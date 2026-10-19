package com.twentygx.tgxcode.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.twentygx.tgxcode.TgxApp
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.repo.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Holds the SSE connection while the app is backgrounded, and turns asks into
 * notifications.
 *
 * This is the whole reason for a native client. From `docs/api.md`:
 *
 * > With no SSE client attached, an ask is denied immediately — there is nobody to
 * > ask. A connected phone is what makes a session answerable when nobody is at the
 * > desk.
 *
 * A web page cannot do this; `web/mobile.js` deliberately tears its `EventSource`
 * down when backgrounded because a backgrounded stream is not reliably alive. So the
 * app *is* the push channel, and needs no FCM and no bridge-side work.
 *
 * **Why `specialUse` and not `dataSync`.** `dataSync` is the semantic near-fit, but
 * on Android 15+ it is capped at six hours per 24, and the quota only refills when
 * the app is brought to the foreground — which defeats the exact case this exists
 * for, a phone on a table overnight. It also cannot be started from
 * `BOOT_COMPLETED` at API 35+. `specialUse` costs a Play Store review of the
 * declared justification, which does not apply to an app installed over adb.
 */
class BridgeService : Service() {

    private var scope: CoroutineScope? = null
    private var lease: AutoCloseable? = null
    private var job: Job? = null

    /** Sessions we currently hold a notification for, so stale ones can be cleared. */
    private val shown = mutableMapOf<String, Ask>()

    /** Of [shown], those posted silently because an orchestrator is carrying them. */
    private val quietShown = mutableSetOf<String>()

    /**
     * Whether a fresh ask on [sessionId] is its orchestrator's to answer. The board says
     * so once it has caught up; until then, a worker whose orchestrator is listed and
     * not paused is taken to be carried — the bridge's own starting assumption.
     */
    private fun carried(repo: com.twentygx.tgxcode.data.repo.BridgeRepository, sessionId: String): Boolean {
        repo.board.value?.sessions?.firstOrNull { it.sessionId == sessionId && it.ask != null }?.let {
            return it.askForOrchestrator
        }
        val worker = repo.sessions.value.firstOrNull { it.sessionId == sessionId }?.worker ?: return false
        if (worker.closed) return false
        val host = repo.sessions.value.firstOrNull { it.sessionId == worker.orchestratorId } ?: return false
        return host.orchestrator?.paused == false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as TgxApp
        startForeground(connecting())

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = serviceScope
        lease = app.container.repository.acquire()
        Tgx.d("service started")

        job = serviceScope.launch {
            launch { watchConnection(app) }
            launch { watchAsks(app) }
            launch { watchStalls(app) }
        }
    }

    private suspend fun watchConnection(app: TgxApp) {
        app.container.repository.connection.collectLatest { state ->
            val waiting = app.container.repository.board.value?.waiting ?: 0
            val notice = when (state) {
                ConnState.Live -> AskNotifications.ServiceState(
                    "Connected",
                    if (waiting > 0) {
                        "$waiting session${if (waiting == 1) "" else "s"} need you"
                    } else {
                        "Nothing waiting"
                    },
                )
                ConnState.Connecting -> AskNotifications.ServiceState(
                    "Reconnecting…",
                    "Asks are auto-denied while disconnected",
                )
                // Deliberately not the Connecting copy. "Asks are auto-denied" is
                // exactly what this mode prevents — the stream socket is held open
                // precisely so the bridge still counts a viewer — so borrowing that
                // line here would be a lie in the one place the user checks.
                ConnState.Polling -> AskNotifications.ServiceState(
                    if (waiting > 0) {
                        "Checking — $waiting session${if (waiting == 1) "" else "s"} need you"
                    } else {
                        "Checking every few seconds"
                    },
                    "This network will not carry a live stream",
                )
                ConnState.NotPaired -> AskNotifications.ServiceState(
                    "Not paired",
                    "This device has no token",
                )
                ConnState.AccessDenied -> AskNotifications.ServiceState(
                    "Blocked by Cloudflare Access",
                    "Check the service token in Settings — asks are being auto-denied",
                )
                else -> AskNotifications.ServiceState(
                    "Not connected",
                    "Asks will be auto-denied",
                )
            }
            startForeground(notice)
        }
    }

