package com.twentygx.tgxcode.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One rendered event from a transcript.
 *
 * The invariant behind all of this, from `docs/api.md`: *content comes from the
 * transcript, never from the process.* Every one of these was read back off Claude
 * Code's own `.jsonl` on disk, which is why a session started in a terminal renders
 * identically to one started from the app — and why there is no de-duplication
 * problem to solve beyond keying on [id].
 *
 * `ts` is an ISO-8601 string on the wire (verified against the live bridge), unlike
 * `askedAt`/`busySince` which are epoch millis. Kept as the raw string because
 * nothing in the phone UI actually shows an event timestamp — `.ev-time` is
 * `display: none` in mobile.css.
 */
@Serializable
sealed class TranscriptEvent {
    abstract val id: String
    abstract val ts: String?
}

/**
 * A slash-command invocation.
 *
 * `bridge/transcript.js:590` parses these out of the XML-ish tags inside a user
 * message and emits `{name, args}` — an **object**, not a string, and [name]
 * already carries its leading slash.
 *
 * `docs/api.md` documents `command` on a user event without saying it is an object,
 * and `web/mobile.js:715` interpolates it straight into a template string — so a
 * slash command renders there as `/[object Object]`. Worth reporting upstream; not
 * worth reproducing.
 */
@Serializable
data class SlashCommand(
    val name: String? = null,
    val args: String? = null,
) {
    /** `/compact` or `/compact keep the plan` — never a double slash. */
    fun render(): String? {
        val label = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withSlash = if (label.startsWith("/")) label else "/$label"
        val trailing = args?.trim()?.takeIf { it.isNotBlank() }
        return if (trailing == null) withSlash else "$withSlash $trailing"
    }
}

@Serializable
@SerialName("user")
data class UserEvent(
    override val id: String,
    override val ts: String? = null,
    val text: String? = null,
    /** What the model was sent inline. Usually also in [files], as the file on disk. */
    val images: List<ImageRef> = emptyList(),
    /**
     * Read back off the `Attached files (read them):` note the bridge appends, which it
     * strips from [text]. Every attachment, image or not.
     */
    val files: List<FileRef> = emptyList(),
    val command: SlashCommand? = null,
    /**
     * Claude Code's own `origin.kind`: `human`, `peer`, or an agent type. Not a closed
     * set, so anything other than `human` reads as "not the person" rather than being
     * switched on.
     */
    val origin: String? = null,
    /**
     * The `parentUuid` of the turn's entry — what a `fromUuid` send branches from.
     *
     * Null on the first turn of a conversation, the one turn that cannot be branched
     * from, and absent from a bridge older than branching. Both read as null here and
     * both mean the same thing to a client: no branch offered.
     */
    val parent: String? = null,
) : TranscriptEvent() {

    /** Whether this turn came from the person, rather than a peer or an agent. */
    val fromHuman: Boolean get() = origin == null || origin == "human"

    /**
     * What to actually show.
     *
     * `bridge/transcript.js`'s `stripEnvelope` removes `<system-reminder>` and
     * `<local-command-stdout>` but *not* the `<command-*>` tags — so for a slash
     * command the entire `text` is bookkeeping the bridge already parsed into
     * [command], and rendering it raw shows the user their own XML. Strip the same
     * family of tags here and fall back to the parsed command.
     *
     * This is not inventing content: it is finishing a strip the bridge intended.
     * The upstream fix belongs in `stripEnvelope`.
     */
    fun displayText(): String {
        val stripped = text.orEmpty()
            .replace(COMMAND_TAGS, "")
            .trim()
        val prefix = origin?.takeIf { it.isNotBlank() && it != "human" }?.let { "[$it] " }.orEmpty()
        val commandLine = command?.render()
        return when {
            stripped.isNotEmpty() && commandLine != null -> "$prefix$commandLine $stripped"
            stripped.isNotEmpty() -> "$prefix$stripped"
            commandLine != null -> "$prefix$commandLine"
            else -> prefix.trim()
        }.trim()
    }

    private companion object {
        val COMMAND_TAGS =
            Regex("<command-(message|name|args)>[\\s\\S]*?</command-\\1>")
    }
}

@Serializable
@SerialName("assistant")
data class AssistantEvent(
    override val id: String,
    override val ts: String? = null,
    /** Markdown. */
    val text: String? = null,
    val model: String? = null,
) : TranscriptEvent()

