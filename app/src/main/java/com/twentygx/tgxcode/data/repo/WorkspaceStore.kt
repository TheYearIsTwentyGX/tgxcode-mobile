package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.LaterCreate
import com.twentygx.tgxcode.data.model.LaterMessage
import com.twentygx.tgxcode.data.model.LaterPatch
import com.twentygx.tgxcode.data.model.Schedule
import com.twentygx.tgxcode.data.model.SlashCommandInfo
import com.twentygx.tgxcode.data.model.Snippet
import com.twentygx.tgxcode.data.model.SnippetGroup
import com.twentygx.tgxcode.data.model.SnippetGroupBody
import com.twentygx.tgxcode.data.model.SnippetReorder
import com.twentygx.tgxcode.data.model.SnippetsPayload
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.BridgeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Work kept on the bridge that belongs to no one conversation: messages scheduled for
 * later, snippets, and schedules.
 *
 * All three are pushed as **whole payloads** (`later-changed`, `snippets-changed`,
 * `schedules-changed`), ungated, so after one read on attaching there is nothing to
 * refetch — and two of them move with nobody touching anything, as messages deliver and
 * schedules fire. While polling nothing is pushed, so they ride the 20s tier.
 *
 * Writes apply the row the bridge answers with straight away rather than waiting for
 * the push, which would otherwise leave the list a round trip behind the press.
 */
