package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.GhReach
import com.twentygx.tgxcode.data.model.PrDetail
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.SudoAsk
import com.twentygx.tgxcode.data.model.SuggestionDecision
import com.twentygx.tgxcode.data.model.TaskList
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.ToolResultEvent
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.model.Truncated
import com.twentygx.tgxcode.data.model.TurnMark

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
    /**
     * A `sudo -A` waiting on a password, beside [ask] rather than in it: it is answered
     * to `/api/sudo`, and both can be up at once. Follows `runner.sudoAsk`, so a status
     * that no longer carries it takes the card down.
     */
    val sudoAsk: SudoAsk? = null,
    /** How the last sudo ask ended, for the line left where its card was. */
    val sudoOutcome: String? = null,
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
    /**
     * The agent's own checklist. `task-list` is sent once as soon as the session is
     * followed and then only when it moves, so this is filled without a read of its own
     * when streaming — and polled while not.
     */
    val checklist: TaskList? = null,
    /**
     * The whole conversation's turn rail, from `?turns=`, whatever is loaded. Empty
     * from a bridge older than turn windows — which then sent the whole transcript, or
     * a `?tail=` cut described by [truncated].
     */
    val turns: List<TurnMark> = emptyList(),
    /**
     * The byte position [events] begin at: 0 when everything from the start is in hand.
     * Moves *down* as earlier stretches are loaded, never up.
     */
    val windowStart: Long = 0,
    /**
     * Results that arrived for a call not in hand yet, keyed by the call's id.
     *
     * A stretch loaded with `/range` is parsed on its own, so the stretch loaded
     * *first* — the newer one — can hold the result for a call in an older one. That
     * result had nothing to patch when it came. Dropping it, as a live tail can afford
     * to, would leave the call spinning forever once its stretch arrived; so it is held
     * here and applied then.
     */
    val orphanResults: Map<String, ToolResultEvent> = emptyMap(),
) {
    /** There is transcript above what is loaded — by the turn window, or a `?tail=` cut. */
    val hasEarlier: Boolean get() = windowStart > 0 || (truncated?.dropped ?: 0) > 0

    /** Turns of yours above the loaded window, for the button that loads them. */
    val earlierTurns: Int get() = turns.count { it.isTurn && it.offset < windowStart }
}

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
    val orphans = orphanResults.toMutableMap()

    for (event in incoming) {
        if (event.id.isBlank()) continue

        // A result arriving separately for a call we already rendered. This is the
        // normal path on a live tail, and it is a *patch*, never a row of its own —
        // appending it would put a stray blank event in the log and leave the tool
        // spinning forever.
        if (event is ToolResultEvent) {
            val toolId = event.toolId ?: continue
            val target = index[toolId]
            if (target != null) {
                (list[target] as? ToolEvent)?.let { list[target] = it.withResult(event) }
            } else if (windowStart > 0 || orphans.isNotEmpty()) {
                // The call may be in a stretch not loaded yet. Held only while there
                // is transcript above the window: on a whole transcript, no call is
                // coming and there is nothing to hold it for.
                orphans[toolId] = event
            }
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
        orphanResults = orphans,
    )
}

/**
 * Fold an *earlier* stretch in above what is loaded — `/range` for the part of the
 * transcript the `?turns=` window left out.
 *
 * Same rules as [applyEvents], plus the one that only this direction has: a result
 * held in [Conversation.orphanResults] whose call is in this stretch is applied to it
 * now. And the other way round — a result in this stretch for a call further up still
 * — is held in turn.
 *
 * [Conversation.offset] is not touched: it is where the live tail resumes, at the
 * *end* of the file, and loading history says nothing about that.
 *
 * @param from the byte the stretch began at, which becomes the new window start.
 */
fun Conversation.prependEvents(incoming: List<TranscriptEvent>, from: Long): Conversation {
    val head = mutableListOf<TranscriptEvent>()
    val headIndex = mutableMapOf<String, Int>()
    val orphans = orphanResults.toMutableMap()

    for (event in incoming) {
        if (event.id.isBlank()) continue
        if (event is ToolResultEvent) {
            val toolId = event.toolId ?: continue
            val at = headIndex[toolId]
            if (at != null) {
                (head[at] as? ToolEvent)?.let { head[at] = it.withResult(event) }
            } else if (from > 0) {
                orphans[toolId] = event
            }
            continue
        }
        // Already loaded below — an overlap between the stretch and the window.
        if (event.id in indexById || event.id in headIndex) continue
        headIndex[event.id] = head.size
        head.add(event)
    }

    // Results that were waiting on a call that has now arrived.
    for ((at, event) in head.withIndex()) {
        if (event !is ToolEvent) continue
        orphans.remove(event.id)?.let { head[at] = event.withResult(it) }
    }

    val merged = head + events
    return copy(
        events = merged,
        indexById = merged.withIndex().associate { (i, e) -> e.id to i },
        windowStart = minOf(windowStart, from),
        orphanResults = if (from > 0) orphans else emptyMap(),
    )
}

/**
 * [Conversation.sudoAsk] from a whole runner status, which always carries `sudoAsk`.
 * Gone from the status is gone: answered, denied, or sudo gave up — the card comes down
 * whether or not `sudo-resolved` made it here.
 */
fun Conversation.withSudoFrom(status: RunnerStatus?): Conversation {
    val next = status?.sudoAsk?.copy(sessionId = sessionId)
    return when {
        next?.id == sudoAsk?.id && next?.attempt == sudoAsk?.attempt -> this
        next == null -> copy(sudoAsk = null, sudoOutcome = sudoOutcome ?: sudoAsk?.let { "sudo — Answered." })
        else -> copy(sudoAsk = next, sudoOutcome = null)
    }
}
