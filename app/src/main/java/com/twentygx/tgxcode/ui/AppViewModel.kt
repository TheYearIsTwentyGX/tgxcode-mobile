package com.twentygx.tgxcode.ui

import androidx.compose.runtime.mutableStateSetOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.PairingInput
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.data.repo.isRefused
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

    private var lease: AutoCloseable? = null

    init {
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
                        accessId = ui.accessId.ifBlank { config.accessId },
                        accessSecret = ui.accessSecret.ifBlank { config.accessSecret },
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

    fun setMode(sessionId: String, mode: String) = repo.setPendingMode(sessionId, mode)

    fun modeFor(sessionId: String): String =
        repo.pendingMode(sessionId)
            ?: repo.conversation.value?.runner?.permissionMode
            ?: repo.conversation.value?.summary?.permissionMode
            ?: "auto"

    fun send(sessionId: String, mode: String) = viewModelScope.launch {
        val text = _draft.value.trim()
        if (text.isEmpty()) return@launch
        _sending.value = true
        // Cleared optimistically: the transcript is the record, and holding text in
        // the box after it is already sent invites sending it twice.
        _draft.value = ""
        val restored = repo.send(sessionId, text, mode)
        if (restored != null) _draft.value = restored
        _sending.value = false
    }

    fun stop(sessionId: String) = viewModelScope.launch { repo.stop(sessionId) }

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
    fun onAccessId(text: String) { _settingsUi.value = _settingsUi.value.copy(accessId = text) }
    fun onAccessSecret(text: String) { _settingsUi.value = _settingsUi.value.copy(accessSecret = text) }

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
        val config = ui.toConfig()
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
        if (!awaitConfig(config)) {
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
        // The service may still hold a lease on a stream loop that a refusal ended,
        // so dropping ours is not enough to reopen it.
        repo.reconnect()
        val health = repo.health.value
        val text = when (state) {
            ConnState.NotPaired -> "Reached the bridge, but it refused the token."
            // /api/health sits behind Access too, so health is null here and the
            // generic "could not reach" would be the wrong diagnosis.
            ConnState.AccessDenied ->
                "Cloudflare Access refused the request. Check the Access client ID " +
                    "and secret below, and that the service token is in the " +
                    "application's Service Auth policy."
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
            probe = ProbeResult(text, !state.isRefused && health != null),
        )
    }

    fun save() = viewModelScope.launch {
        val ui = _settingsUi.value
        ui.toConfig()?.let {
            container.settings.save(it)
            awaitConfig(it)
            repo.onOriginChanged()
            if (lease == null) lease = repo.acquire()
            repo.probePairing()
            repo.reconnect()
        }
    }

    /**
     * Waits for a DataStore write to reach the in-memory config the network layer
     * reads. Returns false if it never arrives, rather than hanging the UI.
     */
    private suspend fun awaitConfig(saved: BridgeConfig): Boolean =
        withTimeoutOrNull(3_000) {
            // Every field, not just the origin: changing only the service token keeps
            // the origin, and probing before the new secret surfaces would test the old.
            container.config.first {
                it?.origin == saved.origin && it.token == saved.token &&
                    it.accessId == saved.accessId && it.accessSecret == saved.accessSecret
            }
        } != null

    private fun SettingsUiState.toConfig(): BridgeConfig? =
        if (accessHalfSet) null else PairingInput.build(origin, token, accessId, accessSecret)

    fun forget() = viewModelScope.launch {
        onStop()
        container.settings.forget()
    }

    // ------------------------------------------------------------ new session

    fun setNewCwd(cwd: String) { _newSession.value = _newSession.value.copy(cwd = cwd) }
    fun setNewMode(mode: String) { _newSession.value = _newSession.value.copy(mode = mode) }
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
        repo.createSession(state.cwd.orEmpty(), state.prompt.trim(), state.mode, state.test)
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
     * `model` and `title` are deliberately not touched — this screen has no control for
     * either, and [DraftBody] does not send them, so whatever was set at the desk
     * survives an edit made here.
     */
    fun editDraft(draft: Draft) {
        _newSession.value = NewSessionUiState(
            cwd = draft.cwd,
            mode = draft.permissionMode,
            prompt = draft.prompt,
            test = draft.test,
            editingDraftId = draft.id,
        )
    }

    fun clearDraftEdit() {
        val state = _newSession.value
        _newSession.value = NewSessionUiState(cwd = state.cwd, mode = state.mode)
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
                _newSession.value = NewSessionUiState(cwd = state.cwd, mode = state.mode)
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
    )

    /**
     * Whether starting this would run something other than what is on screen. `cwd` is
     * compared as stored, since the bridge expands `~` before writing it down.
     */
    fun differsFrom(draft: Draft): Boolean =
        cwd != draft.cwd ||
            prompt.trim() != draft.prompt ||
            mode != draft.permissionMode ||
            test != draft.test
}
