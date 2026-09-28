package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * What a session left on GitHub.
 *
 * Three routes answer three different questions and they go stale on different
 * clocks, which is why they are three routes rather than one:
 *
 *  - **Which** pull requests a session raised is history, read out of the transcript.
 *    It rides free on the session summary as [SessionSummary.prs].
 *  - **What became of them, per session**, is `GET /api/prs` — one [PrAggregate] for a
 *    list that wants a glyph per row and cannot afford a request per row.
 *  - **What became of each one** is `GET /api/sessions/:id/prs`, for the surface that
 *    draws a chip per PR rather than one per session.
 *
 * None of them shells out to `gh`: a refresher in the bridge keeps a snapshot and
 * every route reads it in memory.
 */

/** A PR the session raised, in the order it raised them. Free on the summary. */
@Serializable
data class PrRef(
    val number: Int = 0,
    val url: String = "",
    val repo: String? = null,
)

/**
 * Whether GitHub could be reached — and **the only thing that says so**.
 *
 * A `status` of `unknown` does not: it also covers a PR the bridge simply has not
 * resolved yet, which on a cold start is every merged PR on the machine for one pass.
 * Read [error] for the wording and fall back to something that does not blame the
 * network.
 */
@Serializable
data class GhReach(
    val ok: Boolean = true,
    val error: String? = null,
)

/**
 * One session's whole set of pull requests, folded to the one that most needs doing
 * something about.
 *
 * [status] is the bridge's own answer, ranked by `ATTENTION_ORDER` in
 * `bridge/pulls.js`. **It is not re-derived here and must not be.** The bridge keeps
 * two different precedence orders — settled states last for a session's whole set,
 * settled states first for a single PR — and a client that assumes the wrong one
 * draws the wrong glyph and reports no error.
 */
@Serializable
data class PrAggregate(
    val status: String = "unknown",
    /** The winning PR's own label, in words. */
    val label: String? = null,
    val total: Int = 0,
    /** `{status: n}` over all of them; only the statuses actually present appear. */
    val counts: Map<String, Int> = emptyMap(),
)

/**
 * `GET /api/prs`, and the payload of the `prs-changed` event byte for byte.
 *
 * [sessions] is keyed by session id, and a session with no pull requests is **absent
 * from it rather than null** — the client already knows which those are from `prs` on
 * the summary. So absence means "nobody has looked these up yet", not "there are
 * none", and those two must not be drawn the same way.
 *
 * [checkedAt] is the most recent moment any repository was successfully listed, and
 * it therefore **moves on every read**. Never include it in an equality test.
 */
@Serializable
data class PrsPayload(
    val sessions: Map<String, PrAggregate> = emptyMap(),
    val gh: GhReach = GhReach(),
    val checkedAt: String? = null,
)

/**
 * One pull request off `GET /api/sessions/:id/prs`.
 *
 * [status] is resolved for this PR alone, under the *other* precedence order — settled
 * states first, because for a merged PR nothing else is worth saying. [detail] is the
 * lines the one status had to leave out ("2 of 5 checks failed", "review required").
 */
@Serializable
data class PrDetail(
    val number: Int = 0,
    val url: String = "",
    val repo: String? = null,
    val title: String? = null,
    val branch: String? = null,
    /** ISO-8601, like every other timestamp on the wire here. */
    val updatedAt: String? = null,
    val status: String = "unknown",
    val label: String? = null,
    val detail: List<String> = emptyList(),
)

/** One entry per PR in [SessionSummary.prs], **in the same order**. Do not re-sort. */
@Serializable
data class SessionPrsResponse(
    val prs: List<PrDetail> = emptyList(),
    val gh: GhReach = GhReach(),
    val checkedAt: String? = null,
)
