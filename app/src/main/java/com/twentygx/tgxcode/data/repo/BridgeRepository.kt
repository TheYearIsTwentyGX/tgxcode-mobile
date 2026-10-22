package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.core.isUnauthorized
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.CreateSessionRequest
import com.twentygx.tgxcode.data.model.Decision
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.DraftResponse
import com.twentygx.tgxcode.data.model.FlagsRequest
import com.twentygx.tgxcode.data.model.Health
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.QueueWriteResponse
import com.twentygx.tgxcode.data.model.SessionPrsResponse
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.SendRequest
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.model.SubscribeRequest
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.model.SuggestionActRequest
import com.twentygx.tgxcode.data.model.TaskStatus
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.net.SseSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.random.Random

/**
 * How the open-task badge should move when one task changes status.
 *
 * Taken from the row's **actual** previous status, not from the direction of travel.
 * Undoing something already open, or dismissing something already dismissed, is a
 * no-op the UI can still ask for — a stale decision map on an inline card is enough —
 * and assuming a ±1 there makes the badge disagree with the list until the next read.
 *
 * @param before null when the task is not in the current filter's list at all, so its
 *   old status is genuinely unknown. The direction of travel is then the best guess
 *   available, and the next `loadTasks` settles it either way.
 */
internal fun openDelta(before: String?, after: String): Int {
    if (before == after) return 0
    val wasOpen = before?.let { it == TaskStatus.OPEN } ?: (after != TaskStatus.OPEN)
    val isOpen = after == TaskStatus.OPEN
    return when {
        wasOpen == isOpen -> 0
        isOpen -> 1
        else -> -1
    }
}

/**
 * The `POST …/send` body, out here so the rules in it can be tested on their own.
 *
 * A blank model is left out (absent and null both mean "as you were"); effort is sent
 * as given, `""` included, because there it is `inherit` and is honoured; and a
 * `fromUuid` brings `fork` with it, since the bridge refuses one without the other.
 */
internal fun sendBody(
    text: String,
    mode: String,
    model: String = "",
    effort: String? = null,
    fork: Boolean = false,
    fromUuid: String? = null,
): SendRequest = SendRequest(
    text = text,
    permissionMode = mode,
    model = model.takeIf { it.isNotBlank() },
    effort = effort,
    fork = (fork || fromUuid != null).takeIf { it },
    fromUuid = fromUuid,
)

/**
 * What the connection dot shows.
 *
 * [Polling] is the odd one: it is *working*, but by asking rather than by being told,
 * because this network will not carry a live stream. Held apart from [Live] because a
 * two-second lag looks like a bug unless the UI says otherwise, and apart from
 * [Offline] because asks are still being answered.
 */
enum class ConnState { Offline, Connecting, Live, Polling, NotPaired, Unconfigured }

/** Something worth a toast. */
data class UserMessage(val text: String, val isError: Boolean = false)

/** Messages that never became a turn, handed back for the composer of [sessionId]. */
data class Unsent(val sessionId: String, val texts: List<String>, val kind: String? = null)

/** Navigation the *bridge* asked for, not the user. */
sealed interface NavCommand {
    data class Follow(val sessionId: String) : NavCommand
    data class Deleted(val sessionId: String) : NavCommand
}

/**
 * The single source of truth, and the owner of the live connection.
 *
 * Application-scoped, never held by a ViewModel. Rotating the phone must not drop
 * the SSE stream, because `pool.hasViewer` going false means the bridge starts
 * **auto-denying asks** — a dropped connection is not a cosmetic problem here.
 */
