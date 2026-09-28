package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * A follow-up an agent offered rather than did.
 *
 * The shape is split in two on the bridge and it is worth knowing which half is which,
 * because only one of them can be changed. **The offer is never stored** — it is the
 * `suggest_session` tool call sitting in a transcript, which is why deleting a session
 * deletes its tasks. **Only the decision is stored**, in a small JSON file
 * (`bridge/suggestions.js`), keyed session → toolUseId.
 *
 * `GET /api/suggestions` joins the two, and `docs/api.md:824` is explicit that
 * everything down to [cwd] is byte-identical to the `suggestion` transcript event —
 * *"same fields, same parse, so a client can draw a row and an event with one code
 * path"*. [SuggestionEvent.toTask] is that code path here.
 */
@Serializable
data class TaskSession(
    val title: String? = null,
    val projectName: String? = null,
    val projectCwd: String? = null,
    val worktree: Worktree? = null,
    val test: Boolean = false,
)

object TaskStatus {
    const val OPEN = "open"
    const val STARTED = "started"
    const val DISMISSED = "dismissed"

    /** The order the filter chips are drawn in, and the only three the bridge accepts. */
    val ALL = listOf(OPEN, STARTED, DISMISSED)
}

@Serializable
data class SuggestedTask(
    /** The `toolUseId` — `toolu_…`. The path parameter for every decision. */
    val id: String,
    /**
     * The session that *raised* the offer, and therefore the id in
     * `POST /api/sessions/:id/suggestions/:toolUseId` — never the session a decision
     * started. Absent on a row lifted off a transcript tail, where the open
     * conversation is the answer.
     */
    val sessionId: String? = null,
    /** ISO-8601, unlike a draft's epoch-millis timestamps. */
    val ts: String? = null,
    val title: String? = null,
    val why: String? = null,
    val prompt: String = "",
    val cwd: String? = null,
    /** [TaskStatus.OPEN] is the *absence* of a stored decision, not a stored value. */
    val status: String = TaskStatus.OPEN,
    val startedId: String? = null,
    val at: Long = 0,
    /**
     * A task from an archived session is still returned. Dismissing is already the
     * gesture for "not this"; if archiving hid tasks there would be two ways to
     * dismiss, one of them invisible.
     */
    val archived: Boolean = false,
    val session: TaskSession? = null,
) {
    val isOpen: Boolean get() = status == TaskStatus.OPEN

    /** `title` is frequently absent; the desktop falls back to the first line. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Suggested task"

    /** Worktree beats project name, as everywhere else in this app. */
    val place: String?
        get() = session?.worktree?.name ?: session?.projectName
}

@Serializable
data class SuggestionsResponse(
    val suggestions: List<SuggestedTask> = emptyList(),
    val ready: Boolean = false,
)

/**
 * `{status}` of `started` or `dismissed` — **or an empty object to undo**.
 *
 * `AppJson` has `explicitNulls = false`, so the no-argument constructor encodes as `{}`
 * rather than `{"status": null}`, which is exactly what `docs/api.md:1246` means by
 * *"absent to undo"*. The live bridge happens to accept a literal null too, but that is
 * not what the contract promises, and this costs nothing to get right.
 */
@Serializable
data class SuggestionActRequest(
    val status: String? = null,
    val startedId: String? = null,
)

/** `GET /api/sessions/:id/suggestions` — the decisions alone, keyed by `toolUseId`. */
@Serializable
data class SessionSuggestions(
    val sessionId: String? = null,
    val suggestions: Map<String, SuggestionDecision> = emptyMap(),
)
