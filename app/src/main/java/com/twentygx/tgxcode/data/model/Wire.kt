package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Health(
    val ok: Boolean = false,
    val app: String? = null,
    val version: String? = null,
    val port: Int? = null,
    val dev: Boolean = false,
    val ready: Boolean = false,
    val remote: Boolean = false,
    val authRequired: Boolean = true,
    val host: String? = null,
    /** Read this rather than hardcoding the list. */
    val permissionModes: List<String> = emptyList(),
    val sessions: Int = 0,
    val clients: Int = 0,
    val live: Int = 0,
    val busy: Int = 0,
) {
    /**
     * What a mode picker should offer.
     *
     * A remote caller is refused `bypassPermissions` and `dontAsk` with a 403, so
     * offering them is a button that always fails. The client only *agrees* with a
     * refusal the bridge already makes — it does not invent one.
     */
    fun offeredModes(): List<String> {
        val modes = permissionModes.ifEmpty { DEFAULT_MODES }
        return if (remote) modes.filterNot { it in REMOTE_REFUSED } else modes
    }

    companion object {
        val DEFAULT_MODES = listOf("auto", "acceptEdits", "plan", "manual")
        val REMOTE_REFUSED = setOf("bypassPermissions", "dontAsk")
    }
}

@Serializable
data class Truncated(val dropped: Int = 0, val total: Int = 0)

@Serializable
data class SessionDetail(
    val summary: SessionSummary,
    val events: List<TranscriptEvent> = emptyList(),
    /** A **byte position** in the transcript file, not an event count. Hold it. */
    val offset: Long = 0,
    val truncated: Truncated? = null,
    val runner: RunnerStatus? = null,
    /**
     * What was already done about each suggested follow-up, keyed by `toolUseId`.
     *
     * The offers themselves are `suggestion` events in [events]; this is only the
     * *decisions*, because the two live in different places on the bridge — an offer in
     * the transcript, a decision in its own store. A key that is absent means the offer
     * is still open.
     */
    val suggestions: Map<String, SuggestionDecision> = emptyMap(),
)

@Serializable
data class SinceResponse(
    val events: List<TranscriptEvent> = emptyList(),
    val offset: Long = 0,
    /** The transcript shrank — compacted or forked. Reload from scratch. */
    val reset: Boolean = false,
)

@Serializable
data class SubscribeRequest(
    val clientId: String,
    val sessionId: String? = null,
    val offset: Long = 0,
    val agent: String? = null,
    /**
     * Always true, on every screen. The tab badge is drawn from the board and has to
     * keep arriving while a conversation is open.
     */
    val overview: Boolean = true,
)

@Serializable
data class CreateSessionRequest(
    val cwd: String,
    val prompt: String,
    val model: String? = null,
    val permissionMode: String? = null,
    /** Keeps it out of the everyday window. Use it for anything exploratory. */
    val test: Boolean = false,
)

/**
 * Note [status]: `docs/api.md` writes this response as `{sessionId, status, test}`,
 * which reads as though `status` were a string. It is the **whole runner status
 * object** — `pool.create` returns `{sessionId, status: r.status()}`
 * (`bridge/runner.js:1193`). Modelling it as a String makes the POST succeed and the
 * *response decode* fail, which surfaces as a false "did not send".
 */
@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val status: RunnerStatus? = null,
    val test: Boolean = false,
)

/**
 * `permissionMode` is **not** optional here even though the field is nullable.
 *
 * An absent one normalises to `auto` server-side, which means omitting it does not
 * mean "leave it alone" — it means "set it to auto", and would quietly drop a
 * session out of `acceptEdits` on every message.
 */
@Serializable
data class SendRequest(
    val text: String,
    val permissionMode: String,
    val model: String? = null,
    val fork: Boolean? = null,
)

/**
 * [status] is the **whole runner status object**, not a string — the handler returns
 * `status: r.status()` (`bridge/server.js:844`). Same trap as
 * [CreateSessionResponse], and the more painful one: a String here makes every
 * successful send report "Did not send", and the composer then restores the text,
 * which is an invitation to send it twice.
 *
 * It is also free information — the runner state it carries is newer than whatever
 * the last `runner-status` event said, so it is worth applying rather than discarding.
 *
 * [queued] genuinely is a boolean here (`status.queue.some(...)`), unlike the integer
 * `queued` on a runner status. Two fields, one name, different types.
 */
@Serializable
data class SendResponse(
    val ok: Boolean = false,
    val id: String? = null,
    val cwd: String? = null,
    val fork: Boolean = false,
    val status: RunnerStatus? = null,
    val queued: Boolean = false,
)

@Serializable
data class StopResponse(
    val ok: Boolean = false,
    val how: String? = null,
    val dropped: List<String> = emptyList(),
)

/**
 * The one route that answers all three kinds of ask.
 *
 * [mode] is load-bearing on a **plan** approval: the session is *in* plan mode while
 * the card is up, so approving without changing it agrees to the work and then
 * blocks every edit in it.
 *
 * [answers] values are heterogeneous — a string for single-select, an array for
 * multi-select — which is why they are [JsonElement] and not [String]. Keys must be
 * the *exact* question text; the bridge drops keys that name no question, so an
 * approximation silently answers nothing.
 */
@Serializable
data class PermissionRequestBody(
    val requestId: String,
    val decision: String,
    val answers: Map<String, JsonElement>? = null,
    val feedback: String? = null,
    val mode: String? = null,
)

object Decision {
    const val ALLOW = "allow"
    const val ALLOW_ALWAYS = "allow-always"
    const val DENY = "deny"
}
