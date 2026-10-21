package com.twentygx.tgxcode.data.model

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.toolDisplayName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class AskKind { Tool, Plan, Question, Unknown }

/**
 * Something a session is blocked on.
 *
 * **An ask does not expire.** There is no `expiresAt` and no deadline to count down;
 * [askedAt] exists so a client can say how *long* something has been blocked, which
 * is the number that matters when the person who should answer is not at the desk.
 * So the UI counts up, not down.
 *
 * The case that made expiry look necessary is handled earlier and more bluntly: an
 * ask arriving when no client is connected is denied immediately, because there is
 * nobody to ask. That is the whole reason this app has a foreground service.
 *
 * [sessionId] is *not* on the wire when this arrives via `runner.pendingPermission` —
 * only when it arrives as a `permission-request` event. It has to be stamped in on
 * the pending path.
 */
@Serializable
data class Ask(
    val requestId: String,
    val kind: String? = null,
    val sessionId: String? = null,
    val tool: String? = null,
    val displayName: String? = null,
    /** Free-form. The plan text and the question list both live in here. */
    val input: JsonObject = JsonObject(emptyMap()),
    val toolUseId: String? = null,
    val description: String? = null,
    val reason: String? = null,
    val blockedPath: String? = null,
    val agentId: String? = null,
    /** Epoch millis, unlike event timestamps. */
    val askedAt: Long? = null,
) {
    val askKind: AskKind
        get() = when (kind) {
            "tool" -> AskKind.Tool
            "plan" -> AskKind.Plan
            "question" -> AskKind.Question
            else -> AskKind.Unknown
        }

    /** The bridge's own friendly name when it sent one; the raw [tool] stays for matching. */
    val toolLabel: String get() = displayName ?: tool?.let(::toolDisplayName) ?: "a tool"

    /** The plan's markdown, for a `plan` ask. */
    val planMarkdown: String?
        get() = runCatching { input["plan"]?.jsonPrimitive?.content }.getOrNull()

    /**
     * The questions, for a `question` ask. A malformed list degrades to an empty form
     * rather than failing the whole event.
     */
    fun questions(): List<QuestionSpec> = runCatching {
        AppJson.decodeFromJsonElement(
            ListSerializer(QuestionSpec.serializer()),
            input["questions"] ?: JsonArray(emptyList()),
        )
    }.getOrDefault(emptyList())
}

@Serializable
data class QuestionSpec(
    val question: String,
    val header: String? = null,
    val multiSelect: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

/**
 * How an ask ended. `409` on answering means one of these already happened
 * elsewhere — an ordinary outcome, not an error to shout about.
 */
object AskOutcome {
    private val WORDS = mapOf(
        "allow" to "Allowed",
        "allow-always" to "Allowed for the session",
        "deny" to "Denied",
        "answered" to "Answered",
        "dismissed" to "Dismissed",
        "plan-approved" to "Plan approved",
        "plan-approved-note" to "Plan approved, with a note",
        "plan-rejected" to "Sent back for more planning",
        "auto-denied" to "Nobody answered, so it was denied",
        "superseded" to "Superseded",
        "stopped" to "Stopped",
        "cancelled" to "Withdrawn",
        "abandoned" to "Abandoned",
    )

    fun word(outcome: String?): String =
        WORDS[outcome] ?: outcome?.takeIf { it.isNotBlank() } ?: "Answered"
}
