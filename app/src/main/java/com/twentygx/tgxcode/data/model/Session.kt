package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Worktree(
    val name: String? = null,
    val path: String? = null,
    val branch: String? = null,
)

/**
 * Claude Code's *own* process registry — something running in a terminal.
 *
 * Deliberately separate from [RunnerStatus], which is *this bridge's* process for a
 * session. `sessions.js` keeps them apart and so must we: the board's `elsewhere`
 * reason is `runner == null && live.running`, so collapsing the two into one
 * "is it running" boolean tells someone their own session is running in another
 * window when it is running right here.
 */
@Serializable
data class LiveInfo(
    val sessionId: String? = null,
    val pid: Int? = null,
    val cwd: String? = null,
    val kind: String? = null,
    val entrypoint: String? = null,
    val name: String? = null,
    val status: String? = null,
    val version: String? = null,
    val startedAt: Long? = null,
    val running: Boolean = false,
)

/**
 * The reduced runner shape carried on a summary or an overview card — not the full
 * [RunnerStatus].
 *
 * Two different cuts share this class: a summary carries six fields
 * (`state, activity, detail, queued, claudeVersion, stalled`) and a board card eight
 * (`state, activity, queued, busySince, retry, error, errorKind, stalled`). Every field
 * is optional so one class decodes both; [stalled] is the only one they agree on that
 * the old five-field summary lacked, and absent reads as false — the right answer for
 * an older bridge.
 */
@Serializable
data class CardRunner(
    val state: String? = null,
    val activity: String? = null,
    val detail: String? = null,
    val queued: Int = 0,
    val busySince: Long? = null,
    val error: String? = null,
    val errorKind: String? = null,
    val stalled: Boolean = false,
)

/**
 * One line on where the session's last turn left things — "PR #150 opened, waiting on
 * review".
 *
 * Null far more often than not, and that is normal: only sessions this bridge ran a
 * turn for get one, about fifteen seconds after it ended. It describes the *previous*
 * reply, so it is not shown while the session is busy (`web/app.js` hides it too).
 * Arrives on the summary and is patched by `standing-changed`; `sessions-changed`
 * does not carry it.
 */
@Serializable
data class Standing(
    val text: String = "",
    /** `model` (a short haiku call wrote it) or `extract` (the reply's last prose line). */
    val source: String? = null,
    /** Epoch millis. */
    val at: Long? = null,
)

/** Messages waiting to be delivered to a session later: how many, and the soonest. */
@Serializable
data class LaterBadge(
    val pending: Int = 0,
    /** Epoch millis. */
    val nextAt: Long? = null,
)

/** The schedule that started this session; [title] is already resolved, never empty. */
@Serializable
data class ScheduleRef(
    val id: String? = null,
    val title: String? = null,
)

@Serializable
data class SessionSummary(
    val sessionId: String,
    val title: String? = null,
    val projectName: String? = null,
    val cwd: String? = null,
    val projectCwd: String? = null,
    val gitBranch: String? = null,
    val worktree: Worktree? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val sessionKind: String? = null,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val toolCalls: Int = 0,
    /** ISO-8601 strings, not epoch millis. */
    val firstTs: String? = null,
    val lastTs: String? = null,
    val lastUserTs: String? = null,
    val lastPrompt: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val test: Boolean = false,
    val live: LiveInfo? = null,
    val runner: CardRunner? = null,
    val standing: Standing? = null,
    val later: LaterBadge? = null,
    val schedule: ScheduleRef? = null,
    /** Where [title] came from; `user` is a name set through `/flags`. */
    val titleSource: String? = null,
    /**
     * Every pull request the session raised, in the order it raised them.
     *
     * Read out of the transcript, so it is free and it is *history* — what has become
     * of them is a separate request ([PrsPayload] for a status per session, or
     * `GET /api/sessions/:id/prs` for one). An array because a session that lands one
     * PR and opens another is ordinary; the bridge sent a single `pr` object until
     * August 2026, which silently kept only the newest. Capped at ten.
     */
    val prs: List<PrRef> = emptyList(),
    /** Set on a session marked as an orchestrator: `{inbox, workers, paused}`. */
    val orchestrator: OrchestratorBadge? = null,
    /** Set on a session an orchestrator started. */
    val worker: WorkerBadge? = null,
) {
    /** What the Sessions row shows before the dot: worktree beats project name. */
    val place: String? get() = worktree?.name ?: projectName

    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"

    /**
     * Whether this row can carry a PR glyph at all.
     *
     * The distinction that matters: this being false means *there are no pull
     * requests* and nothing is drawn, which is not the same as having some whose
     * status nobody has looked up yet — that one is drawn, in grey.
     */
    val hasPrs: Boolean get() = prs.isNotEmpty()

    /**
     * The standing line, when it should be drawn at all.
     *
     * Hidden while a turn is working: the line describes the reply before this one, and
     * showing it under a spinner says the opposite of what is happening.
     */
    val standingLine: String?
        get() {
            val state = runner?.state
            if (state == "busy" || state == "starting") return null
            return standing?.text?.takeIf { it.isNotBlank() }
        }

    val isStalled: Boolean get() = runner?.stalled == true
}

@Serializable
data class SessionsResponse(
    val sessions: List<SessionSummary> = emptyList(),
    val ready: Boolean = false,
)
