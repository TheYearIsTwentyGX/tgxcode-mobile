package com.twentygx.tgxcode.data.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.install.PhoneInstaller
import com.twentygx.tgxcode.data.net.StreamPulse
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.data.repo.isRefused
import com.twentygx.tgxcode.data.settings.SettingsStore
import com.twentygx.tgxcode.service.BridgeService
import com.twentygx.tgxcode.service.InstallCheckWorker
import com.twentygx.tgxcode.service.InstallNotifications
import com.twentygx.tgxcode.service.PulseReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything that has to be true for an install to happen with the app closed, and
 * the machinery that keeps it true.
 *
 * The design already allowed it: *Stay connected*'s service holds the stream, and
 * [PhoneInstaller] hears `phone_install` on it. What it lacked was recovery when the
 * phone does what phones do:
 *
 * - **A network change.** Wi-Fi to cellular leaves the stream on an interface that is
 *   gone. The default-network callback drops it and reconnects at once.
 * - **A dead socket while the phone sleeps.** The read timeout and the backoff both run
 *   on a clock that stops in deep sleep, and a dead socket never wakes the phone. The
 *   pulse alarm does, about every ten minutes, and [BackgroundRules.pulse] decides
 *   from the stream's last byte whether to reconnect.
 * - **The service gone.** Android, or the OEM's battery manager, killed it. The
 *   fallback job ([InstallCheckWorker]) restarts it where Android allows, and reads the
 *   queue itself either way.
 * - **Battery optimisation**, behind most of the above. The exemption is what lets a
 *   background service start and keeps the network in Doze, so the first install that
 *   arrives without it asks for it once.
 *
 * And it says so: [status] is the Settings card's "Background installs".
 */