@Serializable
@SerialName("thinking")
data class ThinkingEvent(
    override val id: String,
    override val ts: String? = null,
    /** Rendered as plain text, never markdown — matching mobile.js. */
    val text: String? = null,
) : TranscriptEvent()

/**
 * A tool call.
 *
 * **Arrives twice**: once as the call, with no [status] and no [result], and again
 * later with both. Anything keyed on first sight shows a permanently spinning tool.
 * Render idempotently by [id] and patch in place.
 */
/**
 * A subagent a Task/Agent tool call spawned.
 *
 * `docs/api.md` lists `agent` on a tool event as though it were a string; it is this
 * object.
 */
@Serializable
data class AgentRef(
    val agentId: String? = null,
    val agentType: String? = null,
    val description: String? = null,
    val spawnDepth: Int? = null,
    val hasTranscript: Boolean = false,
    val model: String? = null,
    val isAsync: Boolean = false,
    val durationMs: Long? = null,
    val tokens: Long? = null,
    val toolUses: Int? = null,
)

@Serializable
@SerialName("tool")
data class ToolEvent(
    override val id: String,
    override val ts: String? = null,
    val name: String? = null,
    /** Free-form: every tool has a different shape. Kept as JSON, read structurally. */
    val input: JsonObject = JsonObject(emptyMap()),
    /** `ok`, `error`, or absent while still running. */
    val status: String? = null,
    val result: ToolResult? = null,
    val agent: AgentRef? = null,
    /** Set when the output was too large and went to a file instead. */
    val persistedPath: String? = null,
    val durationMs: Long? = null,
) : TranscriptEvent() {
    /**
     * `pending` while running, per `docs/api.md` — **not** absent, as it reads at first
     * glance. Absent is kept as running too, for the call half of a live tail that has
     * not been stamped yet.
     */
    val isRunning: Boolean get() = status == null || status == "pending"
    val isError: Boolean get() = status == "error"

    /** Fold in a result that arrived separately on a live tail. */
    fun withResult(patch: ToolResultEvent): ToolEvent = copy(
        status = patch.status ?: status,
        result = patch.result ?: result,
        agent = patch.agent ?: agent,
        persistedPath = patch.persistedPath ?: persistedPath,
    )
}

/**
 * A tool's result, arriving separately from the call.
 *
 * **This is how every result arrives on a live tail**, and it is easy to miss. A full
 * transcript read merges the result into the `tool` event, so a session opened from
 * scratch looks complete — but while you are watching one, the call is in an earlier
 * chunk and `bridge/transcript.js:485` emits this instead:
 *
 * > "The call itself was in an earlier chunk — this happens on every live tail.
 * > Emit a patch the client applies to the tool block it already rendered."
 *
 * `web/app.js:970` handles it. **`web/mobile.js` does not** — so on the phone's web
 * client, a tool in a live session shows its spinner and never resolves. Worth
 * reporting upstream; definitely not worth reproducing.
 *
 * Its own [id] is `<toolUseId>:result`, so it can never be mistaken for the call; the
 * call it patches is [toolId].
 */
@Serializable
@SerialName("tool-result")
data class ToolResultEvent(
    override val id: String,
    override val ts: String? = null,
    val toolId: String? = null,
    val status: String? = null,
    val resultTs: String? = null,
    val result: ToolResult? = null,
    val agent: AgentRef? = null,
    val persistedPath: String? = null,
) : TranscriptEvent()

/**
 * One hunk of a diff.
 *
 * `docs/api.md` lists `result.patch` alongside `text` and `stdout` as though it were
 * another string. It is not — it is a structured diff, an array of these, and
 * [lines] already carry their `+`/`-`/space prefixes. Worth reporting upstream.
 */
@Serializable
data class PatchHunk(
    val oldStart: Int = 0,
    val oldLines: Int = 0,
    val newStart: Int = 0,
    val newLines: Int = 0,
    val lines: List<String> = emptyList(),
) {
    /** Back to the unified-diff header everyone can read at a glance. */
    fun render(): String =
        buildString {
            append("@@ -$oldStart,$oldLines +$newStart,$newLines @@\n")
            append(lines.joinToString("\n"))
        }
}

@Serializable
data class ToolResult(
    val text: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val patch: List<PatchHunk>? = null,
    val filePath: String? = null,
    val backgroundTaskId: String? = null,
    val interrupted: Boolean = false,
) {
    fun patchText(): String? =
        patch?.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.render() }
}