class WorkspaceStore(
    private val api: BridgeApi,
    private val repo: BridgeRepository,
    private val scope: CoroutineScope,
) {
    private val _later = MutableStateFlow<List<LaterMessage>>(emptyList())
    val later: StateFlow<List<LaterMessage>> = _later.asStateFlow()

    private val _snippets = MutableStateFlow(SnippetsPayload())
    val snippets: StateFlow<SnippetsPayload> = _snippets.asStateFlow()

    private val _schedules = MutableStateFlow<List<Schedule>?>(null)
    val schedules: StateFlow<List<Schedule>?> = _schedules.asStateFlow()

    /** Slash commands per session or directory, dropped when `slash-commands` says they moved. */
    private val slashCache = mutableMapOf<String, List<SlashCommandInfo>>()

    init {
        scope.launch { repo.resyncs.collect { loadAll() } }
        scope.launch {
            repo.pollRounds.collect { round -> if (round % BridgeRepository.INDEX_EVERY == 0) loadAll() }
        }
        scope.launch { repo.events.collect(::onEvent) }
    }

    private suspend fun loadAll() {
        api.later().getOrNull()?.let { _later.value = it.messages }
        api.snippets().getOrNull()?.let { _snippets.value = it }
        api.schedules().getOrNull()?.let { _schedules.value = it.schedules }
    }

    suspend fun loadSchedules() {
        api.schedules().getOrNull()?.let { _schedules.value = it.schedules }
    }

    private fun onEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.LaterChanged -> _later.value = event.payload.messages
            is StreamEvent.SnippetsChanged -> _snippets.value = event.payload
            is StreamEvent.SchedulesChanged -> _schedules.value = event.payload.schedules
            // Commands are per directory and a session's directory is not known here,
            // so one change drops everything; the next `/` re-reads one list.
            is StreamEvent.SlashCommandsChanged -> slashCache.clear()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ later

    /** @return true when it was written; the failure has already been said. */
    suspend fun scheduleMessage(sessionId: String, body: LaterCreate): Boolean =
        when (val result = api.createLater(sessionId, body)) {
            is ApiResult.Ok -> {
                upsertLater(result.value.message)
                repo.report("Scheduled")
                true
            }
            else -> {
                repo.report("Could not schedule it: ${laterRefusal(result)}", isError = true)
                false
            }
        }

    suspend fun rescheduleMessage(id: String, body: LaterPatch): Boolean =
        when (val result = api.patchLater(id, body)) {
            is ApiResult.Ok -> {
                upsertLater(result.value.message)
                true
            }
            else -> {
                repo.report("Could not change it: ${laterRefusal(result)}", isError = true)
                false
            }
        }

    /** Deleting is cancelling: there is no `cancelled` state to move it to. */
    fun cancelMessage(id: String) {
        val before = _later.value
        _later.value = before.filterNot { it.id == id }
        scope.launch {
            val result = api.deleteLater(id)
            if (result !is ApiResult.Ok && !(result is ApiResult.Http && result.status == 404)) {
                _later.value = before
                repo.report("Could not cancel it: ${result.message()}", isError = true)
            }
        }
    }

    fun sendMessageNow(id: String) {
        scope.launch {
            when (val result = api.sendLaterNow(id)) {
                is ApiResult.Ok -> repo.report("Sent")
                is ApiResult.Http -> repo.report(
                    when (result.status) {
                        409 -> "Not now — the session is busy or held elsewhere. Try again in a moment."
                        403 -> "That message's permission mode cannot be sent from a phone."
                        502 -> "The delivery did not land; the message is kept with the error."
                        else -> "Could not send it: ${result.message()}"
                    },
                    isError = true,
                )
                is ApiResult.Offline -> repo.report("Could not send it: ${result.message()}", isError = true)
            }
        }
    }

    private fun upsertLater(row: LaterMessage) {
        _later.value = (_later.value.filterNot { it.id == row.id } + row).sortedBy { it.at }
    }

    private fun laterRefusal(result: ApiResult<*>): String = when {
        result is ApiResult.Http && result.status == 403 -> "that permission mode cannot be scheduled from a phone."
        result is ApiResult.Http && result.status == 409 -> result.message()
        else -> result.message()
    }

    // --------------------------------------------------------------- snippets

    suspend fun saveSnippet(id: String?, body: JsonObject): Snippet? {
        val result = if (id == null) api.createSnippet(body) else api.patchSnippet(id, body)
        return when (result) {
            is ApiResult.Ok -> {
                val row = result.value.snippet
                _snippets.value = _snippets.value.let { p ->
                    p.copy(
                        snippets = if (p.snippets.any { it.id == row.id }) {
                            p.snippets.map { if (it.id == row.id) row else it }
                        } else {
                            p.snippets + row
                        },
                    )
                }
                row
            }
            else -> {
                repo.report("Could not save that snippet: ${snippetRefusal(result)}", isError = true)
                null
            }
        }
    }

    fun deleteSnippet(id: String) {
        val before = _snippets.value
        _snippets.value = before.copy(snippets = before.snippets.filterNot { it.id == id })
        scope.launch {
            val result = api.deleteSnippet(id)
            if (result !is ApiResult.Ok && !(result is ApiResult.Http && result.status == 404)) {
                _snippets.value = before
                repo.report("Could not delete that snippet: ${result.message()}", isError = true)
            }
        }
    }

    /**
     * Move one snippet a place up or down *within its group*, as one reorder call. A
     * snippet's `order` is global, but only the relative order inside a group shows, so
     * sending that group's ids in their new order is a whole answer.
     */
    fun moveSnippet(snippet: Snippet, by: Int) {
        val siblings = _snippets.value.snippets.filter { it.groupId == snippet.groupId }.map { it.id }.toMutableList()
        val from = siblings.indexOf(snippet.id)
        val to = from + by
        if (from < 0 || to !in siblings.indices) return
        siblings.add(to, siblings.removeAt(from))
        scope.launch {
            when (val result = api.reorderSnippets(SnippetReorder(snippets = siblings))) {
                is ApiResult.Ok -> _snippets.value = result.value
                else -> repo.report("Could not move it: ${result.message()}", isError = true)
            }
        }
    }

    suspend fun saveGroup(id: String?, name: String, accent: String?): SnippetGroup? {
        val body = SnippetGroupBody(name = name.trim(), accent = accent?.takeIf { it.isNotBlank() })
        val result = if (id == null) api.createSnippetGroup(body) else api.patchSnippetGroup(id, body)
        return when (result) {
            is ApiResult.Ok -> {
                val group = result.value.group
                _snippets.value = _snippets.value.let { p ->
                    p.copy(groups = p.groups.filterNot { it.id == group.id } + group)
                }
                group
            }
            else -> {
                repo.report("Could not save that group: ${result.message()}", isError = true)
                null
            }
        }
    }

    /** Its snippets are not deleted and keep their `groupId`; they draw ungrouped. */
    fun deleteGroup(id: String) {
        val before = _snippets.value
        _snippets.value = before.copy(groups = before.groups.filterNot { it.id == id })
        scope.launch {
            val result = api.deleteSnippetGroup(id)
            if (result !is ApiResult.Ok && !(result is ApiResult.Http && result.status == 404)) {
                _snippets.value = before
                repo.report("Could not delete that group: ${result.message()}", isError = true)
            }
        }
    }

    private fun snippetRefusal(result: ApiResult<*>): String = when {
        result is ApiResult.Http && result.status == 403 -> "that permission mode cannot be set from a phone."
        result is ApiResult.Http && result.status == 409 -> "that is 200 snippets. Delete one to make room."
        else -> result.message()
    }

    // -------------------------------------------------------------- schedules

    suspend fun saveSchedule(id: String?, body: JsonObject): Schedule? {
        val result = if (id == null) api.createSchedule(body) else api.patchSchedule(id, body)
        return when (result) {
            is ApiResult.Ok -> {
                val row = result.value.schedule
                _schedules.value = listOf(row) + _schedules.value.orEmpty().filterNot { it.id == row.id }
                row
            }
            else -> {
                repo.report("Could not save that schedule: ${scheduleRefusal(result)}", isError = true)
                null
            }
        }
    }

    fun deleteSchedule(id: String) {
        val before = _schedules.value
        _schedules.value = before?.filterNot { it.id == id }
        scope.launch {
            val result = api.deleteSchedule(id)
            if (result !is ApiResult.Ok && !(result is ApiResult.Http && result.status == 404)) {
                _schedules.value = before
                repo.report("Could not delete that schedule: ${result.message()}", isError = true)
            }
        }
    }

    /** @return the first session the run started, to open. A PR gate may start several. */
    suspend fun runSchedule(id: String): String? =
        when (val result = api.runSchedule(id)) {
            is ApiResult.Ok -> {
                val started = result.value.sessionIds.ifEmpty { listOfNotNull(result.value.sessionId) }
                repo.report(
                    when {
                        started.isEmpty() -> "Nothing to start"
                        started.size == 1 -> "Started"
                        else -> "Started ${started.size} sessions"
                    } + if (result.value.deferred > 0) " — ${result.value.deferred} more to follow" else "",
                )
                started.firstOrNull()
            }
            else -> {
                repo.report("Could not run it: ${scheduleRefusal(result)}", isError = true)
                null
            }
        }

    private fun scheduleRefusal(result: ApiResult<*>): String = when {
        result is ApiResult.Http && result.status == 403 ->
            "its permission mode cannot be started or set from a phone."
        result is ApiResult.Http && result.status == 429 -> "too many sessions at once. Try again in a moment."
        else -> result.message()
    }

    // --------------------------------------------------------- slash commands

    suspend fun slashCommands(sessionId: String?, cwd: String?): List<SlashCommandInfo> {
        val key = sessionId?.let { "s:$it" } ?: cwd?.let { "d:$it" } ?: return emptyList()
        slashCache[key]?.let { return it }
        val list = api.slashCommands(sessionId = sessionId, cwd = if (sessionId == null) cwd else null)
            .getOrNull()?.commands ?: return emptyList()
        slashCache[key] = list
        return list
    }
}
