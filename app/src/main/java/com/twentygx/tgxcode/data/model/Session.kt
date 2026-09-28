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

/** The reduced runner shape carried on an overview card — not the full [RunnerStatus]. */
@Serializable
data class CardRunner(
    val state: String? = null,
    val activity: String? = null,
    val queued: Int = 0,
    val busySince: Long? = null,
    val error: String? = null,
    val errorKind: String? = null,
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
}

@Serializable
data class SessionsResponse(
    val sessions: List<SessionSummary> = emptyList(),
    val ready: Boolean = false,
)