class BackgroundHealth(
    context: Context,
    private val repo: BridgeRepository,
    private val installs: PhoneInstaller,
    private val settings: SettingsStore,
    private val stayConnected: StateFlow<Boolean>,
    private val pulse: StreamPulse,
    private val scope: CoroutineScope,
    private val onNetworkChanged: () -> Unit,
) {
    private val context: Context = context.applicationContext

    /** What can only be asked of the system, re-read on every resume. */
    private data class Probes(val batteryExempt: Boolean, val notificationsAllowed: Boolean)

    private val probes = MutableStateFlow(probe())

    private val lastConnectedAt = MutableStateFlow<Long?>(null)
    private val lastEventAt = MutableStateFlow<Long?>(null)
    private val lastCheck = MutableStateFlow<Pair<Long, String?>?>(null)

    val status: StateFlow<BackgroundStatus> = combine(
        combine(stayConnected, BridgeService.running, repo.connection, ::Triple),
        combine(lastConnectedAt, lastEventAt, lastCheck, ::Triple),
        probes,
        installs.canInstall,
    ) { (stay, running, connection), (connectedAt, eventAt, check), probe, canInstall ->
        BackgroundStatus(
            stayConnected = stay,
            serviceRunning = running,
            connection = connection,
            lastConnectedAt = connectedAt,
            lastEventAt = eventAt,
            batteryExempt = probe.batteryExempt,
            canInstall = canInstall,
            notificationsAllowed = probe.notificationsAllowed,
            lastCheckAt = check?.first,
            lastCheckResult = check?.second,
        )
    }.stateIn(scope, SharingStarted.Eagerly, BackgroundStatus())

    private val _batteryPrompt = MutableStateFlow(false)

    /** The one-time "let installs run in the background?" dialog is up. */
    val batteryPrompt: StateFlow<Boolean> = _batteryPrompt.asStateFlow()

    @Volatile
    private var visible = false

    /** From [com.twentygx.tgxcode.TgxApp.onCreate]: once per process. */
    fun start() {
        watchNetwork()
        InstallCheckWorker.schedule(context)

        scope.launch {
            lastConnectedAt.value = settings.lastConnectedAt.first()
            lastEventAt.value = settings.lastEventAt.first()
            settings.lastCheck.collect { lastCheck.value = it }
        }
        scope.launch {
            repo.connection.collect { state ->
                if (state == ConnState.Live || state == ConnState.Polling) {
                    val now = System.currentTimeMillis()
                    lastConnectedAt.value = now
                    runCatching { settings.setLastConnectedAt(now) }
                }
            }
        }
        scope.launch {
            var persistedAt = 0L
            repo.events.collect {
                val now = System.currentTimeMillis()
                lastEventAt.value = now
                // Every event would be a disk write per streamed transcript line.
                if (now - persistedAt > PERSIST_EVENT_EVERY_MILLIS) {
                    persistedAt = now
                    runCatching { settings.setLastEventAt(now) }
                }
            }
        }
        scope.launch {
            var was = false
            installs.busy.collect { busy ->
                if (busy && !was) onInstallArriving()
                was = busy
            }
        }
        scope.launch {
            BridgeService.running.collect { running -> if (running) schedulePulse() else cancelPulse() }
        }
    }

    // ------------------------------------------------------------- the UI

    fun onResume() {
        visible = true
        refresh()
    }

    fun onPause() {
        visible = false
    }

    fun refresh() {
        probes.value = probe()
    }

    fun dismissBatteryPrompt() {
        _batteryPrompt.value = false
    }

    /** Settings' "Reconnect": a fresh socket on whatever network there is now. */
    fun reconnectNow() {
        onNetworkChanged()
        repo.reconnect()
    }

    /** Settings' "Run now". */
    fun checkNow() = InstallCheckWorker.checkNow(context)

    suspend fun recordCheck(result: String) {
        val now = System.currentTimeMillis()
        lastCheck.value = now to result
        runCatching { settings.setLastCheck(now, result) }
    }

    private fun probe(): Probes = Probes(
        batteryExempt = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    /**
     * The first install to arrive while Android may still freeze the app: ask, once.
     * On screen it is a dialog; off screen, a notification whose tap is the request.
     */
    private fun onInstallArriving() {
        refresh()
        scope.launch {
            if (!BackgroundRules.promptForBattery(probes.value.batteryExempt, settings.batteryAsked())) return@launch
            settings.setBatteryAsked()
            Tgx.d("install arriving without the battery exemption — asking once")
            if (visible) _batteryPrompt.value = true else InstallNotifications.showBatteryPrompt(context)
        }
    }

    // ------------------------------------------------------------- the service

    /**
     * Start *Stay connected*'s service if it should be running and is not. From the
     * background Android may refuse; [BridgeService.start] says so rather than throwing.
     */
    suspend fun ensureService(why: String): Boolean {
        if (BridgeService.running.value) return true
        if (!settings.stayConnected.first()) return false
        Tgx.d("connection service not running — starting it ($why)")
        return BridgeService.start(context)
    }

    /**
     * Wake-up check on the connection: reconnect a stream that has gone quiet, or cut
     * a backoff short. Waits a little for it to come back, so the caller's wake lock
     * (the alarm's, or the job's) covers the reconnect and the catch-up after it.
     */
    suspend fun pulseCheck() {
        val state = repo.connection.value
        val action = BackgroundRules.pulse(state, pulse.lastByteAt, SystemClock.elapsedRealtime(), BridgeService.running.value)
        if (action != BackgroundRules.Pulse.Reconnect) return
        Tgx.w("pulse: connection is $state, last byte ${(SystemClock.elapsedRealtime() - pulse.lastByteAt) / 1000}s ago — reconnecting")
        reconnectNow()
        withTimeoutOrNull(RECONNECT_WAIT_MILLIS) {
            repo.connection.first { it == ConnState.Live || it == ConnState.Polling || it.isRefused }
        }
    }

    /** From [PulseReceiver]: check, and book the next one while the service runs. */
    suspend fun onPulse() {
        ensureService("pulse")
        pulseCheck()
        if (BridgeService.running.value) schedulePulse()
    }

    private fun pulseIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PulseReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Inexact and allowed while idle: no exact-alarm permission, and Android limits it
     * to about one every nine minutes in Doze, which is all this needs.
     */
    private fun schedulePulse() {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + PULSE_EVERY_MILLIS,
                pulseIntent(),
            )
        }.onFailure { Tgx.w("could not book the pulse alarm", it) }
    }

    private fun cancelPulse() {
        context.getSystemService(AlarmManager::class.java)?.cancel(pulseIntent())
    }

    // ------------------------------------------------------------- the network

    /**
     * Reconnect as soon as the default network changes. Without this the stream stays
     * bound to the old interface until the read timeout notices — which, with the
     * phone asleep, can be hours.
     */
    private fun watchNetwork() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Long? = null

            // Registering reports the network already in use, which is not news. With
            // none, the first network to arrive is.
            private var first = connectivity.activeNetwork != null

            override fun onAvailable(network: Network) {
                val next = network.networkHandle
                val reconnect = BackgroundRules.reconnectOnNetwork(current, next, first)
                first = false
                current = next
                if (reconnect) {
                    Tgx.d("default network changed — reconnecting")
                    reconnectNow()
                }
            }

            override fun onLost(network: Network) {
                if (network.networkHandle == current) current = null
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { Tgx.w("could not watch the network", it) }
    }

    companion object {
        private const val PERSIST_EVENT_EVERY_MILLIS = 60_000L
        private const val PULSE_EVERY_MILLIS = 10 * 60_000L
        private const val RECONNECT_WAIT_MILLIS = 20_000L
    }
}