    /**
     * Three sources have to agree, and the third is the one that is easy to miss.
     *
     * 1. A `permission-request` raises a notification.
     * 2. A `permission-resolved` cancels it — somebody answered at the desk.
     * 3. **Reconnect reconciliation.** Nothing replays, so a resolution that fired
     *    into a closed socket is gone forever. After every board refresh, cancel any
     *    notification the board no longer lists as blocked. Without this a phone that
     *    lost signal for ten minutes offers to allow something decided long ago —
     *    worse than no notification at all.
     *
     * An orchestrator's worker is the one exception to "loud": its orchestrator is
     * expected to answer, so the notification is posted silently while the board says
     * the ask is the orchestrator's, and re-posted aloud once it turns `you` — stuck.
     */
    private suspend fun watchAsks(app: TgxApp) {
        val repo = app.container.repository
        val context: Context = this

        scope?.launch {
            repo.askEvents.collect { event ->
                when (event) {
                    is StreamEvent.PermissionRequested -> {
                        val ask = event.ask
                        val id = ask.sessionId ?: return@collect
                        shown[id] = ask
                        val quiet = carried(repo, id)
                        if (quiet) quietShown += id else quietShown -= id
                        AskNotifications.show(context, ask, repo.titleOf(id), quiet)
                    }
                    is StreamEvent.PermissionResolved -> {
                        shown.remove(event.sessionId)
                        quietShown -= event.sessionId
                        AskNotifications.cancel(context, event.sessionId)
                    }
                    else -> Unit
                }
            }
        }

        repo.board.collectLatest { board ->
            if (board == null) return@collectLatest
            val blocked = board.sessions.mapNotNull { card ->
                card.ask?.let { card.sessionId to it.copy(sessionId = card.sessionId) }
            }.toMap()
            val carriedNow = board.sessions.filter { it.askForOrchestrator }.map { it.sessionId }.toSet()

            // Raise anything the board knows about that we missed — and say aloud a
            // worker's ask its orchestrator has stopped getting to.
            for ((sessionId, ask) in blocked) {
                val quiet = sessionId in carriedNow
                val louder = sessionId in quietShown && !quiet
                if (shown[sessionId]?.requestId != ask.requestId || louder) {
                    shown[sessionId] = ask
                    if (quiet) quietShown += sessionId else quietShown -= sessionId
                    AskNotifications.show(context, ask, repo.titleOf(sessionId), quiet)
                }
            }
            quietShown.retainAll(blocked.keys)
            // Clear anything that is no longer blocked.
            for (sessionId in shown.keys - blocked.keys) {
                AskNotifications.cancel(context, sessionId)
            }
            shown.keys.retainAll(blocked.keys)
        }
    }

    /** One notification per crossing into stalled; the crossing back takes it down. */
    private suspend fun watchStalls(app: TgxApp) {
        val repo = app.container.repository
        repo.events.collect { event ->
            if (event !is StreamEvent.RunnerStalled) return@collect
            if (event.stalled) {
                AskNotifications.showStalled(
                    this,
                    event.sessionId,
                    repo.titleOf(event.sessionId),
                    event.stalledAfterMs?.let { it / 60_000 },
                )
            } else {
                AskNotifications.cancelStalled(this, event.sessionId)
            }
        }
    }

    private fun connecting() =
        AskNotifications.ServiceState("Connecting…", "Holding a connection to the bridge")

    private fun startForeground(state: AskNotifications.ServiceState) {
        ServiceCompat.startForeground(
            this,
            AskNotifications.SERVICE_ID,
            AskNotifications.serviceNotification(this, state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Defensive: `specialUse` has no documented timeout, but implementing this costs
     * nothing and is insurance against a future platform change. A dataSync service
     * that ignores it takes a fatal RemoteServiceException.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Tgx.w("foreground service timed out — stopping cleanly")
        stopSelf()
    }

    override fun onDestroy() {
        Tgx.d("service stopping")
        lease?.close()
        lease = null
        job?.cancel()
        scope?.cancel()
        scope = null
        shown.keys.forEach { AskNotifications.cancel(this, it) }
        shown.clear()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
