package com.twentygx.tgxcode.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.twentygx.tgxcode.data.attach.MAX_ATTACHMENTS
import com.twentygx.tgxcode.data.attach.MAX_ATTACHMENT_BYTES
import com.twentygx.tgxcode.data.attach.StageState
import com.twentygx.tgxcode.data.attach.StagedFile
import com.twentygx.tgxcode.data.attach.UploadTarget
import com.twentygx.tgxcode.data.attach.canSendWith
import com.twentygx.tgxcode.data.attach.readyRefs
import com.twentygx.tgxcode.data.attach.tooBig
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.SudoAsk
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.FlagsRequest
import com.twentygx.tgxcode.data.model.RestartOutcome
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.data.model.BriefDecision
import com.twentygx.tgxcode.data.model.InboxItem
import com.twentygx.tgxcode.data.model.OpenTask
import com.twentygx.tgxcode.data.model.SettingsPatch
import com.twentygx.tgxcode.data.model.TaskFields
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.PairingInput
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.data.repo.isRefused
import com.twentygx.tgxcode.data.settings.SettingsStore
import com.twentygx.tgxcode.di.AppContainer
import com.twentygx.tgxcode.service.BridgeService
import com.twentygx.tgxcode.service.InstallNotifications
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
    val status = container.status
    val workspace = container.workspace
    val orchestrators = container.orchestrators

    /** For the one-off reads the files sheet makes; nothing about them is held. */
    val api = container.api

    /** The Images sheet's pictures; the list itself is read fresh each time it opens. */
    val chatImages = container.chatImages

    /** Slash commands for the open session, read on its first `/`. */
    private val _slash = MutableStateFlow<List<com.twentygx.tgxcode.data.model.SlashCommandInfo>>(emptyList())
    val slash: StateFlow<List<com.twentygx.tgxcode.data.model.SlashCommandInfo>> = _slash.asStateFlow()
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

    /**
     * The composer's files, for the session it is open on. Emptied on [open] like
     * [draft]; each is uploaded the moment it is picked, as on the desktop, so a send
     * is a small JSON POST naming files already on disk.
     */
    private val _staged = MutableStateFlow<List<StagedFile>>(emptyList())
    val staged: StateFlow<List<StagedFile>> = _staged.asStateFlow()

    private var nextStagedId = 0L

    private val _answering = MutableStateFlow<Set<String>>(emptySet())
    val answering: StateFlow<Set<String>> = _answering.asStateFlow()

    /** Sudo ask ids with an answer in flight — apart from [answering], so a permission card beside it stays live. */
    private val _sudoAnswering = MutableStateFlow<Set<String>>(emptySet())
    val sudoAnswering: StateFlow<Set<String>> = _sudoAnswering.asStateFlow()

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
    /** A restart the bridge refused over background tasks, waiting on a yes. */
    private val _restartConfirm = MutableStateFlow<RestartConfirm?>(null)
    val restartConfirm: StateFlow<RestartConfirm?> = _restartConfirm.asStateFlow()

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
        viewModelScope.launch {
            container.phoneInstalls.enabled.collect { on ->
                _settingsUi.value = _settingsUi.value.copy(installApps = on)
            }
        }
        viewModelScope.launch {
            container.settings.thinkingExpanded.collect { on ->
                _settingsUi.value = _settingsUi.value.copy(thinkingExpanded = on)
            }
        }
        viewModelScope.launch {
            container.phoneInstalls.canInstall.collect { can ->
                _settingsUi.value = _settingsUi.value.copy(canInstallApps = can)
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
        // An upload still in flight for the last session lands nowhere: its chip's id
        // is gone from this list, and the file it wrote is harmless on disk.
        _staged.value = emptyList()
        _slash.value = emptyList()
        // A branch was about the session it was armed on, and would be a surprise
        // waiting on the next one opened.
        if (_branch.value?.sessionId != sessionId) _branch.value = null
        // Opening a conversation is reading it, as on the desktop — but only worth a
        // request when the inbox holds something unread about it.
        if (status.inbox.value.rows.any { it.sessionId == sessionId && it.loud && !it.read }) {
            status.markRead(sessionId)
        }
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

    fun loadEarlier(from: Long?) = viewModelScope.launch { repo.loadEarlier(from) }

    fun openAgent(sessionId: String, toolUseId: String, title: String?) =
        viewModelScope.launch { repo.openAgent(sessionId, toolUseId, title) }

    fun closeAgent() = viewModelScope.launch { repo.closeAgent() }

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
            val files = _staged.value
            if (!canSendWith(text, files, sending = false)) return@launch
            val refs = files.readyRefs()
            val branch = _branch.value?.takeIf { it.sessionId == sessionId }
            _sending.value = true
            // Cleared optimistically: the transcript is the record, and holding text in
            // the box after it is already sent invites sending it twice.
            _draft.value = ""
            _staged.value = emptyList()

            if (branch?.first == true) {
                val open = repo.conversation.value?.takeIf { it.sessionId == sessionId }
                val cwd = open?.summary?.cwd
                if (cwd == null) {
                    restoreComposer(text, files)
                    repo.report("That session has no folder to start in", isError = true)
                } else {
                    // Same folder, so the same attached_assets: the files uploaded to this
                    // session resolve for the new one too.
                    repo.createSession(
                        cwd = cwd,
                        prompt = text,
                        mode = mode,
                        test = open.summary.test,
                        model = modelFor(sessionId),
                        effort = effortFor(sessionId),
                        attachments = refs,
                    ).onSuccess { id ->
                        _branch.value = null
                        justCreated = id
                        onNewSession(id)
                    }.onFailure { error ->
                        restoreComposer(text, files)
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
                attachments = refs,
            )
            if (restored != null) {
                restoreComposer(restored, files)
            } else {
                // Sent: the copy is being made, and `session-forked` takes us there.
                if (branch != null) _branch.value = null
                picks.intValue++
            }
            _sending.value = false
        }

    /** Put a message that did not go back in the box, files and all, ahead of anything picked since. */
    private fun restoreComposer(text: String, files: List<StagedFile>) {
        _draft.value = text
        _staged.value = files + _staged.value
    }

    /** Stage what the picker returned and start uploading each to [sessionId]. */
    fun attach(sessionId: String, uris: List<Uri>) {
        val added = stage(uris, already = _staged.value.size, state = StageState.Uploading)
        if (added.isEmpty()) return
        _staged.value = _staged.value + added
        added.forEach { upload(UploadTarget.Session(sessionId), it, ::updateStaged) }
    }

    fun retryStaged(sessionId: String, id: Long) {
        val file = _staged.value.firstOrNull { it.id == id } ?: return
        updateStaged { list -> list.map { if (it.id == id) it.copy(state = StageState.Uploading) else it } }
        upload(UploadTarget.Session(sessionId), file, ::updateStaged)
    }

    /** Off the message only; the bridge never deletes, and the file stays on disk. */
    fun unstage(id: Long) = updateStaged { list -> list.filterNot { it.id == id } }

    private fun updateStaged(change: (List<StagedFile>) -> List<StagedFile>) {
        _staged.value = change(_staged.value)
    }

    /**
     * Picked URIs as chips, after the two checks that need no bytes: the per-message
     * count, and the size of anything that is not a photo (a photo is scaled down
     * before it goes, so its size as picked says nothing about whether it fits).
     */
    private fun stage(uris: List<Uri>, already: Int, state: StageState): List<StagedFile> {
        val room = (MAX_ATTACHMENTS - already).coerceAtLeast(0)
        if (uris.size > room) {
            viewModelScope.launch { repo.report("At most $MAX_ATTACHMENTS files per message", isError = true) }
        }
        return uris.take(room).mapNotNull { uri ->
            val picked = container.attachmentSource.describe(uri)
            if (!picked.isImage && (picked.size ?: 0) > MAX_ATTACHMENT_BYTES) {
                viewModelScope.launch { repo.report(tooBig(picked.name), isError = true) }
                return@mapNotNull null
            }
            StagedFile(
                id = nextStagedId++,
                uri = uri.toString(),
                name = picked.name,
                size = picked.size,
                mediaType = picked.mediaType,
                state = state,
            )
        }
    }

    /** Upload one chip and move it to Ready or Failed — by id, so a removed chip stays removed. */
    private fun upload(
        target: UploadTarget,
        file: StagedFile,
        update: ((List<StagedFile>) -> List<StagedFile>) -> Unit,
    ) = viewModelScope.launch {
        val result = container.uploader.upload(target, file.picked())
        update { list ->
            list.map {
                if (it.id != file.id) it
                else it.copy(state = result, name = (result as? StageState.Ready)?.ref?.name ?: it.name)
            }
        }
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

    /**
     * Replace the session's process so it picks up new tools. Background tasks are the
     * one refusal worth asking about: a yes retries with `force`, which stops them.
     */
    fun restartProcess(sessionId: String, force: Boolean = false) = viewModelScope.launch {
        _restartConfirm.value = null
        val outcome = repo.restart(sessionId, force)
        if (outcome is RestartOutcome.Background && !force) {
            _restartConfirm.value = RestartConfirm(sessionId, outcome.count)
        }
    }

    fun dismissRestartConfirm() {
        _restartConfirm.value = null
    }

    /**
     * A snippet that sends itself: straight to the session, leaving the box — and
     * whatever is half-typed in it — exactly as it was.
     *
     * @param snippetMode the snippet's own mode, or null to keep the session's.
     */
    fun sendCanned(sessionId: String, text: String, snippetMode: String?) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        snippetMode?.let { setMode(sessionId, it) }
        repo.send(
            sessionId = sessionId,
            text = text,
            mode = modeFor(sessionId),
            model = modelFor(sessionId),
            effort = effortFor(sessionId),
        )
        picks.intValue++
    }

    /** Hold what is in the box and deliver it at [at], in [mode]. The box empties only if it was written. */
    fun scheduleSend(sessionId: String, at: Long, mode: String) = viewModelScope.launch {
        val text = _draft.value.trim()
        val files = _staged.value
        if (!canSendWith(text, files, sending = false)) return@launch
        val written = workspace.scheduleMessage(
            sessionId,
            com.twentygx.tgxcode.data.model.LaterCreate(
                text = text,
                permissionMode = mode,
                at = at,
                model = modelFor(sessionId).ifBlank { null },
                effort = effortFor(sessionId).ifBlank { null },
                attachments = files.readyRefs().takeIf { it.isNotEmpty() },
            ),
        )
        if (written && _draft.value.trim() == text) _draft.value = ""
        if (written) _staged.value = _staged.value.filterNot { staged -> files.any { it.id == staged.id } }
    }

    fun rescheduleMessage(id: String, at: Long) = viewModelScope.launch {
        workspace.rescheduleMessage(id, com.twentygx.tgxcode.data.model.LaterPatch(at = at))
    }

    fun loadSlashCommands(sessionId: String) = viewModelScope.launch {
        if (_slash.value.isNotEmpty()) return@launch
        _slash.value = workspace.slashCommands(sessionId, null)
    }

    // -------------------------------------------------------------- schedules

    /** Only `enabled` is sent, so pausing a `dontAsk` schedule from a phone is allowed. */
    fun setScheduleEnabled(schedule: com.twentygx.tgxcode.data.model.Schedule, on: Boolean) = viewModelScope.launch {
        workspace.saveSchedule(
            schedule.id,
            kotlinx.serialization.json.buildJsonObject { put("enabled", kotlinx.serialization.json.JsonPrimitive(on)) },
        )
    }

    fun saveSchedule(id: String?, body: kotlinx.serialization.json.JsonObject, onSaved: () -> Unit) = viewModelScope.launch {
        if (workspace.saveSchedule(id, body) != null) onSaved()
    }

    fun runSchedule(schedule: com.twentygx.tgxcode.data.model.Schedule, onStarted: (String) -> Unit) = viewModelScope.launch {
        workspace.runSchedule(schedule.id)?.let { id ->
            justCreated = id
            onStarted(id)
        }
    }

    // --------------------------------------------------------------- snippets

    fun saveSnippet(id: String?, body: kotlinx.serialization.json.JsonObject, onSaved: () -> Unit) = viewModelScope.launch {
        if (workspace.saveSnippet(id, body) != null) onSaved()
    }

    fun saveSnippetGroup(id: String?, name: String, accent: String?) = viewModelScope.launch {
        workspace.saveGroup(id, name, accent)
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

    /**
     * Send a sudo password. [password] has already left the card's field; it is held
     * here only until the request returns, and never logged — see `SudoLoggingTest`.
     */
    fun answerSudo(ask: SudoAsk, password: String) = viewModelScope.launch {
        _sudoAnswering.value = _sudoAnswering.value + ask.id
        repo.answerSudo(ask, password)
        _sudoAnswering.value = _sudoAnswering.value - ask.id
    }

    fun denySudo(ask: SudoAsk) = viewModelScope.launch {
        _sudoAnswering.value = _sudoAnswering.value + ask.id
        repo.denySudo(ask)
        _sudoAnswering.value = _sudoAnswering.value - ask.id
    }

    // ----------------------------------------------------------- orchestrators

    /**
     * Answer one of an orchestrator's brief questions. Sent in the orchestrator's own
     * mode — the one its composer would send with — so answering does not move it.
     */
    fun answerDecision(orchestratorId: String, decision: BriefDecision, answer: String) = viewModelScope.launch {
        val mode = if (repo.conversation.value?.sessionId == orchestratorId) modeFor(orchestratorId)
        else repo.sessions.value.firstOrNull { it.sessionId == orchestratorId }?.permissionMode ?: "auto"
        orchestrators.answerDecision(orchestratorId, decision, answer, mode)
    }

    fun saveOrchestratorSettings(orchestratorId: String, patch: SettingsPatch) =
        viewModelScope.launch { orchestrators.saveSettings(orchestratorId, patch) }

    fun resumeOrchestrator(orchestratorId: String) = viewModelScope.launch { orchestrators.resume(orchestratorId) }

    fun dismissInboxItem(orchestratorId: String, item: InboxItem) =
        viewModelScope.launch { orchestrators.dismiss(orchestratorId, item.id) }

    fun withdrawTask(orchestratorId: String, task: OpenTask) =
        viewModelScope.launch { orchestrators.withdraw(orchestratorId, task.id) }

    fun makeOrchestrator(sessionId: String) = viewModelScope.launch { orchestrators.enable(sessionId) }

    fun stopOrchestrating(sessionId: String) = viewModelScope.launch { orchestrators.disable(sessionId) }

    /**
     * Hand a stored draft over. The bridge removes it once filed. One open in the form
     * and changed since goes as the form, so what is handed over is what is on screen.
     */
    fun sendDraftToOrchestrator(orchestratorId: String, draft: Draft) = viewModelScope.launch {
        val state = _newSession.value
        if (state.editingDraftId == draft.id && state.differsFrom(draft)) {
            sendFormToOrchestrator(orchestratorId).join()
            return@launch
        }
        if (orchestrators.fileTask(orchestratorId, draftId = draft.id) &&
            _newSession.value.editingDraftId == draft.id
        ) {
            clearDraftEdit()
        }
    }

    /**
     * Hand the Start form over as it stands. A form showing a saved draft sends both,
     * which files what is on screen and consumes the draft — an edit sent unsaved.
     */
    fun sendFormToOrchestrator(orchestratorId: String) = viewModelScope.launch {
        val state = _newSession.value
        val invalid = state.validate()
            ?: "A task cannot carry attachments. Start the session, or remove the files."
                .takeIf { state.attachments.isNotEmpty() }
        if (invalid != null) {
            _newSession.value = state.copy(error = invalid)
            return@launch
        }
        _newSession.value = state.copy(starting = true, error = null)
        val filed = orchestrators.fileTask(
            orchestratorId,
            draftId = state.editingDraftId,
            fields = TaskFields(
                cwd = state.cwd.orEmpty(),
                prompt = state.prompt.trim(),
                permissionMode = state.mode,
                test = state.test,
                model = state.model.takeIf { it.isNotBlank() },
                effort = state.effort.takeIf { it.isNotBlank() },
            ),
        )
        _newSession.value = if (filed) state.blankKeepingSetup() else _newSession.value.copy(starting = false)
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

    fun setInstallApps(on: Boolean) {
        container.phoneInstalls.setEnabled(on)
    }

    fun setThinkingExpanded(on: Boolean) = viewModelScope.launch {
        container.settings.setThinkingExpanded(on)
    }

    /** The phone-installs queue, for the Installs screen. */
    val phoneInstalls = container.phoneInstalls

    /** The Settings card's "Background installs", and the one-time battery prompt. */
    val background = container.background

    private val _retryingInstalls = MutableStateFlow<Set<String>>(emptySet())
    val retryingInstalls: StateFlow<Set<String>> = _retryingInstalls.asStateFlow()

    fun refreshInstalls() = viewModelScope.launch { container.phoneInstalls.refresh() }

    fun retryInstall(id: String) = viewModelScope.launch {
        _retryingInstalls.value += id
        try {
            container.phoneInstalls.retry(id)?.let { repo.report(it, isError = true) }
        } finally {
            _retryingInstalls.value -= id
        }
    }

    /**
     * The per-app "Install unknown apps" screen. Coming back is a resume, which re-reads
     * the grant and carries on anything that was waiting for it.
     */
    fun grantInstallPermission() {
        runCatching {
            container.appContext.startActivity(
                InstallNotifications.unknownSourcesIntent(container.appContext)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            viewModelScope.launch { repo.report("Couldn't open the install-apps setting on this phone", isError = true) }
        }
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

    fun setNewCwd(cwd: String) {
        val state = _newSession.value
        // A file already uploaded went into the old folder's attached_assets, which the
        // new folder's session would not resolve; hold it again so Start re-sends it.
        val files = if (cwd == state.cwd) state.attachments
        else state.attachments.map { if (it.state is StageState.Ready) it.copy(state = StageState.Held) else it }
        _newSession.value = state.copy(cwd = cwd, attachments = files)
    }

    /** Held, not uploaded: there is no session yet, and the folder can still change. */
    fun attachNew(uris: List<Uri>) {
        val state = _newSession.value
        val added = stage(uris, already = state.attachments.size, state = StageState.Held)
        _newSession.value = state.copy(attachments = state.attachments + added, error = null)
    }

    fun unstageNew(id: Long) = updateNew { list -> list.filterNot { it.id == id } }

    private fun updateNew(change: (List<StagedFile>) -> List<StagedFile>) {
        _newSession.value = _newSession.value.copy(attachments = change(_newSession.value.attachments))
    }
    fun setNewMode(mode: String) { _newSession.value = _newSession.value.copy(mode = mode) }
    fun setNewModel(model: String) { _newSession.value = _newSession.value.copy(model = model) }
    fun setNewEffort(effort: String) { _newSession.value = _newSession.value.copy(effort = effort) }
    fun setNewPrompt(text: String) { _newSession.value = _newSession.value.copy(prompt = text) }
    fun setNewTest(on: Boolean) { _newSession.value = _newSession.value.copy(test = on) }

    /**
     * Ticking it moves a `plan` session to `auto`, as the desktop's dialog does: in plan
     * mode every worker it starts would wait on your approval before doing anything.
     */
    fun setNewOrchestrator(on: Boolean) {
        val state = _newSession.value
        _newSession.value = state.copy(
            orchestrator = on,
            mode = if (on && state.mode == "plan") "auto" else state.mode,
        )
    }

    fun startSession(onStarted: (String) -> Unit) = viewModelScope.launch {
        val state = _newSession.value
        val invalid = state.validate()
        if (invalid != null) {
            _newSession.value = state.copy(error = invalid)
            return@launch
        }
        _newSession.value = state.copy(starting = true, error = null)

        // Upload, then create — the order the bridge asks for. One at a time, and a
        // failure stops the start: a session begun without a file its first message
        // talks about is worse than one not begun yet.
        val cwd = state.cwd.orEmpty()
        for (file in state.attachments.filter { it.state !is StageState.Ready }) {
            updateNew { list -> list.map { if (it.id == file.id) it.copy(state = StageState.Uploading) else it } }
            upload(UploadTarget.Folder(cwd), file, ::updateNew).join()
        }
        val files = _newSession.value.attachments
        val failed = files.firstOrNull { it.state is StageState.Failed }
        if (failed != null) {
            _newSession.value = _newSession.value.copy(
                starting = false,
                error = "${failed.name}: ${(failed.state as StageState.Failed).reason}",
            )
            return@launch
        }

        repo.createSession(
            cwd = cwd,
            prompt = state.prompt.trim(),
            mode = state.mode,
            test = state.test,
            model = state.model,
            effort = state.effort,
            attachments = files.readyRefs(),
            orchestrator = state.orchestrator,
        )
            .onSuccess { id ->
                _newSession.value = _newSession.value.copy(
                    starting = false,
                    prompt = "",
                    attachments = emptyList(),
                    editingDraftId = null,
                    orchestrator = false,
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
    /** A passing line for the snackbar, from a screen that has nothing else to say it with. */
    fun notice(text: String) {
        viewModelScope.launch { repo.report(text) }
    }

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
            ?: "A draft cannot hold files. Remove them, or Start it now.".takeIf { state.attachments.isNotEmpty() }
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
                repo.report(
                    (if (editing == null) "Saved as a draft" else "Draft updated") +
                        (if (state.orchestrator) " — a draft does not remember “Start as an orchestrator”" else ""),
                )
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
    /** Start it as an orchestrator. Not sticky, and a draft cannot remember it. */
    val orchestrator: Boolean = false,
    /** Held until Start, then uploaded to [cwd]. Drafts cannot carry them. */
    val attachments: List<StagedFile> = emptyList(),
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
        prompt.isBlank() && attachments.isEmpty() -> "A session needs a first message."
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

/** [count] background tasks would stop if [sessionId]'s process were restarted. */
data class RestartConfirm(val sessionId: String, val count: Int)
