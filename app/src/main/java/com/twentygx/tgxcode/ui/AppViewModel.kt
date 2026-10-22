package com.twentygx.tgxcode.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.FlagsRequest
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.data.net.PairingInput
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.data.settings.SettingsStore
import com.twentygx.tgxcode.di.AppContainer
import com.twentygx.tgxcode.service.BridgeService
import com.twentygx.tgxcode.ui.sessions.PrSheetState
import com.twentygx.tgxcode.ui.settings.ProbeResult
import com.twentygx.tgxcode.ui.settings.SettingsUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One ViewModel for the whole app.
 *
 * The screens are small and share nearly all their state, so splitting this per
 * screen would mean five objects all reaching into the same repository. The
 * repository owns everything durable; this owns only what dies with the UI —
 * drafts, which tool rows are open, which buttons are mid-flight.
 */
class AppViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val repo: BridgeRepository = container.repository
    val settings: SettingsStore = container.settings

    /**
     * Deliberately outside the event list. This is the fix for the web client's one
     * real rough edge: `patchTool` replaces the DOM node, so a tool collapses the
     * moment its result lands. Keeping expansion here means a result fills in an
     * open tool instead of closing it.
     */
    val expandedTools = mutableStateSetOf<String>()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _answering = MutableStateFlow<Set<String>>(emptySet())
    val answering: StateFlow<Set<String>> = _answering.asStateFlow()

    /** Task ids with a decision in flight, so their buttons can go quiet. */
    private val _acting = MutableStateFlow<Set<String>>(emptySet())
    val acting: StateFlow<Set<String>> = _acting.asStateFlow()

    /** One shared 30s tick drives every "waiting N min" line in the app. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _settingsUi = MutableStateFlow(SettingsUiState())
    val settingsUi: StateFlow<SettingsUiState> = _settingsUi.asStateFlow()

    private val _newSession = MutableStateFlow(NewSessionUiState())
    val newSession: StateFlow<NewSessionUiState> = _newSession.asStateFlow()

    /**
     * The long-press PR sheet, or null for closed.
     *
     * Visibility is *derived* from this being non-null rather than held beside it, so
     * there is one source of truth and no way to show an empty sheet.
     *
     * Here rather than in the screen because the detail is a network read that has to
     * outlive the sheet's own recomposition and a rotation, and because this is where
     * every other in-flight action in the app is brokered.
     */
    private val _prSheet = MutableStateFlow<PrSheetState?>(null)
    val prSheet: StateFlow<PrSheetState?> = _prSheet.asStateFlow()

    /**
     * The long-press options sheet on a Sessions row, or null for closed. Held here
     * rather than in the screen for the same reason as [prSheet]: it outlives a
     * rotation, and the writes it starts are brokered here.
     */
    private val _sessionSheet = MutableStateFlow<SessionSummary?>(null)
    val sessionSheet: StateFlow<SessionSummary?> = _sessionSheet.asStateFlow()

    /** The earlier turn the next send branches from, if Edit and branch is armed. */
    private val _branch = MutableStateFlow<BranchFrom?>(null)
    val branch: StateFlow<BranchFrom?> = _branch.asStateFlow()

    /**
     * Bumped whenever a mode, model or effort pick changes.
     *
     * The picks themselves live in plain maps on the repository, which Compose cannot
     * see — so [modeFor] and friends read this, and a pick recomposes whatever drew
     * the label rather than waiting for the next unrelated state change to do it.
     */
    private val picks = mutableIntStateOf(0)

    private var lease: AutoCloseable? = null

    init {
        // Text the bridge handed back — a failed send, or what a stop took off the
        // queue — goes back in the box it came from, after anything already typed.
        viewModelScope.launch {
            repo.unsent.collect { back ->
                if (repo.conversation.value?.sessionId != back.sessionId) return@collect
                val returned = back.texts.joinToString("\n\n")
                _draft.value = listOf(_draft.value.trim(), returned)
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
                // A refused cut is refused again on retry; the way forward is an
                // ordinary send, so the branch stands down.
                if (back.kind == "branch-refused") _branch.value = null
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _now.value = System.currentTimeMillis()
                delay(30_000)
            }
        }
        // Re-evaluate whenever the stored config appears, changes or is cleared.
        viewModelScope.launch {
            container.config.collect {
                syncLease()
                if (it != null && uiStarted) repo.probePairing()
            }
        }
        viewModelScope.launch {
            container.settings.stayConnected.collect { on ->
                _settingsUi.value = _settingsUi.value.copy(stayConnected = on)
            }
        }
        // Show what is actually stored. Leaving the token blank made the form look
        // broken — Save disabled and an empty field — on a device that was in fact
        // paired and connected.
        viewModelScope.launch {
            container.config.collect { config ->
                if (config == null) return@collect
                val ui = _settingsUi.value
                if (ui.origin.isBlank() || ui.token.isBlank()) {
                    _settingsUi.value = ui.copy(
                        origin = ui.origin.ifBlank { config.origin },
                        token = ui.token.ifBlank { config.token },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- connection

    private var uiStarted = false

    /**
     * The connection wants two things: the UI to be up, and a config to exist.
     *
     * Checking `config.value` once inside [onStart] is not enough — it is a
     * `stateIn` flow fed asynchronously from DataStore, so on a cold start with a
     * bridge already saved it is still null when the first composition runs, and the
     * stream would simply never open. Which matters more here than it sounds: a
     * client that is not connected is a client whose asks get auto-denied.
     */
    fun onStart() {
        uiStarted = true
        syncLease()
    }

    fun onStop() {
        uiStarted = false
        syncLease()
    }

    private fun syncLease() {
        val want = uiStarted && container.config.value != null
        if (want && lease == null) {
            lease = repo.acquire()
        } else if (!want && lease != null) {
            lease?.close()
            lease = null
        }
    }

    fun probe() = viewModelScope.launch { repo.probePairing() }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        repo.refreshAll()
        _refreshing.value = false
    }

    // ------------------------------------------------------------ conversation

    /**
     * A session we started ourselves and have not opened yet. Lets [open] know it
     * should wait through the gap between "created" and "readable".
     */
    private var justCreated: String? = null

    fun open(sessionId: String) = viewModelScope.launch {
        expandedTools.clear()
        _draft.value = ""
        // A branch was about the session it was armed on, and would be a surprise
        // waiting on the next one opened.
        if (_branch.value?.sessionId != sessionId) _branch.value = null
        val fresh = justCreated == sessionId
        if (fresh) justCreated = null
        repo.openSession(sessionId, awaitCreation = fresh)
    }

    fun closeConversation() = viewModelScope.launch { repo.closeSession() }

    fun toggleTool(id: String) {
        if (!expandedTools.add(id)) expandedTools.remove(id)
    }

    fun setDraft(text: String) { _draft.value = text }

    fun loadAll() = viewModelScope.launch { repo.loadWholeTranscript() }

    fun setMode(sessionId: String, mode: String) {
        repo.setPendingMode(sessionId, mode)
        picks.intValue++
    }

    fun modeFor(sessionId: String): String {
        picks.intValue
        return repo.pendingMode(sessionId)
            ?: repo.conversation.value?.runner?.permissionMode
            ?: repo.conversation.value?.summary?.permissionMode
            ?: "auto"
    }

    fun setModel(sessionId: String, model: String) {
        repo.setPendingModel(sessionId, model)
        picks.intValue++
    }

    fun setEffort(sessionId: String, effort: String) {
        repo.setPendingEffort(sessionId, effort)
        picks.intValue++
    }

    /**
     * The model picker's value — the desktop's `paintModel` ladder, top rung last.
     *
     * A change the bridge is holding ([RunnerStatus.pendingSettings]) outranks what the
     * process runs now, because it is what the *next* turn after the background work
     * will run, and showing the old value would invite picking the new one twice.
     */
    fun modelFor(sessionId: String): String {
        picks.intValue
        repo.pendingModel(sessionId)?.let { return it }
        val open = repo.conversation.value?.takeIf { it.sessionId == sessionId }
        val runner = open?.runner
        runner?.pendingSettings?.let { return SessionChoices.knownModel(it.model) }
        if (runner != null) return SessionChoices.knownModel(runner.model)
        return SessionChoices.knownModel(open?.summary?.model)
    }

    /**
     * The effort picker's value: [modelFor] less its last rung. The transcript never
     * records effort, so with no live process and no pick this is `inherit` — which is
     * also what a send from it then asks for, so what this shows is what runs.
     */
    fun effortFor(sessionId: String): String {
        picks.intValue
        repo.pendingEffort(sessionId)?.let { return it }
        val runner = repo.conversation.value?.takeIf { it.sessionId == sessionId }?.runner
        runner?.pendingSettings?.let { return SessionChoices.knownEffort(it.effort) }
        return SessionChoices.knownEffort(runner?.effort)
    }

    /**
     * @param onNewSession called when the send started a session of its own — Edit and
     *   branch from the first turn, which has nothing before it to keep, so it becomes a
     *   new session in the same folder with the edited text, as on the desktop.
     */
    fun send(sessionId: String, mode: String, onNewSession: (String) -> Unit = {}) =
        viewModelScope.launch {
            val text = _draft.value.trim()
            if (text.isEmpty()) return@launch
            val branch = _branch.value?.takeIf { it.sessionId == sessionId }
            _sending.value = true
            // Cleared optimistically: the transcript is the record, and holding text in
            // the box after it is already sent invites sending it twice.
            _draft.value = ""

            if (branch?.first == true) {
                val open = repo.conversation.value?.takeIf { it.sessionId == sessionId }
                val cwd = open?.summary?.cwd
                if (cwd == null) {
                    _draft.value = text
                    repo.report("That session has no folder to start in", isError = true)
                } else {
                    repo.createSession(
                        cwd = cwd,
                        prompt = text,
                        mode = mode,
                        test = open.summary.test,
                        model = modelFor(sessionId),
                        effort = effortFor(sessionId),
                    ).onSuccess { id ->
                        _branch.value = null
                        justCreated = id
                        onNewSession(id)
                    }.onFailure { error ->
                        _draft.value = text
                        repo.report("Could not start it: ${error.message}", isError = true)
                    }
                }
                _sending.value = false
                return@launch
            }

            val restored = repo.send(
                sessionId = sessionId,
                text = text,
                mode = mode,
                model = modelFor(sessionId),
                effort = effortFor(sessionId),
                fromUuid = branch?.uuid,
            )
            if (restored != null) {
                _draft.value = restored
            } else {
                // Sent: the copy is being made, and `session-forked` takes us there.
                if (branch != null) _branch.value = null
                picks.intValue++
            }
            _sending.value = false
        }

    /**
     * A stalled turn gets a hard stop, as on the desktop: a process that has gone
     * silent is the one least likely to answer an interrupt.
     */
    fun stop(sessionId: String) = viewModelScope.launch {
        val stalled = repo.conversation.value
            ?.takeIf { it.sessionId == sessionId }?.runner?.stalled == true
        repo.stop(sessionId, hard = stalled)
    }

    // ------------------------------------------------------------ edit & branch

    /** Put one of your earlier turns back in the box, to send again as the next turn. */
    fun editAndResend(event: UserEvent) {
        _branch.value = null
        _draft.value = event.displayText()
    }

    /**
     * Put one of your earlier turns back in the box, and arm the next send to go to a
     * *copy* of this session cut back to just before it. The original is never
     * rewritten.
     *
     * Where the turn sits is counted from what is loaded, so it is only claimed when
     * the whole transcript is in hand; otherwise the banner says "an earlier turn".
     */
    fun editAndBranch(sessionId: String, event: UserEvent) {
        val open = repo.conversation.value?.takeIf { it.sessionId == sessionId } ?: return
        val turns = open.events.filterIsInstance<UserEvent>().filter { it.fromHuman }
        val whole = (open.truncated?.dropped ?: 0) == 0
        val index = turns.indexOfFirst { it.id == event.id }
        val turn = if (whole && index >= 0) index + 1 else null
        _branch.value = BranchFrom(
            sessionId = sessionId,
            uuid = event.id,
            turn = turn,
            total = if (whole) turns.size else null,
            // `parent` is null on the first turn — and on every turn from a bridge
            // that predates branching, so only the position can settle it then.
            first = event.parent == null && turn == 1,
        )
        _draft.value = event.displayText()
    }

    fun cancelBranch() {
        _branch.value = null
    }

    // ----------------------------------------------------------------- queue

    fun dropQueued(sessionId: String, queueId: String) =
        viewModelScope.launch { repo.dropQueued(sessionId, queueId) }

    /**
     * Move one queued message up or down a place. A `handed` message keeps its place
     * at the front whatever is sent, so it never takes part.
     */
    fun moveQueued(sessionId: String, queueId: String, by: Int) = viewModelScope.launch {
        val queue = repo.conversation.value
            ?.takeIf { it.sessionId == sessionId }?.runner?.queue.orEmpty()
        val ids = queue.filterNot { it.handed }.mapNotNull { it.id }.toMutableList()
        val from = ids.indexOf(queueId)
        val to = from + by
        if (from < 0 || to !in ids.indices) return@launch
        ids.add(to, ids.removeAt(from))
        repo.reorderQueue(sessionId, ids)
    }

    fun answer(sessionId: String, body: PermissionRequestBody) = viewModelScope.launch {
        _answering.value = _answering.value + body.requestId
        repo.answer(sessionId, body)
        _answering.value = _answering.value - body.requestId
    }

    fun answerFromBoard(ask: Ask, decision: String) = viewModelScope.launch {
        _answering.value = _answering.value + ask.requestId
        repo.answerTool(ask, decision)
        _answering.value = _answering.value - ask.requestId
    }

    // ---------------------------------------------------------------- settings

    fun onPaste(text: String) {
        val parsed = PairingInput.parse(text)
        _settingsUi.value = _settingsUi.value.copy(
            paste = text,
            origin = parsed?.origin ?: _settingsUi.value.origin,
            token = parsed?.token ?: _settingsUi.value.token,
            note = parsed?.note,
        )
    }

    fun onOrigin(text: String) { _settingsUi.value = _settingsUi.value.copy(origin = text) }
    fun onToken(text: String) { _settingsUi.value = _settingsUi.value.copy(token = text) }

    /**
     * Persist the choice *and* act on it. Kept separate from the switch's own
     * callback so the Activity can gate it behind the notification permission — a
     * toggle that flips on and then quietly does nothing is worse than one that asks.
     */
    fun applyStayConnected(on: Boolean) = viewModelScope.launch {
        container.settings.setStayConnected(on)
        if (on) BridgeService.start(container.appContext) else BridgeService.stop(container.appContext)
    }

    /**
     * Two requests, reported separately, because they fail for different reasons and
     * the difference is the whole point: health proves the bridge is reachable,
     * `/api/sessions` proves the token is good.
     */
    fun test() = viewModelScope.launch {
        val ui = _settingsUi.value
        val config = PairingInput.build(ui.origin, ui.token)
        if (config == null) {
            _settingsUi.value = ui.copy(probe = ProbeResult("That is not a usable address.", false))
            return@launch
        }
        _settingsUi.value = ui.copy(testing = true, probe = null)
        // Saving first is what makes the probe use this config — the API layer reads
        // the stored one, so an unsaved test would check the previous bridge.
        container.settings.save(config)
        // ...and then *wait for the write to surface*. DataStore writes propagate
        // asynchronously through the config StateFlow that the interceptors read, so
        // probing immediately asks the old config — which on a first pairing is no
        // config at all, and reports "could not reach the bridge" about a bridge
        // that is perfectly reachable.
        if (!awaitConfig(config.origin)) {
            _settingsUi.value = _settingsUi.value.copy(
                testing = false,
                probe = ProbeResult("Could not store that address.", false),
            )
            return@launch
        }
        // Reconnect against the new origin: a stream still attached to the previous
        // bridge is not evidence about this one — and neither is what we learned about
        // whether the *last* transport could carry a stream at all.
        lease?.close()
        lease = null
        repo.onOriginChanged()
        val state = repo.probePairing()
        syncLease()
        val health = repo.health.value
        val text = when (state) {
            ConnState.NotPaired -> "Reached the bridge, but it refused the token."
            else -> if (health != null) {
                "Connected to ${health.host ?: "the bridge"} " +
                    "(v${health.version ?: "?"}, ${health.sessions} sessions)" +
                    if (health.remote) " — it sees you as remote." else ""
            } else {
                "Could not reach the bridge."
            }
        }
        _settingsUi.value = _settingsUi.value.copy(
            testing = false,
            probe = ProbeResult(text, state != ConnState.NotPaired && health != null),
        )
    }

    fun save() = viewModelScope.launch {
        val ui = _settingsUi.value
        PairingInput.build(ui.origin, ui.token)?.let {
            container.settings.save(it)
            awaitConfig(it.origin)
            repo.onOriginChanged()
            if (lease == null) lease = repo.acquire()
            repo.probePairing()
        }
    }

    /**
     * Waits for a DataStore write to reach the in-memory config the network layer
     * reads. Returns false if it never arrives, rather than hanging the UI.
     */
    private suspend fun awaitConfig(origin: String): Boolean =
        withTimeoutOrNull(3_000) {
            container.config.first { it?.origin == origin }
        } != null

    fun forget() = viewModelScope.launch {
        onStop()
        container.settings.forget()
    }

    // ------------------------------------------------------------ new session

    fun setNewCwd(cwd: String) { _newSession.value = _newSession.value.copy(cwd = cwd) }
    fun setNewMode(mode: String) { _newSession.value = _newSession.value.copy(mode = mode) }
    fun setNewModel(model: String) { _newSession.value = _newSession.value.copy(model = model) }
    fun setNewEffort(effort: String) { _newSession.value = _newSession.value.copy(effort = effort) }
    fun setNewPrompt(text: String) { _newSession.value = _newSession.value.copy(prompt = text) }
    fun setNewTest(on: Boolean) { _newSession.value = _newSession.value.copy(test = on) }

    fun startSession(onStarted: (String) -> Unit) = viewModelScope.launch {
        val state = _newSession.value
        val invalid = state.validate()
        if (invalid != null) {
            _newSession.value = state.copy(error = invalid)
            return@launch
        }
        _newSession.value = state.copy(starting = true, error = null)
        repo.createSession(
            cwd = state.cwd.orEmpty(),
            prompt = state.prompt.trim(),
            mode = state.mode,
            test = state.test,
            model = state.model,
            effort = state.effort,
        )
            .onSuccess { id ->
                _newSession.value = _newSession.value.copy(
                    starting = false,
                    prompt = "",
                    editingDraftId = null,
                )
                justCreated = id
                onStarted(id)
            }
            .onFailure { error ->
                _newSession.value =
                    _newSession.value.copy(starting = false, error = error.message)
            }
    }

    // ---------------------------------------------------------------- tasks

    fun setTaskFilter(status: String) = repo.setTaskFilter(status)

    fun startTask(task: SuggestedTask, onStarted: (String) -> Unit) = viewModelScope.launch {
        acting(task.id) {
            repo.startTask(task)
                .onSuccess { id ->
                    // Without this, `open` will not tolerate the few seconds in which a
                    // freshly created session still 404s.
                    justCreated = id
                    onStarted(id)
                }
                .onFailure { error ->
                    repo.report(error.message ?: "Could not start that task")
                }
        }
    }

    fun dismissTask(task: SuggestedTask) = viewModelScope.launch {
        acting(task.id) { repo.dismissTask(task) }
    }

    fun undoTask(task: SuggestedTask) = viewModelScope.launch {
        acting(task.id) { repo.undoTask(task) }
    }

    private suspend inline fun acting(id: String, block: () -> Unit) {
        _acting.value = _acting.value + id
        try {
            block()
        } finally {
            _acting.value = _acting.value - id
        }
    }

    // ------------------------------------------------------------ session flags

    fun openSessionSheet(session: SessionSummary) {
        _sessionSheet.value = session
    }

    fun closeSessionSheet() {
        _sessionSheet.value = null
    }

    fun setPinned(session: SessionSummary, pinned: Boolean) = viewModelScope.launch {
        repo.setFlags(session.sessionId, FlagsRequest(pinned = pinned))
    }

    fun setArchived(session: SessionSummary, archived: Boolean) = viewModelScope.launch {
        repo.setFlags(session.sessionId, FlagsRequest(archived = archived))
    }

    /** A blank name clears the one given here, and the transcript's title shows again. */
    fun rename(session: SessionSummary, title: String) = viewModelScope.launch {
        repo.setFlags(session.sessionId, FlagsRequest(title = title.trim()))
    }

    fun deleteSession(session: SessionSummary) = viewModelScope.launch {
        if (repo.deleteSession(session.sessionId)) repo.report("Deleted “${session.displayTitle}”")
    }

    // ------------------------------------------------------------------- prs

    /**
     * Open the PR sheet for one session, seeded from what is already known.
     *
     * [PrSheetState.refs] and [PrSheetState.aggregate] both come from memory — the
     * summary and the row's own glyph — so the sheet opens with the numbers, the links
     * and the headline already drawn and then fills in titles when the detail lands.
     * Waiting on the network first would make a long press feel broken.
     *
     * The aggregate is *carried*, never recomputed from the detail: which of several
     * PRs speaks for the session is the bridge's ranking, and a second copy of it here
     * is exactly how the two would drift.
     */
    fun openPrSheet(session: SessionSummary) {
        val held = repo.prs.value
        _prSheet.value = PrSheetState(
            sessionId = session.sessionId,
            title = session.displayTitle,
            refs = session.prs,
            aggregate = held?.sessions?.get(session.sessionId),
            gh = held?.gh,
        )
        viewModelScope.launch {
            val fresh = repo.sessionPrs(session.sessionId)
            // A late answer for a sheet already closed, or reopened on another
            // session, is dropped rather than drawn into the wrong one.
            val still = _prSheet.value ?: return@launch
            if (still.sessionId != session.sessionId) return@launch
            _prSheet.value = still.copy(
                loading = false,
                prs = fresh?.prs,
                gh = fresh?.gh ?: still.gh,
            )
        }
    }

    fun closePrSheet() {
        _prSheet.value = null
    }

    /**
     * Hand a PR's URL to whatever opens links.
     *
     * The app opens no other external URL, so this is the one place it can fail for
     * want of a browser — hence the fallback onto the snackbar rather than an
     * uncaught `ActivityNotFoundException`.
     */
    fun reportUnopenableUrl(url: String) = viewModelScope.launch {
        repo.report("No app here can open $url", isError = true)
    }

    // --------------------------------------------------------------- drafts

    /**
     * Load a saved draft back into the form above it.
     *
     * `title` is deliberately not touched — this screen has no control for it, and
     * [DraftBody] does not send one, so whatever was set at the desk survives an edit
     * made here. A model or effort the pickers do not offer shows as `inherit`, and
     * saving would then clear it — the same trade the desktop's selects make.
     */
    fun editDraft(draft: Draft) {
        _newSession.value = NewSessionUiState(
            cwd = draft.cwd,
            mode = draft.permissionMode,
            model = SessionChoices.knownModel(draft.model),
            effort = SessionChoices.knownEffort(draft.effort),
            prompt = draft.prompt,
            test = draft.test,
            editingDraftId = draft.id,
        )
    }

    fun clearDraftEdit() {
        val state = _newSession.value
        _newSession.value = state.blankKeepingSetup()
    }

    fun saveDraft() = viewModelScope.launch {
        val state = _newSession.value
        val invalid = state.validate()
        if (invalid != null) {
            _newSession.value = state.copy(error = invalid)
            return@launch
        }
        _newSession.value = state.copy(savingDraft = true, error = null)
        val body = state.toDraftBody()
        val editing = state.editingDraftId
        val result = if (editing == null) repo.saveDraft(body) else repo.updateDraft(editing, body)
        result
            .onSuccess { draft ->
                // Stay on the draft after saving it: the next thing you do to something
                // you just wrote down is usually to start it.
                _newSession.value = _newSession.value.copy(
                    savingDraft = false,
                    editingDraftId = draft.id,
                    // The bridge expands `~`, so take its answer rather than the input.
                    cwd = draft.cwd,
                    error = null,
                )
                repo.report(if (editing == null) "Saved as a draft" else "Draft updated")
            }
            .onFailure { error ->
                _newSession.value = _newSession.value.copy(
                    savingDraft = false,
                    error = error.message,
                )
            }
    }

    fun deleteDraft(draft: Draft) = viewModelScope.launch {
        if (repo.deleteDraft(draft.id) && _newSession.value.editingDraftId == draft.id) {
            clearDraftEdit()
        }
    }

    /**
     * Start a draft, saving first if the form has moved on since it was written.
     *
     * `POST /api/drafts/:id/start` runs the **stored** draft, so starting a dirty form
     * without saving would quietly run the previous message.
     */
    fun startDraft(draft: Draft, onStarted: (String) -> Unit) = viewModelScope.launch {
        val state = _newSession.value
        val dirty = state.editingDraftId == draft.id && state.differsFrom(draft)
        if (dirty) {
            val invalid = state.validate()
            if (invalid != null) {
                _newSession.value = state.copy(error = invalid)
                return@launch
            }
            _newSession.value = state.copy(starting = true, error = null)
            val saved = repo.updateDraft(draft.id, state.toDraftBody())
            if (saved.isFailure) {
                _newSession.value = _newSession.value.copy(
                    starting = false,
                    error = saved.exceptionOrNull()?.message,
                )
                return@launch
            }
        } else {
            _newSession.value = state.copy(starting = true, error = null)
        }

        repo.startDraft(draft.id)
            .onSuccess { id ->
                _newSession.value = state.blankKeepingSetup()
                justCreated = id
                onStarted(id)
            }
            .onFailure { error ->
                _newSession.value =
                    _newSession.value.copy(starting = false, error = error.message)
            }
    }

    override fun onCleared() {
        onStop()
        super.onCleared()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(container) as T
    }
}

data class NewSessionUiState(
    val cwd: String? = null,
    /**
     * `plan` by default, and not sticky. The first message of a session is the one
     * written with the least idea of what it will touch.
     */
    val mode: String = "plan",
    /** `""` is `inherit`, in both. */
    val model: String = "",
    val effort: String = "",
    val prompt: String = "",
    val test: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    /** Set while the form is showing a saved draft rather than a blank session. */
    val editingDraftId: String? = null,
    val savingDraft: Boolean = false,
) {
    val busy: Boolean get() = starting || savingDraft

    /** The one set of rules, so Start and Save cannot come to different verdicts. */
    fun validate(): String? = when {
        cwd.isNullOrBlank() -> "Pick a directory first."
        prompt.isBlank() -> "A session needs a first message."
        else -> null
    }

    fun toDraftBody(): DraftBody = DraftBody(
        cwd = cwd.orEmpty(),
        prompt = prompt.trim(),
        // Always sent: an absent mode does not mean "decide later", it means `auto`.
        permissionMode = mode,
        test = test,
        model = model,
        effort = effort,
    )

    /** A fresh form that keeps where and how — the folder and the three pickers. */
    fun blankKeepingSetup(): NewSessionUiState =
        NewSessionUiState(cwd = cwd, mode = mode, model = model, effort = effort)

    /**
     * Whether starting this would run something other than what is on screen. `cwd` is
     * compared as stored, since the bridge expands `~` before writing it down.
     */
    fun differsFrom(draft: Draft): Boolean =
        cwd != draft.cwd ||
            prompt.trim() != draft.prompt ||
            mode != draft.permissionMode ||
            model != SessionChoices.knownModel(draft.model) ||
            effort != SessionChoices.knownEffort(draft.effort) ||
            test != draft.test
}

/**
 * An armed Edit and branch.
 *
 * @param uuid the turn's own `user` event id — what `fromUuid` takes. Never its parent.
 * @param turn 1-based position among your turns, when the whole transcript is loaded.
 * @param first nothing before it to keep: the send becomes a new session instead.
 */
data class BranchFrom(
    val sessionId: String,
    val uuid: String,
    val turn: Int? = null,
    val total: Int? = null,
    val first: Boolean = false,
) {
    val banner: String
        get() = when {
            first -> "Branching from the first turn — this starts a new session in the same folder."
            turn == null || total == null ->
                "Branching from an earlier turn — the copy will not include it or anything after it."
            turn == total -> "Branching from turn $turn of $total — the copy will not include turn $turn."
            else -> "Branching from turn $turn of $total — the copy will not include turns $turn–$total."
        }
}
