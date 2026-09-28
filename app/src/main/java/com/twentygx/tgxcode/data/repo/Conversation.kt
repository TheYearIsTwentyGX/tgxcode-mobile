package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.GhReach
import com.twentygx.tgxcode.data.model.PrDetail
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.SuggestionDecision
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.ToolResultEvent
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.model.Truncated

/**
 * One open conversation.
 *
 * [indexById] does two jobs that mobile.js splits: it is the `seen` set that makes
 * appending idempotent, *and* the lookup that finds a tool row to patch in place
 * (where the web client does a DOM query). One map, both answers.
 */
data class Conversation(
    val sessionId: String,
    val summary: SessionSummary,
    val events: List<TranscriptEvent> = emptyList(),
    val indexById: Map<String, Int> = emptyMap(),
    /** A **byte position**. Only ever taken from a server response. */
    val offset: Long = 0,
    val truncated: Truncated? = null,
    val runner: RunnerStatus? = null,
    val ask: Ask? = null,
    val lastOutcome: String? = null,
    val loading: Boolean = false,
    /**
     * What was already decided about each `suggestion` event here, keyed by its id.
     *
     * Carried beside [events] rather than folded into them because the two come from
     * different places on the bridge — the offer is in the transcript, the decision is
     * in a store of its own — and only one of them can change without the transcript
     * changing.
     */
    val suggestions: Map<String, SuggestionDecision> = emptyMap(),
    /**
     * What has become of each pull request this session raised, for the chip bar.
     *
     * **Per PR, not the session's aggregate.** The Sessions rows fold a session's whole
     * set down to the one that most needs attention; this surface has room to say what
     * became of each, so it reads `GET /api/sessions/:id/prs` instead — which resolves
     * each PR under the *other* precedence order. Two different questions, two
     * requests; `summary.prs` already has the numbers and the links, so this only
     * fills in what they came to.
     *
     * Empty until that read lands, which is deliberately not awaited on open: the
     * transcript must never wait on GitHub state.
     */
    val prs: List<PrDetail> = emptyList(),
    /** Whether GitHub was reachable when [prs] was read. Null before the first read. */
    val prsGh: GhReach? = null,
)

/**
 * Fold new events in.
 *
 * Two rules, both from `docs/api.md`:
 *
 * 1. **A tool call is not final when you first see it.** It arrives once with no
 *    status and no result, and again later with both. So a repeat of a known id is
 *    not a duplicate to drop — it is the result landing, and it replaces in place.
 *    Anything keyed on first sight shows a permanently spinning tool.
 * 2. **The offset never goes backwards.** Reconnect fires `/since` while a `tail`
 *    may already be in flight, and the two can arrive out of order. Rendering is
 *    idempotent by id so duplicate *events* are harmless, but an offset that
 *    regresses makes the next `/since` refetch — potentially forever. `maxOf` costs
 *    one line and removes the whole class of bug.
 */
fun Conversation.applyEvents(incoming: List<TranscriptEvent>, newOffset: Long?): Conversation {
    if (incoming.isEmpty()) {
        return if (newOffset != null) copy(offset = maxOf(offset, newOffset)) else this
    }

    val list = events.toMutableList()
    val index = indexById.toMutableMap()

    for (event in incoming) {
        if (event.id.isBlank()) continue

        // A result arriving separately for a call we already rendered. This is the
        // normal path on a live tail, and it is a *patch*, never a row of its own —
        // appending it would put a stray blank event in the log and leave the tool
        // spinning forever.
        if (event is ToolResultEvent) {
            val target = event.toolId?.let { index[it] }
            if (target != null) {
                (list[target] as? ToolEvent)?.let { list[target] = it.withResult(event) }
            }
            // No matching call means the tail started after it; nothing to show.
            continue
        }

        val at = index[event.id]
        if (at != null) {
            // Only a tool legitimately changes after first sight. Anything else
            // repeating is a genuine duplicate from an overlapping tail/since.
            if (event is ToolEvent) list[at] = event
        } else {
            index[event.id] = list.size
            list.add(event)
        }
    }

    return copy(
        events = list,
        indexById = index,
        offset = if (newOffset != null) maxOf(offset, newOffset) else offset,
    )
}