@Serializable
@SerialName("system")
data class SystemEvent(
    override val id: String,
    override val ts: String? = null,
    val subtype: String? = null,
    val isError: Boolean = false,
    /** Rendered with *inline* markdown only — a line starting with `#` is not a heading. */
    val text: String? = null,
) : TranscriptEvent()

@Serializable
@SerialName("agent-done")
data class AgentDoneEvent(
    override val id: String,
    override val ts: String? = null,
    val taskId: String? = null,
    val toolUseId: String? = null,
    val status: String? = null,
    val summary: String? = null,
    /**
     * Whether there is a subagent transcript to open. A background shell reports
     * through the same notification and its `toolUseId` names a `Bash` call, which has
     * nothing to open — so only offer a way in when this is true.
     */
    val hasTranscript: Boolean = false,
) : TranscriptEvent()

/**
 * Another session messaged this one over the peer socket. [fromName] is the peer's
 * name, which is also its address.
 */
@Serializable
@SerialName("peer-message")
data class PeerMessageEvent(
    override val id: String,
    override val ts: String? = null,
    val from: String? = null,
    val fromName: String? = null,
    val text: String? = null,
) : TranscriptEvent()

/** Work another session handed this one — which is what woke it. */
@Serializable
@SerialName("handoff")
data class HandoffEvent(
    override val id: String,
    override val ts: String? = null,
    val text: String? = null,
    /** The sending session's id. */
    val from: String? = null,
    val fromTitle: String? = null,
    val fromProject: String? = null,
    val title: String? = null,
) : TranscriptEvent()

@Serializable
@SerialName("compact")
data class CompactEvent(
    override val id: String,
    override val ts: String? = null,
    val text: String? = null,
) : TranscriptEvent()

/**
 * A kind this build does not know about. Renders as nothing.
 *
 * Without this, one unrecognised event kind from a newer bridge would throw and take
 * the whole `tail` batch with it.
 */
@Serializable
@SerialName("__unknown__")
data class UnknownEvent(
    override val id: String = "",
    override val ts: String? = null,
) : TranscriptEvent()

/**
 * A follow-up an agent offered rather than did — the `suggest_session` tool call.
 *
 * Its fields are byte-identical to a `GET /api/suggestions` row down to [cwd]
 * (`docs/api.md:824`), which is what [toTask] exists to exploit: one card renderer
 * serves both the Tasks tab and an inline offer in a conversation.
 *
 * The desktop lifts these out of the event stream entirely and draws them in a panel
 * beside the transcript; `web/mobile.js` renders them inline. This client does both —
 * inline here, and collected on the Tasks tab — because on a phone there is no room
 * for a panel beside anything.
 *
 * [sessionId] is **absent** here, unlike on a `/api/suggestions` row: an event knows
 * only the tail it arrived on. The open conversation's id is the answer, which is
 * `taskSessionId` in `web/app.js:2039`.
 */
@Serializable
@SerialName("suggestion")
data class SuggestionEvent(
    override val id: String,
    override val ts: String? = null,
    val prompt: String = "",
    val why: String? = null,
    val title: String? = null,
    val cwd: String? = null,
) : TranscriptEvent() {
    /**
     * @param sessionId the conversation this arrived on — the id every decision route
     *   takes, and the one thing the event cannot tell us itself.
     * @param status what was already decided, read from the session detail's
     *   `suggestions` map. Absent means [TaskStatus.OPEN].
     */
    fun toTask(sessionId: String?, decision: SuggestionDecision? = null): SuggestedTask =
        SuggestedTask(
            id = id,
            sessionId = sessionId,
            ts = ts,
            title = title,
            why = why,
            prompt = prompt,
            cwd = cwd,
            status = decision?.status ?: TaskStatus.OPEN,
            startedId = decision?.startedId,
            at = decision?.at ?: 0,
        )
}

/**
 * What was already done about one offer, from the `suggestions` map on
 * `GET /api/sessions/:id`.
 *
 * The offer itself is in `events`; this is only the decision, which is why the two are
 * carried separately and joined by `toolUseId`.
 */
@Serializable
data class SuggestionDecision(
    val status: String? = null,
    val startedId: String? = null,
    val at: Long = 0,
)

/** One inline image block. [dataUri] is null when the bridge could not read it back. */
@Serializable
data class ImageRef(
    val mediaType: String? = null,
    val dataUri: String? = null,
)

/** One attached file. [size] is a display string (`"412 KB"`), not a number. */
@Serializable
data class FileRef(
    val relPath: String? = null,
    val name: String? = null,
    val size: String? = null,
)
