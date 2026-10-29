package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.BriefDecision
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.OrchestratorPayload
import com.twentygx.tgxcode.data.model.OrchestratorPut
import com.twentygx.tgxcode.data.model.OrchestratorRow
import com.twentygx.tgxcode.data.model.SettingsPatch
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.model.TaskFields
import com.twentygx.tgxcode.data.model.TaskFiling
import com.twentygx.tgxcode.data.net.BridgeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrators: the payload of each one looked at, and the list a task can go to.
 *
 * The `orchestrator` event carries the **whole** payload, ungated and debounced to the
 * end of a burst, so after one read a held payload is replaced rather than refetched.
 * Every one pushed is kept, not just the one on screen: it is a few KB, and keeping it
 * means opening an orchestrator draws at once instead of after a round trip. While
 * polling nothing is pushed, so the one on screen is re-read on the board's tier.
 *
 * The list ([orchestrators]) is `GET /api/orchestrators`, which `docs/api.md` names as
 * the one answer to "is there an orchestrator to send to" — read on attaching, after
 * any orchestrator changes, and whenever a picker opens.
 */
class OrchestratorStore(
    private val api: BridgeApi,
    private val repo: BridgeRepository,
    private val scope: CoroutineScope,
) {
    private val _payloads = MutableStateFlow<Map<String, OrchestratorPayload>>(emptyMap())
    val payloads: StateFlow<Map<String, OrchestratorPayload>> = _payloads.asStateFlow()

    private val _orchestrators = MutableStateFlow<List<OrchestratorRow>?>(null)
    val orchestrators: StateFlow<List<OrchestratorRow>?> = _orchestrators.asStateFlow()

    /** Write ids in flight — a decision, an item, a task, `settings` — so buttons go quiet. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** The orchestrator on screen, if any: the one polling re-reads. */
    @Volatile private var watching: String? = null

    init {
        scope.launch {
            repo.resyncs.collect {
                loadList()
                watching?.let { load(it) }
            }
        }
        scope.launch {
            repo.pollRounds.collect { round ->
                if (round % BridgeRepository.BOARD_EVERY == 0) watching?.let { load(it) }
                if (round % BridgeRepository.INDEX_EVERY == 0) loadList()
            }
        }
        scope.launch { repo.events.collect(::onEvent) }
    }

    private suspend fun onEvent(event: StreamEvent) {
        if (event !is StreamEvent.OrchestratorChanged) return
        put(event.payload)
        // Counts, the live state and new or removed orchestrators all move with it.
        loadList()
    }

    private fun put(payload: OrchestratorPayload) {
        _payloads.value = _payloads.value + (payload.orchestratorId to payload)
    }

    /** Start following one: read it now, and keep it fresh while polling. */
    fun watch(sessionId: String) {
        watching = sessionId
        scope.launch { load(sessionId) }
    }

    fun unwatch(sessionId: String) {
        if (watching == sessionId) watching = null
    }

    suspend fun load(sessionId: String) {
        api.orchestrator(sessionId).getOrNull()?.let(::put)
    }

    suspend fun loadList() {
        api.orchestrators().getOrNull()?.let { _orchestrators.value = it.orchestrators }
    }

    private suspend inline fun <T> busy(key: String, block: () -> T): T {
        _busy.value = _busy.value + key
        try {
            return block()
        } finally {
            _busy.value = _busy.value - key
        }
    }

    // ------------------------------------------------------------------ role

    /**
     * Mark an existing session as one. Its process is replaced when it is next free,
     * because the CLI reads its tool list once at start — the answer says when.
     */
    suspend fun enable(sessionId: String): Boolean = busy("role:$sessionId") {
        when (val result = api.putOrchestrator(sessionId, OrchestratorPut(enabled = true))) {
            is ApiResult.Ok -> {
                put(result.value)
                repo.report("Now an orchestrator — it picks up its tools when it is next free")
                loadList()
                true
            }
            else -> {
                repo.report(
                    when {
                        result is ApiResult.Http && result.status == 409 ->
                            "A worker cannot be an orchestrator — they only go one level deep."
                        else -> "Could not make it an orchestrator: ${result.message()}"
                    },
                    isError = true,
                )
                false
            }
        }
    }

    /**
     * The workers carry on as ordinary sessions; the inbox is discarded, and a worker
     * blocked on an ask has it denied. Open tasks go back to Drafts.
     */
    suspend fun disable(sessionId: String): Boolean = busy("role:$sessionId") {
        when (val result = api.putOrchestrator(sessionId, OrchestratorPut(enabled = false))) {
            is ApiResult.Ok -> {
                put(result.value)
                repo.report("No longer an orchestrator")
                loadList()
                true
            }
            else -> {
                repo.report("Could not turn it off: ${result.message()}", isError = true)
                false
            }
        }
    }

    // -------------------------------------------------------------- settings

    /**
     * Answers the saved payload, or null when it failed. Its `ownModelApplied` is
     * `after-turn` when the orchestrator's own model changes only once it finishes the
     * turn it is in, which is said here as the desktop toasts it.
     */
    suspend fun saveSettings(sessionId: String, patch: SettingsPatch): OrchestratorPayload? = busy("settings:$sessionId") {
        when (val result = api.putOrchestrator(sessionId, OrchestratorPut(settings = patch))) {
            is ApiResult.Ok -> {
                put(result.value)
                if (result.value.ownModelApplied == OWN_AFTER_TURN) {
                    repo.report("Saved. The orchestrator switches when its current turn ends.")
                }
                result.value
            }
            else -> {
                repo.report("Could not save the settings: ${result.message()}", isError = true)
                null
            }
        }
    }

    /** A `409` is usage still over the cutoff, and the bridge's sentence says which. */
    suspend fun resume(sessionId: String): Boolean = busy("resume:$sessionId") {
        when (val result = api.resumeOrchestrator(sessionId)) {
            is ApiResult.Ok -> {
                put(result.value)
                repo.report("Resumed")
                true
            }
            else -> {
                repo.report(
                    if (result is ApiResult.Http && result.status == 409) {
                        "Still over the cutoff — ${result.message()}. Raise or turn off the cutoff first."
                    } else {
                        "Could not resume: ${result.message()}"
                    },
                    isError = true,
                )
                false
            }
        }
    }

    // ----------------------------------------------------------------- inbox

    suspend fun dismiss(sessionId: String, itemId: String): Boolean = busy(itemId) {
        val result = api.dismissInboxItem(sessionId, itemId)
        // 404: already gone, which is what was asked for.
        if (result is ApiResult.Ok || (result is ApiResult.Http && result.status == 404)) {
            load(sessionId)
            true
        } else {
            repo.report("Could not dismiss it: ${result.message()}", isError = true)
            false
        }
    }

    // ------------------------------------------------------------- decisions

    /**
     * Answer one of the brief's questions: **send first, then record**, as the desktop
     * does, so nothing is drawn as answered unless it is on its way. The orchestrator
     * only learns the answer from the message; the record only tells every window to
     * stop asking.
     *
     * @param mode the orchestrator's own permission mode, so answering it does not
     *   quietly move it to `auto`.
     */
    suspend fun answerDecision(
        sessionId: String,
        decision: BriefDecision,
        answer: String,
        mode: String,
    ): Boolean = busy(decision.id) {
        val text = answer.trim()
        if (text.isEmpty()) return@busy false
        val unsent = repo.send(sessionId, decisionMessage(decision, text), mode)
        if (unsent != null) return@busy false
        when (val result = api.answerDecision(sessionId, decision.id, text)) {
            is ApiResult.Ok -> {
                val held = _payloads.value[sessionId]
                val answered = result.value.decision
                if (held?.summary != null && answered != null) {
                    put(
                        held.copy(
                            summary = held.summary.copy(
                                decisions = held.summary.decisions.map { if (it.id == answered.id) answered else it },
                            ),
                        ),
                    )
                }
            }
            // Already dropped from the brief: answered as far as anyone can tell.
            is ApiResult.Http -> if (result.status != 404) {
                repo.report("Sent, but not marked answered: ${result.message()}", isError = true)
            }
            else -> repo.report("Sent, but not marked answered: ${result.message()}", isError = true)
        }
        true
    }

    // ----------------------------------------------------------------- tasks

    /**
     * Hand a draft over as it is stored, or a form as it stands — or both, for a draft
     * edited and sent without saving, which consumes the draft. Nothing starts: the
     * orchestrator decides how it fits.
     */
    suspend fun fileTask(
        orchestratorId: String,
        draftId: String? = null,
        fields: TaskFields? = null,
    ): Boolean = busy("task:${draftId ?: orchestratorId}") {
        when (val result = api.fileTask(orchestratorId, TaskFiling(draftId = draftId, fields = fields))) {
            is ApiResult.Ok -> {
                val title = _orchestrators.value?.firstOrNull { it.orchestratorId == orchestratorId }
                    ?.displayTitle ?: repo.titleOf(orchestratorId) ?: "the orchestrator"
                repo.report(
                    if (result.value.paused) "Handed to $title — it is paused, so it waits until it resumes"
                    else "Handed to $title",
                )
                load(orchestratorId)
                true
            }
            else -> {
                repo.report(taskRefusal(result), isError = true)
                false
            }
        }
    }

    /** It becomes a draft again — the same draft when it came from one. */
    suspend fun withdraw(orchestratorId: String, itemId: String): Draft? = busy(itemId) {
        when (val result = api.withdrawTask(orchestratorId, itemId)) {
            is ApiResult.Ok -> {
                repo.report("Taken back — it is in Drafts again")
                load(orchestratorId)
                result.value.draft
            }
            else -> {
                repo.report(
                    when {
                        result is ApiResult.Http && result.status == 409 ->
                            "Too late — it has already been started, or Drafts is full."
                        result is ApiResult.Http && result.status == 403 ->
                            "That task's permission mode cannot be saved as a draft from here."
                        result is ApiResult.Http && result.status == 404 -> "That task is gone."
                        else -> "Could not take it back: ${result.message()}"
                    },
                    isError = true,
                )
                null
            }
        }
    }

    private fun taskRefusal(result: ApiResult<*>): String = when {
        result !is ApiResult.Http -> "Could not hand it over: ${result.message()}"
        result.status == 403 -> "That permission mode cannot be handed over from here."
        result.status == 404 -> "That orchestrator, or the draft, is gone."
        result.status == 409 -> "That orchestrator already has 20 tasks waiting."
        result.status == 400 -> "Could not hand it over — ${result.message()}"
        else -> "Could not hand it over: ${result.message()}"
    }
}

/**
 * What an answer to a brief's question is sent as. The orchestrator is told to expect
 * exactly this: a message starting `Re: <the question>`.
 */
internal fun decisionMessage(decision: BriefDecision, answer: String): String =
    "Re: ${decision.text.trim()}\n\n${answer.trim()}"

/** `ownModelApplied` when the orchestrator is mid-turn. */
const val OWN_AFTER_TURN = "after-turn"