class BridgeRepository(
    private val api: BridgeApi,
    private val sse: BridgeSse,
    private val scope: CoroutineScope,
    /**
     * Overridable so a test does not have to wait six real seconds to watch the
     * fallback happen. Production never passes these; the defaults beside them are the
     * documentation.
     */
    private val helloGraceMillis: Long = HELLO_GRACE_MILLIS,
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val _connection = MutableStateFlow(ConnState.Unconfigured)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _health = MutableStateFlow<Health?>(null)
    val health: StateFlow<Health?> = _health.asStateFlow()

    private val _board = MutableStateFlow<Overview?>(null)
    val board: StateFlow<Overview?> = _board.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    /** The session currently being opened, so the screen can say what it is doing. */
    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening.asStateFlow()

    /**
     * Suggested follow-ups for the chosen [taskFilter].
     *
     * Re-read rather than pushed. `docs/api.md:855` is explicit that there is no push
     * for a task being *filed*: a client watching one conversation sees the
     * `suggestion` event on its tail, and anything watching all of them refetches on
     * `sessions-changed`. `suggestion-changed` covers the other half — a decision taken
     * in another window.
     */
    private val _tasks = MutableStateFlow<List<SuggestedTask>>(emptyList())
    val tasks: StateFlow<List<SuggestedTask>> = _tasks.asStateFlow()

    private val _taskFilter = MutableStateFlow(TaskStatus.OPEN)
    val taskFilter: StateFlow<String> = _taskFilter.asStateFlow()

    /**
     * How many offers are still open, for the tab badge.
     *
     * Held apart from [tasks] because that list follows the filter — switching to
     * Dismissed must not empty the badge, which is about work outstanding rather than
     * about what is on screen.
     */
    private val _openTasks = MutableStateFlow(0)
    val openTasks: StateFlow<Int> = _openTasks.asStateFlow()

    /**
     * Never fetched after the first load: `drafts-changed` carries the whole
     * `GET /api/drafts` payload, so there is nothing to refetch when one changes.
     */
    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    /**
     * One aggregate PR status per session, for a glyph per row.
     *
     * The bridge's own ranking (`ATTENTION_ORDER` in `bridge/pulls.js`), **never
     * re-derived here** — it keeps a second, different precedence order for a single
     * PR, and a client that folds a session's set itself will eventually pick the
     * wrong one and report no error.
     *
     * Null until the first read, and a session absent from [PrsPayload.sessions] has
     * pull requests nobody has resolved *yet* — which is not the same as having none.
     * `prs` on the summary is what says that.
     */
    private val _prs = MutableStateFlow<PrsPayload?>(null)
    val prs: StateFlow<PrsPayload?> = _prs.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val _commands = MutableSharedFlow<NavCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<NavCommand> = _commands.asSharedFlow()

    /**
     * Text the bridge handed back because it never became a turn — a `send-failed`, or
     * the messages a stop took off the queue. Keyed by session so the composer only
     * takes back what was its own.
     */
    private val _unsent = MutableSharedFlow<Unsent>(extraBufferCapacity = 8)
    val unsent: SharedFlow<Unsent> = _unsent.asSharedFlow()

    /**
     * Permission traffic for *every* session, not just the open one.
     *
     * The conversation screen only cares about the session it is showing, but the
     * notification layer cares about all of them — the whole point is answering a
     * session you are not looking at.
     */
    private val _askEvents = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 32)
    val askEvents: SharedFlow<StreamEvent> = _askEvents.asSharedFlow()

    /**
     * Every stream event, after this repository has applied it.
     *
     * The seam the feature stores hang off (quota, notifications, snippets…), so each
     * of them owns its own state without this class growing a field and a branch per
     * feature. Emitted *after* [handle] has run, so a store reading [conversation] in
     * response sees the state the event produced.
     */
    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<StreamEvent> = _events.asSharedFlow()

    /**
     * "Re-read whatever you hold": the connection was (re)established, or somebody
     * pulled to refresh. Nothing is replayed to a client that was away, so a store that
     * only listened to [events] would sit on whatever it last heard before the gap.
     */
    private val _resyncs = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val resyncs: SharedFlow<Unit> = _resyncs.asSharedFlow()

    /**
     * One tick per polling round, carrying the round number, while the transport will
     * not carry a stream. A store whose pushes never arrive in that mode picks a tier
     * off this — the same scheme as [BOARD_EVERY] and friends.
     */
    private val _pollRounds = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val pollRounds: SharedFlow<Int> = _pollRounds.asSharedFlow()

    /**
     * Say something to the user from outside the repository.
     *
     * The snackbar host is wired to [messages] and nothing else, so a ViewModel with
     * something to report needs a way in rather than a second channel of its own.
     */
    suspend fun report(text: String, isError: Boolean = false) {
        _messages.emit(UserMessage(text, isError))
    }

    /** Best title we know for a session, for a notification headline. */
    fun titleOf(sessionId: String): String? =
        _board.value?.sessions?.firstOrNull { it.sessionId == sessionId }?.title
            ?: _sessions.value.firstOrNull { it.sessionId == sessionId }?.title

    /** Modes picked but not yet sent, per session. Survives leaving the screen. */
    private val pendingModes = mutableMapOf<String, String>()

    /**
     * Model and effort picks not yet sent, per session, and for the same reason as
     * [pendingModes]. `""` is a real pick — `inherit` — so these hold strings rather
     * than treating empty as unset.
     */
    private val pendingModels = mutableMapOf<String, String>()
    private val pendingEfforts = mutableMapOf<String, String>()

    private var clientId: String? = null
    private var streamJob: Job? = null

    /**
     * Set once this transport is known to buffer the stream, and then left alone.
     *
     * Sticky for the same reason `poll.on` is in `web/mobile.js`: having established
     * that this network eats streams, re-litigating it on every lease cycle costs six
     * seconds of blank board each time. Cleared only by [onOriginChanged], because a
     * *different* origin is genuinely a different question — which is one better than
     * the web client, where only a page reload clears it.
     */
    private var buffered = false
    private var pollJob: Job? = null

    private val lock = Mutex()

    /** How many things want the connection open. See [acquire]. */
    private var leases = 0

    companion object {
        /**
         * `?tail=120`. A 60-turn session is ~1,800 events and half a megabyte of
         * JSON, and none of the first 1,500 is why someone opened their phone.
         */
        const val TAIL = 120

        /**
         * How long to keep trying a freshly created session. Measured at ~3.5s on a
         * warm local bridge; this leaves room for a cold start over a relay.
         */
        const val CREATION_GRACE_MILLIS = 45_000L

        /**
         * How long to wait for `hello` before concluding the stream will never work.
         *
         * The bridge writes it before the ping timer exists, so on any transport that
         * works at all it lands in well under a second — 0.04s on loopback, 0.29s
         * through the named tunnel. Six seconds is far past that and still short
         * enough that the fallback is not a visible outage.
         */
        const val HELLO_GRACE_MILLIS = 6_000L

        /** How often to poll, once polling. Fast enough that answering an ask feels immediate. */
        const val POLL_MILLIS = 2_500L

        /** The board is ~7KB and a transcript delta is 42 bytes; they do not deserve the same rate. */
        const val BOARD_EVERY = 2

        /**
         * The sessions list and the task badge, every 20s.
         *
         * A tier `/m` has no counterpart for: it draws only Needs-you while polling, so
         * it never had a Sessions list or a Tasks tab to keep warm. Both normally ride on
         * `sessions-changed`, and nothing pushes in this mode.
         */
        const val INDEX_EVERY = 8

        /**
         * The PR glyphs, every 60s — and deliberately not faster.
         *
         * The bridge's own refresher only *looks* every 30s, and its shortest
         * per-repository interval is a 60s floor for a repository with a session
         * working in it, rising to 2 minutes while checks run and 20 minutes when
         * idle. Asking more often than a minute therefore cannot learn anything: it
         * re-reads the same in-memory snapshot and spends relay round trips and
         * battery to be told so.
         *
         * 24 is a multiple of both [BOARD_EVERY] and [INDEX_EVERY], so this tier
         * always lands on a round that was already going to talk — one fat round a
         * minute rather than three staggered ones, which is the right shape for a
         * radio.
         */
        const val PRS_EVERY = 24

        /**
         * `?tail=0` — "none of them", not "no limit".
         *
         * Everything a liveness check needs (runner state, `pendingPermission`, the
         * offset) without the transcript, at a few hundred bytes rather than half a
         * megabyte. Landed upstream in PR #31; against a bridge older than that the old
         * `want > 0` guard makes this the *most* expensive call on the route, so a
         * polling client wants a bridge on current `main`.
         */
        const val LIVENESS_TAIL = 0
    }

    // ------------------------------------------------------------------ leases

    /**
     * One connection, however many things want it.
     *
     * The UI holds a lease while started; the foreground service holds one for its
     * lifetime. With both, there is still exactly one `/api/events` stream and one
     * `clientId` — the bridge counts clients, and we simply never give it two.
     */
    fun acquire(): AutoCloseable {
        scope.launch {
            lock.withLock {
                leases++
                Tgx.d("lease acquired (now $leases)")
                if (streamJob == null) startStream()
            }
        }
        return AutoCloseable {
            scope.launch {
                lock.withLock {
                    leases = (leases - 1).coerceAtLeast(0)
                    if (leases == 0) stopStream()
                }
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        stopPolling()
        clientId = null
        if (_connection.value == ConnState.Live ||
            _connection.value == ConnState.Connecting ||
            _connection.value == ConnState.Polling
        ) {
            _connection.value = ConnState.Offline
        }
    }

    /**
     * Forget what we learned about the last transport.
     *
     * Called when the origin changes, and only then. A phone that fell back to polling
     * on a bad tunnel and then moved to a good one should get its stream back, and the
     * origin changing is the one moment at which "this network eats streams" stops
     * being evidence about anything.
     */
    fun onOriginChanged() {
        buffered = false
    }

    // ------------------------------------------------------------- the stream

    private fun startStream() {
        streamJob = scope.launch {
            var attempt = 0
            while (isActive) {
                // Checked at the top as well as after the collector below. A token the
                // bridge refuses is not a reason to keep reopening anything — and
                // reopening would call [startPolling] again, resurrecting a poll that
                // had already stopped itself for exactly this reason.
                if (_connection.value == ConnState.NotPaired) return@launch
                val held = buffered
                if (held) {
                    // The socket is opened for the bridge's benefit, not ours. Get the
                    // poll running first so the screen has data while it connects.
                    startPolling()
                } else {
                    _connection.value = ConnState.Connecting
                }
                Tgx.d("opening /api/events (attempt $attempt, held=$held)")
                var sawHello = false
                var sawOpen = false

                coroutineScope {
                    val collector = launch {
                        sse.stream(held = held).collect { signal ->
                            when (signal) {
                                is SseSignal.Open -> sawOpen = true
                                is SseSignal.Message -> {
                                    if (signal.event is StreamEvent.Hello) sawHello = true
                                    handle(signal.event)
                                    _events.emit(signal.event)
                                }
                                is SseSignal.Closed -> {
                                    Tgx.w("stream closed: http=${signal.httpStatus}", signal.cause)
                                    if (signal.httpStatus == 401) {
                                        _connection.value = ConnState.NotPaired
                                        return@collect
                                    }
                                    // Polling owns the state in buffered mode: the
                                    // socket dropping is worth reconnecting, not worth
                                    // telling the user the app is offline while it
                                    // still works.
                                    if (!buffered) _connection.value = ConnState.Offline
                                }
                            }
                        }
                    }

                    // The watchdog. `hello` is the only thing that distinguishes a
                    // healthy idle stream from one a proxy is swallowing, so the
                    // decision is made once, up front, on a timer — never by watching
                    // for silence later.
                    val watchdog = if (held) {
                        null
                    } else {
                        launch {
                            delay(helloGraceMillis)
                            if (sawHello) return@launch
                            Tgx.w(
                                "no hello in ${helloGraceMillis}ms — this transport is " +
                                    "buffering the stream; polling instead",
                            )
                            buffered = true
                            startPolling()
                            // Cancel the collection only — never this scope, which
                            // `coroutineScope` would rethrow, taking the whole retry
                            // loop with it. Dropping the collector falls through to the
                            // top of the loop, which reopens on `heldStream`: the same
                            // socket with its read timeout off, so it survives being
                            // sent nothing at all.
                            collector.cancel()
                        }
                    }

                    collector.join()
                    watchdog?.cancel()
                }

                if (_connection.value == ConnState.NotPaired) return@launch

                // A clean run that got as far as `hello` starts its backoff over —
                // an hour-long connection dropping once should reconnect instantly,
                // not wait thirty seconds.
                //
                // In buffered mode `hello` never arrives, so keying the reset on it
                // alone would climb to the 30s ceiling on every network blip while the
                // socket is the only thing keeping asks answerable. Opening it at all
                // is the success worth resetting on there.
                attempt = if (sawHello || (buffered && sawOpen)) 0 else attempt + 1
                delay(backoffMillis(attempt))
            }
        }
    }

    /**
     * Aggressive rather than polite, because every second disconnected is a second
     * in which an arriving ask is auto-denied. Full jitter so several clients do not
     * synchronise on a bridge restart.
     */
    private fun backoffMillis(attempt: Int): Long {
        if (attempt <= 0) return 0
        val base = minOf(30_000L, 250L * (1L shl minOf(attempt, 7)))
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    // ------------------------------------------------------------- polling

    /**
     * What to do when the network will not carry a stream.
     *
     * Not a protocol change: the same routes the stream feeds from, read on a timer, so
     * everything downstream is identical either way — [applyEvents] does not know which
     * mode delivered its events, and `BridgeService` does not know whether an ask
     * arrived on a push or was noticed by a poll.
     *
     * It is cheap enough to be barely a compromise. Measured through the tunnel: ~62ms
     * and 7KB for the board, and **42 bytes** for a delta with nothing in it. What is
     * lost is that liveness becomes a couple of seconds granular instead of instant,
     * which for "has it finished, does it need me" is a distinction without a
     * difference.
     *
     * Idempotent — [startStream] calls it on every reconnect attempt once [buffered].
     */
    private fun startPolling() {
        // Belt and braces with the check in [startStream]: polling a bridge that has
        // refused this token only discovers the same 401 every couple of seconds.
        if (_connection.value == ConnState.NotPaired) return
        if (pollJob?.isActive == true) {
            _connection.value = ConnState.Polling
            return
        }
        pollJob = scope.launch {
            _connection.value = ConnState.Polling
            resync()
            var round = 0
            while (isActive) {
                val thisRound = round++
                tickPoll(thisRound)
                // The stream loop bails out on NotPaired and so must this one: a token
                // the bridge refuses will keep being refused, and polling it every two
                // and a half seconds forever is the version of this bug that costs
                // battery instead of just being wrong.
                if (_connection.value == ConnState.NotPaired) return@launch
                _pollRounds.tryEmit(thisRound)
                delay(pollMillis)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * One round of "what changed", using only ordinary GETs.
     *
     * **Deliberately not gated on whether the app is foregrounded.** `web/mobile.js`
     * returns early when its tab is hidden, and copying that here would undo the reason
     * this app exists: a phone in a pocket is exactly when the board needs to be
     * current, because it is when nobody is at the desk to answer instead.
     */
    private suspend fun tickPoll(round: Int) {
        // The board: the needs-you cards, the waiting badge, and — because
        // `BridgeService` reconciles off it — every notification for a session other
        // than the open one.
        if (round % BOARD_EVERY == 0) {
            when (val result = api.overview()) {
                is ApiResult.Ok -> publishBoard(result.value)
                else -> if (result.isUnauthorized) return setPaired(false)
            }
        }

        // The list and the task badge. Both normally ride on `sessions-changed`, which
        // never arrives here, so they get a slower tier of their own rather than going
        // stale for as long as the app is up.
        if (round % INDEX_EVERY == 0) {
            loadSessions()
            loadTasks()
        }

        // The PR glyphs. `prs-changed` never arrives here, so without a tier of their
        // own they would freeze for as long as the app is up.
        if (round % PRS_EVERY == 0) {
            loadPrs()
            loadOpenPrs()
        }

        val open = _conversation.value ?: return stillPolling()

        // Liveness for the open session: runner state, and any ask waiting on it.
        // `?tail=0` exists for exactly this — the metadata without the transcript.
        //
        // `runner` is the only field read. The response also carries `summary`,
        // `suggestions`, `prefs`, an `offset` and a `truncated` of
        // `{dropped: total, total}` — folding that in would claim every event in the
        // open transcript is missing and put a "load all" button over a conversation
        // already fully in hand.
        when (val meta = api.session(open.sessionId, tail = LIVENESS_TAIL)) {
            is ApiResult.Ok -> applyPolledRunner(open.sessionId, meta.value.runner)
            else -> if (meta.isUnauthorized) return setPaired(false)
        }

        // And whatever was appended since we last looked.
        when (val delta = api.since(open.sessionId, open.offset)) {
            is ApiResult.Ok ->
                if (delta.value.reset) {
                    openSession(open.sessionId, force = true)
                } else {
                    currentIf(open.sessionId)?.let {
                        _conversation.value = it.applyEvents(delta.value.events, delta.value.offset)
                    }
                }
            else -> if (delta.isUnauthorized) return setPaired(false)
        }

        stillPolling()
    }

    /**
     * Re-asserted at the end of every round that got through, on both exits.
     *
     * A round that failed for any reason other than 401 says nothing worth acting on —
     * the next one is along in a couple of seconds — so nothing writes Offline over
     * Polling, which would make one dropped request look like a dead app. Saying it
     * again on success is what heals the state if anything else ever does.
     */
    private fun stillPolling() {
        if (_connection.value != ConnState.NotPaired) _connection.value = ConnState.Polling
    }

    /**
     * Reconcile a polled runner status with what is on screen.
     *
     * The stream is *told* when an ask appears and when it is answered. Polling only
     * ever sees the current state, so both edges have to be inferred — and then
     * announced on [askEvents], because that is what the notification layer listens to.
     * Feeding it here is what makes `BridgeService` work unchanged in both modes.
     *
     * Keyed on `requestId` rather than on presence, so that re-seeing the same ask is
     * not a change: re-rendering an unchanged card would throw away a half-filled
     * question answer under the user's hands.
     *
     * Writing [RunnerStatus.permissionMode] through is quietly load-bearing.
     * `AppViewModel.modeFor` reads the mode from here, and `POST …/send` treats an
     * absent `permissionMode` as "set it to auto" — so dropping the runner on this path
     * would drop the session out of `acceptEdits` on the next message sent.
     */
    private suspend fun applyPolledRunner(sessionId: String, runner: RunnerStatus?) {
        val open = currentIf(sessionId) ?: return
        val ask = runner?.pendingPermission?.copy(sessionId = sessionId)
        val showing = open.ask?.requestId

        _conversation.value = when {
            ask != null && ask.requestId != showing -> open.copy(runner = runner, ask = ask, lastOutcome = null)
            ask == null && showing != null -> open.copy(runner = runner, ask = null, lastOutcome = "answered")
            else -> open.copy(runner = runner)
        }

        if (ask != null && ask.requestId != showing) {
            _askEvents.emit(StreamEvent.PermissionRequested(ask))
        } else if (ask == null && showing != null) {
            // Polling cannot know *how* it was answered — the outcome rides on the
            // event we are not receiving — so it says the neutral thing rather than
            // inventing one.
            _askEvents.emit(StreamEvent.PermissionResolved(sessionId, showing, null))
        }
    }

    private suspend fun handle(event: StreamEvent) {
        when (event) {
            is StreamEvent.Hello -> onHello(event)

            is StreamEvent.BoardChanged -> publishBoard(event.board)

            // The index moved, which is also the only signal that a *new* offer was
            // filed — nothing pushes that.
            is StreamEvent.SessionsChanged -> {
                loadSessions()
                loadTasks()
            }

            // The whole payload, so there is nothing to go and read.
            is StreamEvent.DraftsChanged -> _drafts.value = event.drafts

            // The rows have their whole answer in hand. The open conversation is a
            // *different* question — per-PR rather than the aggregate — so that one
            // has to be re-asked, or its chips sit stale while the rows recolour.
            is StreamEvent.PrsChanged -> {
                publishPrs(event.prs)
                loadOpenPrs()
            }

            is StreamEvent.SuggestionChanged -> {
                loadTasks()
                // The open conversation draws its own offers inline, and their status
                // comes from a map the transcript knows nothing about — so a decision
                // taken anywhere has to be written into it directly.
                event.toolUseId?.let { patchOpenDecision(event.sessionId, it) }
            }

            is StreamEvent.Tail -> currentIf(event.sessionId)?.let { open ->
                _conversation.value = open.applyEvents(event.events, event.offset)
            }

            is StreamEvent.Reset -> currentIf(event.sessionId)?.let {
                openSession(event.sessionId, force = true)
            }

            is StreamEvent.RunnerStatusChanged -> {
                val id = event.status.sessionId ?: return
                currentIf(id)?.let { open ->
                    _conversation.value = open.copy(
                        runner = event.status,
                        // An ask can also *arrive* on runner-status, and this is the
                        // path that catches one raised before we attached.
                        ask = open.ask ?: event.status.pendingPermission
                            ?.copy(sessionId = id),
                    )
                }
            }

            is StreamEvent.PermissionRequested -> {
                val id = event.ask.sessionId ?: return
                _askEvents.emit(event)
                currentIf(id)?.let { open ->
                    _conversation.value = open.copy(ask = event.ask, lastOutcome = null)
                }
            }

            is StreamEvent.PermissionResolved -> {
                _askEvents.emit(event)
                currentIf(event.sessionId)?.let { open ->
                    // Compare requestId before clearing. mobile.js does not, so any
                    // resolution for the open session wipes whatever card is up —
                    // wrong the moment a second ask has replaced the first.
                    if (open.ask == null || open.ask.requestId == event.requestId) {
                        _conversation.value =
                            open.copy(ask = null, lastOutcome = event.outcome ?: "answered")
                    }
                }
            }

            is StreamEvent.SessionDeleted -> {
                if (_conversation.value?.sessionId == event.sessionId) {
                    _messages.emit(
                        UserMessage("“${event.title ?: "That session"}” was deleted"),
                    )
                    _commands.emit(NavCommand.Deleted(event.sessionId))
                }
                loadSessions()
            }

            is StreamEvent.SessionForked -> {
                if (_conversation.value?.sessionId == event.from) {
                    _commands.emit(NavCommand.Follow(event.to))
                }
            }

            // Info-level notices are dropped on purpose; only warnings interrupt.
            is StreamEvent.Notice ->
                if (event.level == "warn") {
                    event.text?.let { _messages.emit(UserMessage(it, isError = true)) }
                }

            is StreamEvent.TurnComplete ->
                if (event.isError && event.sessionId == _conversation.value?.sessionId) {
                    _messages.emit(
                        UserMessage(
                            event.detail ?: "The turn ended with an error",
                            isError = true,
                        ),
                    )
                }

            is StreamEvent.SendFailed -> {
                _messages.emit(
                    UserMessage(sendFailedText(event), isError = true),
                )
                val id = event.sessionId
                // A refused branch is told to the composer even with nothing to hand
                // back: the armed branch has to stand down either way.
                if (id != null && (event.unsent.isNotEmpty() || event.kind == "branch-refused")) {
                    _unsent.emit(Unsent(id, event.unsent, event.kind))
                }
            }

            is StreamEvent.AgentTail, is StreamEvent.AgentReset -> Unit
        }
    }

    /**
     * The resynchronisation point, and the whole recovery story.
     *
     * Nothing is buffered for a disconnected client, so reconnecting always means
     * re-reading rather than assuming the gap was empty. Order matters and matches
     * mobile.js: subscribe *before* catching up, because the overlap between a tail
     * push and a `/since` read is harmless — both are idempotent by event id — while
     * a gap between them is not.
     */
    private suspend fun onHello(hello: StreamEvent.Hello) {
        clientId = hello.clientId
        Tgx.d("hello: clientId=${hello.clientId}")
        _connection.value = ConnState.Live
        resync()
        // Still before [catchUp], which is the invariant that matters — the overlap
        // between a tail push and a `/since` read is harmless, a gap is not. Where it
        // falls among the reads above is not load-bearing.
        subscribe()
        catchUp()
    }

    /**
     * Everything that has to be *read* on attaching, in the order it has to be read in.
     *
     * Shared with the polling path, which is the whole reason it is a function: none of
     * this is pushed, and `hello` is the only thing that used to trigger it. A polling
     * client that skipped it would show an empty Sessions list and an empty Tasks tab
     * indefinitely, because both arrive on `sessions-changed` and nothing pushes.
     */
    private suspend fun resync() {
        refreshHealth()
        // Before the session list, deliberately. Both land on the same screen now, and
        // the board is the part that goes at the top — reading it second means the rows
        // render first and the cards arrive as a prepend, which a lazy list answers by
        // holding its scroll position and pushing them out of sight.
        refreshBoard()
        loadSessions()
        loadTasks()
        loadDrafts()
        // After the rows, deliberately: a glyph is meaningless without one to sit on.
        loadPrs()
        _resyncs.emit(Unit)
    }

    /**
     * The `overview` event is only pushed when the board has actually *changed*, which
     * is right for bandwidth and wrong for a client that just attached: on a quiet
     * minute nothing arrives and the landing screen sits on "waiting for the bridge"
     * while six sessions are running. So it is read once on connect.
     */
    suspend fun refreshBoard() {
        api.overview().getOrNull()?.let { publishBoard(it) }
    }

    /**
     * Write a board, but only if it says something new.
     *
     * [Overview.at] is the time the payload was built, so it differs on every single
     * read — which means `MutableStateFlow`'s own equality dedupe never fires and every
     * poll would emit. That costs more than it sounds: the Sessions list recomposes
     * under the reader's thumb every few seconds, which on a phone can eat the tap it
     * was aimed at. The bridge nulls `at` out of its own signature before deciding
     * whether to push, for the same reason.
     *
     * Kept honest in the other direction too: a real change must still emit, because
     * `BridgeService` reconciles notifications off this flow and nothing else.
     */
    private fun publishBoard(board: Overview) {
        if (_board.value?.copy(at = null) == board.copy(at = null)) return
        _board.value = board
    }

    /**
     * Write a PR payload, but only if it says something new — and only if it is not
     * older than what we already hold.
     *
     * **Two separate hazards, and each one is invisible when it bites.**
     *
     * `checkedAt` is a max over the per-repository clocks, so it moves on *every*
     * read. Leave it in the comparison and `MutableStateFlow`'s equality dedupe never
     * fires, every poll emits, and the Sessions list recomposes under the reader's
     * thumb — which on a phone eats the tap it was aimed at. Same reasoning as
     * [publishBoard] nulling `Overview.at`, and the bridge nulls it out of its own
     * push signature for the same reason. `gh.error` is deliberately *inside* the
     * comparison: a token expiring is a real change and has to repaint the wording.
     *
     * And the stream starts delivering the moment it connects, which is *before*
     * `hello` arrives and runs [resync] — so [resync]'s own read can land after a
     * `prs-changed` push and overwrite it with an older answer. That does not heal:
     * the next push comes only when the answer moves again, which on a quiet
     * repository is twenty minutes. Hence the ordering test.
     */
    private fun publishPrs(payload: PrsPayload) {
        val held = _prs.value
        if (held != null && isOlder(payload.checkedAt, held.checkedAt)) return
        if (held?.copy(checkedAt = null) == payload.copy(checkedAt = null)) return
        _prs.value = payload
    }

    /**
     * ISO-8601 or null.
     *
     * Unparseable or absent is **never** "older" — it is unknown, which is a different
     * thing, and treating it as older would drop the first payload of a bridge that
     * has listed nothing yet (`checkedAt` is legitimately null there).
     */
    private fun isOlder(incoming: String?, held: String?): Boolean {
        val a = incoming?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        val b = held?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return a < b
    }

    /**
     * Pull-to-refresh.
     *
     * Everything here normally arrives by push, so this should be redundant — but
     * "should be" is doing a lot of work in a client that reconnects on its own over
     * a flaky link. When something looks stale the cheapest possible answer is a
     * gesture that re-reads it, rather than the user wondering whether to trust the
     * screen.
     */
    suspend fun refreshAll() {
        refreshHealth()
        loadSessions()
        refreshBoard()
        loadTasks()
        loadDrafts()
        loadPrs()
        loadOpenPrs()
        _resyncs.emit(Unit)
    }

    private suspend fun subscribe() {
        val id = clientId ?: return
        val open = _conversation.value
        val result = api.subscribe(
            SubscribeRequest(
                clientId = id,
                sessionId = open?.sessionId,
                offset = open?.offset ?: 0,
                overview = true,
            ),
        )
        // 404 means the clientId died with the stream; a fresh subscribe cannot fix
        // that, so rebuild the whole connection.
        if (result is ApiResult.Http && result.status == 404) {
            lock.withLock {
                stopStream()
                if (leases > 0) startStream()
            }
        }
    }

    /**
     * Errors here are swallowed on purpose: the stream will bring it, or the next
     * time the app comes to the foreground will.
     */
    private suspend fun catchUp() {
        val open = _conversation.value ?: return
        when (val result = api.since(open.sessionId, open.offset)) {
            is ApiResult.Ok -> {
                if (result.value.reset) {
                    openSession(open.sessionId, force = true)
                } else {
                    _conversation.value =
                        _conversation.value?.applyEvents(result.value.events, result.value.offset)
                }
            }
            else -> Unit
        }
    }

    /**
     * Re-read one session's decisions and apply them to the open conversation.
     *
     * `GET /api/sessions/:id/suggestions` is the decisions alone — much cheaper than
     * refetching a transcript to learn that one card now says "Dismissed".
     */
    private suspend fun patchOpenDecision(sessionId: String?, toolUseId: String) {
        val open = currentIf(sessionId) ?: return
        val fresh = api.sessionSuggestions(open.sessionId).getOrNull() ?: return
        currentIf(open.sessionId)?.let { still ->
            _conversation.value = still.copy(suggestions = fresh.suggestions)
        }
        Tgx.d("suggestion $toolUseId changed on ${open.sessionId}")
    }

    private fun currentIf(sessionId: String?): Conversation? =
        _conversation.value?.takeIf { it.sessionId == sessionId }

    // ------------------------------------------------------------------ loads

    suspend fun refreshHealth() {
        api.health().getOrNull()?.let { _health.value = it }
    }

    /**
     * The stream owns [connection] for Live/Connecting/Offline — it is the only
     * thing that knows. A probe may only *raise* NotPaired, or clear it; it must
     * never write Offline over a stream that is perfectly alive, which is a
     * surprisingly easy way to make a working app look broken.
     */
    private fun setPaired(paired: Boolean) {
        if (!paired) {
            _connection.value = ConnState.NotPaired
        } else if (_connection.value == ConnState.NotPaired ||
            _connection.value == ConnState.Unconfigured
        ) {
            _connection.value = when {
                pollJob?.isActive == true -> ConnState.Polling
                streamJob != null -> ConnState.Connecting
                else -> ConnState.Offline
            }
        }
    }

    suspend fun loadSessions() {
        when (val result = api.sessions()) {
            is ApiResult.Ok -> {
                _sessions.value = result.value.sessions
                setPaired(true)
            }
            else -> if (result.isUnauthorized) setPaired(false)
        }
    }

    fun setTaskFilter(status: String) {
        if (_taskFilter.value == status) return
        _taskFilter.value = status
        scope.launch { loadTasks() }
    }

    /**
     * The Tasks list, plus the open count behind the badge.
     *
     * Two reads rather than one, and only when the filter is not already `open`: the
     * badge counts work outstanding, so it must not go to zero because somebody is
     * looking at the Dismissed chip.
     */
    suspend fun loadTasks() {
        val status = _taskFilter.value
        when (val result = api.suggestions(status)) {
            is ApiResult.Ok -> {
                _tasks.value = result.value.suggestions
                if (status == TaskStatus.OPEN) {
                    _openTasks.value = result.value.suggestions.size
                    return
                }
            }
            else -> if (result.isUnauthorized) setPaired(false)
        }
        if (status != TaskStatus.OPEN) {
            api.suggestions(TaskStatus.OPEN).getOrNull()
                ?.let { _openTasks.value = it.suggestions.size }
        }
    }

    /** Only ever on connect and on pull-to-refresh; `drafts-changed` does the rest. */
    suspend fun loadDrafts() {
        api.drafts().getOrNull()?.let { _drafts.value = it.drafts }
    }

    /**
     * The rows' PR glyphs. On connect, on pull-to-refresh, and on the slow poll tier.
     *
     * Silent on failure, and that is the wanted behaviour: [_prs] staying null leaves
     * every row that has PRs showing a grey glyph and "Asking GitHub…", which is
     * exactly true. Blanking it would say those sessions have no pull requests.
     */
    suspend fun loadPrs() {
        api.prs().getOrNull()?.let { publishPrs(it) }
    }

    /**
     * The open conversation's per-PR detail, for the chip bar.
     *
     * Its own request rather than a slice of [prs], because that surface shows each
     * PR's own status and not the session's aggregate. No guard on the summary having
     * any: a session with none is exactly the one that raises its first
     * mid-conversation, and the bridge answers from its index either way.
     *
     * Silent on failure too — `summary.prs` already put the numbers on screen and they
     * still link.
     */
    private suspend fun loadOpenPrs() {
        val open = _conversation.value ?: return
        val fresh = api.sessionPrs(open.sessionId).getOrNull() ?: return
        // The reader may have left while this was in flight.
        currentIf(open.sessionId)?.let { still ->
            _conversation.value = still.copy(prs = fresh.prs, prsGh = fresh.gh)
        }
    }

    /**
     * One session's PRs for the long-press sheet.
     *
     * Not held in a flow: one screen asks, one screen draws, and the answer is stale
     * the moment the sheet closes.
     */
    suspend fun sessionPrs(sessionId: String): SessionPrsResponse? =
        api.sessionPrs(sessionId).getOrNull()

    /**
     * `GET /api/sessions?limit=1` is the real auth probe — `/api/health` is
     * unauthenticated and proves nothing beyond reachability.
     */
    suspend fun probePairing(): ConnState {
        refreshHealth()
        val result = api.sessions(limit = 1)
        setPaired(!result.isUnauthorized)
        Tgx.d("probe: paired=${!result.isUnauthorized} conn=${_connection.value}")
        return _connection.value
    }

    /**
     * @param awaitCreation tolerate a 404 for a while, because the session was only
     *   just created.
     *
     * `POST /api/sessions` returns a `sessionId` as soon as the process is spawned,
     * but the session is not *readable* until Claude Code has written its transcript
     * to disk — measured at **~3.5s** against the live bridge. Navigating straight to
     * it, which is the obvious thing to do, therefore lands on a 404 and reports
     * "session not found" about a session that is being created perfectly well.
     *
     * This is a consequence of the invariant the whole system rests on: content
     * comes from the transcript, never from the process. A session with no transcript
     * yet genuinely has nothing to show — so the honest fix is to wait for it rather
     * than to invent a shell from the create response.
     */
    suspend fun openSession(
        sessionId: String,
        force: Boolean = false,
        awaitCreation: Boolean = false,
    ) {
        if (!force && _conversation.value?.sessionId == sessionId) return

        _opening.value = sessionId
        try {
            val deadline = System.currentTimeMillis() +
                if (awaitCreation) CREATION_GRACE_MILLIS else 0L

            while (true) {
                // The user moved on, or another open superseded this one.
                if (_opening.value != sessionId) return

                when (val result = api.session(sessionId, tail = TAIL)) {
                    is ApiResult.Ok -> {
                        val detail = result.value
                        _conversation.value = Conversation(
                            sessionId = sessionId,
                            summary = detail.summary,
                            offset = detail.offset,
                            truncated = detail.truncated,
                            runner = detail.runner,
                            // The ask that predates the connection.
                            // `pendingPermission` has no sessionId of its own, so
                            // stamp one in — without it every answer would POST to a
                            // null session.
                            ask = detail.runner?.pendingPermission?.copy(sessionId = sessionId),
                            suggestions = detail.suggestions,
                        ).applyEvents(detail.events, detail.offset)
                        subscribe()
                        // Fire-and-forget: the transcript must never wait on GitHub
                        // state, so the chip bar appears a beat later — which is what
                        // the desktop does too.
                        scope.launch { loadOpenPrs() }
                        return
                    }

                    is ApiResult.Http ->
                        if (result.status == 404 && System.currentTimeMillis() < deadline) {
                            delay(400)
                        } else {
                            _messages.emit(
                                UserMessage(
                                    "Could not open that session: ${result.message()}",
                                    isError = true,
                                ),
                            )
                            return
                        }

                    // A dropped connection mid-creation is worth one more try.
                    is ApiResult.Offline ->
                        if (System.currentTimeMillis() < deadline) {
                            delay(400)
                        } else {
                            _messages.emit(
                                UserMessage(
                                    "Could not open that session: ${result.message()}",
                                    isError = true,
                                ),
                            )
                            return
                        }
                }
            }
        } finally {
            if (_opening.value == sessionId) _opening.value = null
        }
    }

    /** The "N earlier events — load all" button. */
    suspend fun loadWholeTranscript() {
        val open = _conversation.value ?: return
        when (val result = api.session(open.sessionId, tail = null)) {
            is ApiResult.Ok -> {
                if (_conversation.value?.sessionId != open.sessionId) return
                _conversation.value = Conversation(
                    sessionId = open.sessionId,
                    summary = result.value.summary,
                    offset = result.value.offset,
                    runner = result.value.runner ?: open.runner,
                    ask = open.ask,
                    suggestions = result.value.suggestions,
                    // Carried forward for the same reason `runner` is: this rebuilds
                    // the conversation from scratch, and `/api/sessions/:id` knows
                    // nothing about PR status — so without these two the chip bar
                    // would vanish the moment somebody pressed "load all".
                    prs = open.prs,
                    prsGh = open.prsGh,
                ).applyEvents(result.value.events, result.value.offset)
            }
            else -> _messages.emit(
                UserMessage("Could not load the rest: ${result.message()}", isError = true),
            )
        }
    }

    suspend fun closeSession() {
        _conversation.value = null
        subscribe()
    }

    // ----------------------------------------------------------------- writes

    fun pendingMode(sessionId: String): String? = pendingModes[sessionId]

    fun setPendingMode(sessionId: String, mode: String) {
        pendingModes[sessionId] = mode
    }

    fun pendingModel(sessionId: String): String? = pendingModels[sessionId]
    fun setPendingModel(sessionId: String, model: String) { pendingModels[sessionId] = model }
    fun pendingEffort(sessionId: String): String? = pendingEfforts[sessionId]
    fun setPendingEffort(sessionId: String, effort: String) { pendingEfforts[sessionId] = effort }

    /**
     * @param model `""` for inherit, which on a send is "as you were" — the bridge has
     *   no way to ask a live process to drop its model — so it is left out.
     * @param effort always sent: `""` is `inherit` and *is* honoured. See [SendRequest].
     * @param fromUuid Edit and branch — the turn's own id. Implies [fork].
     * @return the text back if it did not send, so the composer can restore it.
     */
    suspend fun send(
        sessionId: String,
        text: String,
        mode: String,
        model: String = "",
        effort: String? = null,
        fork: Boolean = false,
        fromUuid: String? = null,
    ): String? {
        // permissionMode is always sent, never conditionally — an absent one
        // normalises to `auto` server-side, so omitting it would quietly drop a
        // session out of acceptEdits on every message.
        val body = sendBody(text, mode, model, effort, fork, fromUuid)
        return when (val result = api.send(sessionId, body)) {
            is ApiResult.Ok -> {
                pendingModes.remove(sessionId)
                pendingModels.remove(sessionId)
                pendingEfforts.remove(sessionId)
                // The response carries a fresher runner status than the last event
                // did, so apply it rather than waiting for the stream to catch up.
                result.value.status?.let { status ->
                    currentIf(sessionId)?.let { open ->
                        _conversation.value = open.copy(runner = status)
                    }
                }
                null
            }
            else -> {
                _messages.emit(UserMessage("Did not send: ${sendRefusal(result)}", isError = true))
                text
            }
        }
    }

    /**
     * The send refusals worth their own sentence — mostly the branch ones, each of
     * which is a different thing to do next.
     */
    private fun sendRefusal(result: ApiResult<*>): String = when {
        result is ApiResult.Http && result.status == 409 ->
            "it is working — stop it first, or send without branching."
        result is ApiResult.Http && result.status == 403 -> "that mode cannot be set from here."
        else -> result.message()
    }

    private fun sendFailedText(event: StreamEvent.SendFailed): String = when (event.kind) {
        "branch-refused" ->
            "Claude Code would not branch from there. Send it without branching instead."
        "busy-elsewhere" -> "This session is running somewhere else. Branch off a copy instead."
        "exited" -> "The process ended without answering."
        else -> event.message ?: "That message did not send"
    }

    /**
     * @param hard what a stalled turn gets — see [BridgeApi.stop].
     *
     * Whatever was still queued comes back as `dropped`, and has to be put somewhere or
     * it is lost — including when `how` is null, which is the case where a session
     * looked stuck and Stop was the obvious thing to press.
     */
    suspend fun stop(sessionId: String, hard: Boolean = false) {
        when (val result = api.stop(sessionId, hard)) {
            is ApiResult.Ok -> {
                val how = result.value.how
                _messages.emit(
                    UserMessage(
                        when (how) {
                            "hard" -> "Stopped the process"
                            "soft" -> "Asked it to stop"
                            else -> "There was nothing running to stop"
                        },
                    ),
                )
                if (result.value.dropped.isNotEmpty()) {
                    _unsent.emit(Unsent(sessionId, result.value.dropped))
                }
            }
            else -> _messages.emit(
                UserMessage("Could not stop: ${result.message()}", isError = true),
            )
        }
    }

    // ------------------------------------------------------------------ queue

    /**
     * Drop one queued message. A `409` means it already went — a `handed` one the
     * running turn read first — and is said as what happened, not as a failure.
     */
    suspend fun dropQueued(sessionId: String, queueId: String) {
        val result = api.dropQueued(sessionId, queueId)
        if (result is ApiResult.Http && result.status == 409) {
            _messages.emit(UserMessage("Too late — that one has already gone to the turn"))
            return
        }
        applyQueueWrite(sessionId, result, "Could not drop that message")
    }

    suspend fun reorderQueue(sessionId: String, ids: List<String>) {
        applyQueueWrite(sessionId, api.reorderQueue(sessionId, ids), "Could not reorder the queue")
    }

    /** Every queue write answers with the whole runner, which is what the chips draw from. */
    private suspend fun applyQueueWrite(
        sessionId: String,
        result: ApiResult<QueueWriteResponse>,
        failure: String,
    ) {
        when (result) {
            is ApiResult.Ok -> result.value.status?.let { status ->
                currentIf(sessionId)?.let { _conversation.value = it.copy(runner = status) }
            }
            else -> _messages.emit(UserMessage("$failure: ${result.message()}", isError = true))
        }
    }

    // ------------------------------------------------------------------ flags

    /**
     * Pin, archive or rename. Applied to the list straight away and confirmed by a
     * re-read, because `sessions-changed` will say the same a moment later and a row
     * that sits unchanged for that moment reads as a press that did not land.
     */
    suspend fun setFlags(sessionId: String, flags: FlagsRequest): Boolean {
        val before = _sessions.value
        _sessions.value = before.map { s ->
            if (s.sessionId != sessionId) {
                s
            } else {
                s.copy(
                    pinned = flags.pinned ?: s.pinned,
                    archived = flags.archived ?: s.archived,
                    title = flags.title?.takeIf { it.isNotBlank() } ?: s.title,
                )
            }
        }
        return when (val result = api.setFlags(sessionId, flags)) {
            is ApiResult.Ok -> {
                val answer = result.value
                // `title` in the answer is what the session now *shows* — after a
                // clear, the transcript's title again — so it is the one to keep.
                _sessions.value = _sessions.value.map { s ->
                    if (s.sessionId != sessionId) {
                        s
                    } else {
                        s.copy(
                            pinned = answer.pinned,
                            archived = answer.archived,
                            title = answer.title ?: s.title,
                            titleSource = answer.titleSource,
                        )
                    }
                }
                currentIf(sessionId)?.let { open ->
                    _conversation.value = open.copy(
                        summary = open.summary.copy(
                            pinned = answer.pinned,
                            archived = answer.archived,
                            title = answer.title ?: open.summary.title,
                            titleSource = answer.titleSource,
                        ),
                    )
                }
                true
            }
            else -> {
                _sessions.value = before
                _messages.emit(
                    UserMessage("Could not update that session: ${result.message()}", isError = true),
                )
                false
            }
        }
    }

    /**
     * A hard delete — the transcript cannot be put back, which is why the UI asks
     * first. `409` while a turn is running, and that is worth its own sentence.
     */
    suspend fun deleteSession(sessionId: String): Boolean {
        val result = api.deleteSession(sessionId)
        return when {
            result is ApiResult.Ok -> {
                _sessions.value = _sessions.value.filterNot { it.sessionId == sessionId }
                true
            }
            result is ApiResult.Http && result.status == 409 -> {
                _messages.emit(UserMessage("It is working — stop it before deleting", isError = true))
                false
            }
            result is ApiResult.Http && result.status == 404 -> {
                _sessions.value = _sessions.value.filterNot { it.sessionId == sessionId }
                true
            }
            else -> {
                _messages.emit(UserMessage("Could not delete: ${result.message()}", isError = true))
                false
            }
        }
    }

    /**
     * @return true if the ask is settled — including on 409, which means somebody
     *   else answered first and is an ordinary outcome, not an error to shout about.
     */
    suspend fun answer(sessionId: String, body: PermissionRequestBody): Boolean {
        val result = api.permission(sessionId, body)
        return when {
            result is ApiResult.Ok -> true
            result is ApiResult.Http && result.status == 409 -> {
                _messages.emit(UserMessage("Already answered somewhere else"))
                true
            }
            result is ApiResult.Http && result.status == 404 -> {
                _messages.emit(
                    UserMessage("That session is no longer running", isError = true),
                )
                true
            }
            else -> {
                _messages.emit(
                    UserMessage("Could not answer: ${result.message()}", isError = true),
                )
                false
            }
        }
    }

    /** Convenience for the board's inline tool buttons. */
    suspend fun answerTool(ask: Ask, decision: String): Boolean {
        val sessionId = ask.sessionId ?: return false
        return answer(
            sessionId,
            PermissionRequestBody(requestId = ask.requestId, decision = decision),
        )
    }

    /**
     * @param model `""` or null for inherit; both leave the field out.
     * @param effort the same.
     */
    suspend fun createSession(
        cwd: String,
        prompt: String,
        mode: String,
        test: Boolean,
        model: String? = null,
        effort: String? = null,
    ): Result<String> {
        val body = CreateSessionRequest(
            cwd = cwd,
            prompt = prompt,
            permissionMode = mode,
            test = test,
            model = model?.takeIf { it.isNotBlank() },
            effort = effort?.takeIf { it.isNotBlank() },
        )
        return when (val result = api.createSession(body)) {
            is ApiResult.Ok -> Result.success(result.value.sessionId)
            else -> Result.failure(IllegalStateException(result.message()))
        }
    }

    // ------------------------------------------------------------------ tasks

    /**
     * Accepting an offer is **two calls, in this order**, mirroring `startSuggestion`
     * in `web/app.js:2004`.
     *
     * `plan` is not a default falling through from anywhere — it is the point. An
     * agent-written prompt has had no human read it as an instruction, so the session
     * it starts should say what it intends to do before it does any of it.
     *
     * Filing the decision is the second call and is allowed to fail on its own: the
     * session is already running by then, and losing the id to report a bookkeeping
     * failure would be the worse outcome. So a failure there is a message, not a
     * [Result.failure].
     */
    suspend fun startTask(task: SuggestedTask): Result<String> {
        val cwd = task.cwd?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("That task has no directory."))
        if (task.prompt.isBlank()) {
            return Result.failure(IllegalStateException("That task has no message to send."))
        }

        val created = api.createSession(
            CreateSessionRequest(
                cwd = cwd,
                prompt = task.prompt,
                permissionMode = "plan",
                // A test session only exists on a development bridge; inheriting the
                // flag from a session that cannot be here would hide the new one.
                test = _health.value?.dev == true && task.session?.test == true,
            ),
        )
        if (created !is ApiResult.Ok) {
            return Result.failure(IllegalStateException(startFailure(created)))
        }

        val newId = created.value.sessionId
        val origin = task.sessionId
        if (origin == null) {
            Tgx.w("started task ${task.id} with no origin session — decision not filed")
        } else {
            val filed = api.actOnSuggestion(
                origin,
                task.id,
                SuggestionActRequest(status = TaskStatus.STARTED, startedId = newId),
            )
            if (filed is ApiResult.Ok) {
                patchTask(task.id, TaskStatus.STARTED, newId)
            } else {
                _messages.emit(
                    UserMessage(
                        "Started it, but could not mark the task: ${filed.message()}",
                        isError = true,
                    ),
                )
            }
        }
        return Result.success(newId)
    }

    suspend fun dismissTask(task: SuggestedTask): Boolean =
        act(task, SuggestionActRequest(status = TaskStatus.DISMISSED), TaskStatus.DISMISSED)

    /** An empty body is the undo — the offer goes back to being open. */
    suspend fun undoTask(task: SuggestedTask): Boolean =
        act(task, SuggestionActRequest(), TaskStatus.OPEN)

    /**
     * Written optimistically and rolled back on failure, as the desktop does
     * (`actOnSuggestion`, `web/app.js:2056`). A dismiss that leaves the card sitting
     * there for a round-trip reads as a button that did not work.
     */
    private suspend fun act(
        task: SuggestedTask,
        body: SuggestionActRequest,
        optimistic: String,
    ): Boolean {
        val sessionId = task.sessionId ?: run {
            _messages.emit(
                UserMessage("That task has lost track of its session", isError = true),
            )
            return false
        }
        val before = _tasks.value
        val beforeOpen = _openTasks.value
        patchTask(task.id, optimistic, null)

        val result = api.actOnSuggestion(sessionId, task.id, body)
        if (result is ApiResult.Ok) {
            // The filter decides whether a task that just changed status still belongs
            // on screen, and only a re-read knows that.
            loadTasks()
            return true
        }
        _tasks.value = before
        _openTasks.value = beforeOpen
        _messages.emit(
            UserMessage("Could not update that task: ${result.message()}", isError = true),
        )
        return false
    }

    /**
     * Move one row, and the badge with it.
     *
     * The badge delta is taken from the row's **actual** previous status where we have
     * it, not from the direction of travel. Undoing something already open, or
     * dismissing something already dismissed, is a no-op the UI can still ask for — a
     * stale decision map on an inline card is enough — and assuming a ±1 there makes the
     * badge disagree with the list until the next read.
     *
     * A task not in [_tasks] at all is one the current filter is not showing; its old
     * status is genuinely unknown, so the direction of travel is the best guess
     * available, and [loadTasks] settles it either way.
     */
    private fun patchTask(id: String, status: String, startedId: String?) {
        val before = _tasks.value.firstOrNull { it.id == id }?.status
        _tasks.value = _tasks.value.map {
            if (it.id == id) it.copy(status = status, startedId = startedId ?: it.startedId) else it
        }
        _openTasks.value = (_openTasks.value + openDelta(before, status)).coerceAtLeast(0)
    }

    // ----------------------------------------------------------------- drafts

    suspend fun saveDraft(body: DraftBody): Result<Draft> = writeDraft { api.createDraft(body) }

    suspend fun updateDraft(id: String, body: DraftBody): Result<Draft> =
        writeDraft { api.patchDraft(id, body) }

    private suspend fun writeDraft(
        call: suspend () -> ApiResult<DraftResponse>,
    ): Result<Draft> =
        when (val result = call()) {
            is ApiResult.Ok -> {
                // `drafts-changed` will bring the same row a moment later; applying it
                // now is what keeps the list from lagging a round-trip behind the press.
                val draft = result.value.draft
                _drafts.value = listOf(draft) + _drafts.value.filterNot { it.id == draft.id }
                Result.success(draft)
            }
            else -> Result.failure(IllegalStateException(draftFailure(result)))
        }

    suspend fun deleteDraft(id: String): Boolean {
        val before = _drafts.value
        _drafts.value = before.filterNot { it.id == id }
        val result = api.deleteDraft(id)
        // 404 means it is already gone, which is the outcome asked for.
        if (result is ApiResult.Ok || (result is ApiResult.Http && result.status == 404)) return true
        _drafts.value = before
        _messages.emit(
            UserMessage("Could not delete that draft: ${result.message()}", isError = true),
        )
        return false
    }

    /**
     * The draft is deleted **only after the process starts**, so a failure here leaves
     * it exactly where it was. Nothing is removed locally either — `drafts-changed`
     * removes it on success, and on failure there is nothing to put back.
     */
    suspend fun startDraft(id: String): Result<String> =
        when (val result = api.startDraft(id)) {
            is ApiResult.Ok -> Result.success(result.value.sessionId)
            else -> Result.failure(IllegalStateException(startFailure(result)))
        }

    /**
     * The refusals worth their own sentence.
     *
     * A generic "could not save" is true and useless: every one of these is a different
     * thing to do next, and three of them are not the user's fault.
     */
    private fun draftFailure(result: ApiResult<*>): String = when {
        result !is ApiResult.Http -> result.message()
        result.status == 400 ->
            "That directory is gone, or the message is empty — ${result.message()}"
        result.status == 403 -> "That permission mode cannot be set from here."
        result.status == 409 -> "That is 200 drafts. Delete one to make room."
        result.status == 404 -> "That draft is no longer there."
        else -> result.message()
    }

    private fun startFailure(result: ApiResult<*>): String = when {
        result !is ApiResult.Http -> result.message()
        // The draft survives a 400 — worth saying, because the obvious fear is that
        // the message you wrote went with it.
        result.status == 400 ->
            "Nothing was started, and it is still saved — ${result.message()}"
        result.status == 403 -> "That permission mode cannot be started from here."
        result.status == 404 -> "That is already started, or gone."
        result.status == 429 -> "Too many sessions at once. Try again in a moment."
        else -> result.message()
    }

    @Suppress("unused")
    private val decisions = Decision
}
