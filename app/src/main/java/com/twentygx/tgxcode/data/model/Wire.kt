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
    /**
     * With `?turns=K`: the whole conversation's rail, in file order, whatever was
     * loaded. Empty from a bridge older than turn windows, which answers the whole
     * transcript instead.
     */
    val turns: List<TurnMark> = emptyList(),
    val window: TranscriptWindow? = null,
)

/**
 * One entry of the turn rail: a turn of yours, or a plan or question inside one.
 *
 * [offset] is a byte position a window may start at — and for a plan or question it is
 * the *turn's*, not its own line's, so no window ever starts halfway through a turn.
 * [id] is the id the same moment has as an event, which is how a client tells what it
 * has loaded.
 */
@Serializable
data class TurnMark(
    /** `turn`, `plan` or `question`. */
    val kind: String = "turn",
    val id: String = "",
    val offset: Long = 0,
    val ts: String? = null,
    /** Turns only: the text, clipped to 460 characters. */
    val text: String? = null,
    val command: SlashCommand? = null,
    /** Plans and questions: `pending`, `ok` or `error`. */
    val status: String? = null,
) {
    val isTurn: Boolean get() = kind == "turn"

    fun label(): String = when (kind) {
        "plan" -> "Plan"
        "question" -> "Question"
        else -> command?.render()
            ?: text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: "(no text)"
    }
}

/** Where the loaded window begins: a byte [start] (0 for everything) and its first turn's index. */
@Serializable
data class TranscriptWindow(val start: Long = 0, val startTurn: Int = 0)

/** `GET /api/sessions/:id/range`: the events in bytes `[from, to)`. */
@Serializable
data class RangeResponse(
    val events: List<TranscriptEvent> = emptyList(),
    val from: Long = 0,
    val to: Long = 0,
)

/**
 * `GET /api/sessions/:id/subagent?toolUseId=&offset=` — one subagent's transcript, from
 * a byte offset that follows the same contract as `/since`.
 *
 * Undocumented in `docs/api.md`; read from `SessionIndex.subagent` in
 * `bridge/sessions.js`.
 */
@Serializable
data class SubagentResponse(
    val toolUseId: String? = null,
    val agentId: String? = null,
    val agentType: String? = null,
    val description: String? = null,
    val events: List<TranscriptEvent> = emptyList(),
    val offset: Long = 0,
    val reset: Boolean = false,
)

/**
 * The `agent` field of `POST /api/subscribe` — an **object**, `{toolUseId, offset}`,
 * not the agent's id. Follows that subagent's file while the session stays followed.
 */
@Serializable
data class AgentFollow(val toolUseId: String, val offset: Long = 0)

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
    val agent: AgentFollow? = null,
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
    /** Absent is `inherit` — no `--effort` flag. */
    val effort: String? = null,
    val permissionMode: String? = null,
    /** Keeps it out of the everyday window. Use it for anything exploratory. */
    val test: Boolean = false,
    /** Uploaded through `POST /api/attachments?cwd=` first; [prompt] may then be empty. */
    val attachments: List<AttachmentRef>? = null,
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
    /** Absent and null both mean "as you were" — there is no asking a live process to inherit. */
    val model: String? = null,
    /**
     * **The one field where absent and null differ**, so it is a string that is always
     * sent: a level, or `""` for `inherit`, which the bridge reads as null and honours
     * by replacing a process that has a level. Leaving it out would keep whatever the
     * live process has, which is what a client *without* a selector should do — and
     * this one has a selector.
     */
    val effort: String? = null,
    val fork: Boolean? = null,
    /**
     * Edit and branch: the `id` of one of your own `user` events — the turn itself,
     * never its parent. Only accepted with [fork]; the copy keeps everything before
     * that turn and [text] takes its place.
     */
    val fromUuid: String? = null,
    /**
     * Files already uploaded to this session, at most five. With at least one, [text]
     * may be empty. The bridge keeps only each path's basename and re-derives the
     * directory, so a path handed back for the wrong session is dropped, not obeyed.
     */
    val attachments: List<AttachmentRef>? = null,
)

/**
 * A file the bridge has already written into `attached_assets/`, as a send, a create
 * or a scheduled message names it. Only [path] is read; the rest rides along so a
 * later read of the row has something to show.
 */
@Serializable
data class AttachmentRef(
    val path: String,
    val relPath: String? = null,
    val mediaType: String? = null,
) {
    /** What a chip or a queue row calls it. */
    val name: String get() = path.substringAfterLast('/')
}

/**
 * What an upload answers. [name] can differ from the one sent: the bridge never
 * overwrites, so `shot.png` comes back as `shot-2.png` with [renamed] set. [mediaType]
 * is sniffed from the bytes, not taken from the `Content-Type` the app sent.
 */
@Serializable
data class UploadedAttachment(
    val ok: Boolean = false,
    val name: String,
    val renamed: Boolean = false,
    val path: String,
    val relPath: String? = null,
    val bytes: Long = 0,
    val mediaType: String? = null,
) {
    fun ref(): AttachmentRef = AttachmentRef(path = path, relPath = relPath, mediaType = mediaType)
}

/** `hard` is SIGTERM then SIGKILL — what a stalled turn gets, since it is the least likely to answer an interrupt. */
@Serializable
data class StopRequest(val hard: Boolean? = null)

/**
 * `POST /api/sessions/:id/flags`. Every field optional; only what is sent changes.
 * [title] `""` clears a name given here, and the session shows its transcript's
 * title again.
 */
@Serializable
data class FlagsRequest(
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val title: String? = null,
)

/** [title] is the name the session now *shows*, not the flag — after a clear it is the transcript's again. */
@Serializable
data class FlagsResponse(
    val ok: Boolean = false,
    val sessionId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val test: Boolean = false,
    val title: String? = null,
    val titleSource: String? = null,
)

@Serializable
data class QueueReorderRequest(val ids: List<String>)

/**
 * What every queue write answers. Dropping one is `{ok, removed, status}`; the status
 * is the whole runner, and the queue chips redraw from it.
 */
@Serializable
data class QueueWriteResponse(
    val ok: Boolean = false,
    val status: RunnerStatus? = null,
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
