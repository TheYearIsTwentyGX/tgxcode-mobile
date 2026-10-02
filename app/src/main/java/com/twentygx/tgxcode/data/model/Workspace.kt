package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- changes

/**
 * `GET /api/sessions/:id/changes` — what a session changed, in the two ways that has an
 * answer, drawn as two lists and never reconciled: [edits] is the transcript's (what the
 * conversation touched, committed or not), [git] is the directory's (the tree as it
 * stands, whoever changed it).
 */
@Serializable
data class Changes(
    val dir: String? = null,
    val git: GitTree? = null,
    val edits: List<EditedFile> = emptyList(),
    val agents: AgentEdits? = null,
)

@Serializable
data class AgentEdits(val total: Int = 0, val edited: Int = 0)

/** `{ok: false}` is an answer, not an error — [reason] says which. */
@Serializable
data class GitTree(
    val ok: Boolean = false,
    /** `no-directory`, `not-a-repo`, `left-behind` or `status-failed`. */
    val reason: String? = null,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val files: Int = 0,
    val sample: List<GitFile> = emptyList(),
    val truncated: Int = 0,
)

@Serializable
data class GitFile(
    /** Relative to the repository root — what `/diff` takes. */
    val path: String,
    /** The porcelain-v2 XY code; `?` for untracked. */
    val status: String? = null,
    val added: Int? = null,
    val deleted: Int? = null,
    val binary: Boolean = false,
)

@Serializable
data class EditedFile(
    val path: String,
    /** Relative to the repository root inside one, absolute outside — `/diff` takes either. */
    val relPath: String? = null,
    val added: Int = 0,
    val deleted: Int = 0,
    val edits: Int = 0,
    /** The first edit's tool call, to jump to where the file started changing. */
    val toolId: String? = null,
)

/** `GET /api/sessions/:id/diff` — failures are 200s with `ok: false` and a [reason]. */
@Serializable
data class FileDiff(
    val ok: Boolean = false,
    val path: String? = null,
    val reason: String? = null,
    val error: String? = null,
    val added: Int? = null,
    val deleted: Int? = null,
    val binary: Boolean = false,
    val diff: String = "",
    /** Bytes left out past the 2 MB cap — a count, not a flag. */
    val truncated: Long = 0,
)

// ------------------------------------------------------------- scratchpad

@Serializable
data class Scratchpad(
    val dirs: List<ScratchDir> = emptyList(),
    /** Newest first. */
    val files: List<ScratchFile> = emptyList(),
    val truncated: Boolean = false,
)

/** One scratchpad. A session that entered a worktree has one per worktree; [where] names it. */
@Serializable
data class ScratchDir(val key: String, val path: String? = null, val where: String? = null)

@Serializable
data class ScratchFile(
    /** A [ScratchDir.key]. */
    val dir: String,
    /** Relative to that directory, `/`-separated. */
    val path: String,
    val size: Long = 0,
    val mtimeMs: Long = 0,
)

@Serializable
data class ScratchFileContent(
    val ok: Boolean = false,
    val reason: String? = null,
    val binary: Boolean = false,
    val truncated: Boolean = false,
    val size: Long = 0,
    val text: String = "",
)

// --------------------------------------------------------------- later

/**
 * A message written now and delivered to a session that already exists, at a time
 * picked — a `POST …/send` held back. `GET /api/later`, and `later-changed`.
 */
@Serializable
data class LaterMessage(
    val id: String,
    val sessionId: String,
    val text: String = "",
    val model: String? = null,
    val effort: String? = null,
    /** Never absent: stored per message, and required on create. */
    val permissionMode: String = "auto",
    /** Epoch millis — when it is due. */
    val at: Long = 0,
    /** `pending`, `delivering`, `sent`, `missed`, `failed`. */
    val state: String = "pending",
    /** A pending row past its window. Only the bridge can work this out. */
    val late: Boolean = false,
    val sentAt: Long? = null,
    val error: String? = null,
) {
    val isPending: Boolean get() = state == "pending"
}

@Serializable
data class LaterCounts(val total: Int = 0, val pending: Int = 0)

@Serializable
data class LaterPayload(
    val at: Long? = null,
    /** Soonest first. */
    val messages: List<LaterMessage> = emptyList(),
    val counts: LaterCounts? = null,
)

@Serializable
data class LaterResponse(val message: LaterMessage)

/**
 * `POST /api/sessions/:id/later`. [permissionMode] is required: `auto` is the one mode
 * that cannot work unattended, so the bridge 400s an absent one rather than defaulting.
 */
@Serializable
data class LaterCreate(
    val text: String,
    val permissionMode: String,
    val at: Long,
    val model: String? = null,
    val effort: String? = null,
)

/** A genuine partial: only what is sent changes. */
@Serializable
data class LaterPatch(
    val text: String? = null,
    val at: Long? = null,
)

// --------------------------------------------------------------- snippets

/** `GET /api/snippets` and `snippets-changed` — always unfiltered on the event. */
@Serializable
data class SnippetsPayload(
    val at: Long? = null,
    /** In the order to draw them; the bridge decides it so no two surfaces disagree. */
    val snippets: List<Snippet> = emptyList(),
    val groups: List<SnippetGroup> = emptyList(),
)

@Serializable
data class Snippet(
    val id: String,
    val title: String = "",
    /** **Not trimmed** — with `append` or `cursor` the whitespace is part of what it means. */
    val body: String = "",
    val hint: String? = null,
    /** May name a group not in the list: draw it ungrouped and leave the field alone. */
    val groupId: String? = null,
    val params: List<SnippetParam> = emptyList(),
    /** `overwrite`, `append` or `cursor`. */
    val insert: String = "overwrite",
    val autoSubmit: Boolean = false,
    /** Null is *inherit* — leave the selector where it is. Not `auto`. */
    val permissionMode: String? = null,
    val pinned: Boolean = false,
    val order: Int? = null,
    /** Absolute paths; empty is everywhere. A prefix match at a path boundary. */
    val projects: List<String> = emptyList(),
    val undeclared: List<String> = emptyList(),
    val unused: List<String> = emptyList(),
)

@Serializable
data class SnippetParam(
    /** `[A-Za-z_]\w*`, and its identity: `{{name}}` in the body refers to it. */
    val name: String,
    val label: String? = null,
    /** `text`, `integer`, `decimal`, `date`, `time`, `datetime` — an unknown one reads as text. */
    val type: String = "text",
    val required: Boolean = false,
    /** Always a string whatever the type, and untrimmed. */
    val default: String? = null,
) {
    val displayLabel: String get() = label?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class SnippetGroup(
    val id: String,
    val name: String = "",
    /** `#rgb` or `#rrggbb`, or null. */
    val accent: String? = null,
    val order: Int? = null,
)

@Serializable
data class SnippetResponse(val snippet: Snippet)

@Serializable
data class SnippetGroupBody(val name: String? = null, val accent: String? = null)

@Serializable
data class SnippetGroupResponse(val group: SnippetGroup)

/** One call for an up/down move — a swap as two PATCHes has a moment where both share a number. */
@Serializable
data class SnippetReorder(val snippets: List<String>? = null, val groups: List<String>? = null)

// -------------------------------------------------------------- schedules

/** `GET /api/schedules` and `schedules-changed`. */
@Serializable
data class SchedulesPayload(
    val at: Long? = null,
    /** Newest `updatedAt` first — and a run bumps it, so the order moves on its own. */
    val schedules: List<Schedule> = emptyList(),
)

@Serializable
data class Schedule(
    val id: String,
    val enabled: Boolean = false,
    /** Null means derive it from the prompt's first line. */
    val title: String? = null,
    val cwd: String = "",
    val projectName: String? = null,
    val prompt: String = "",
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String = "auto",
    val test: Boolean = false,
    val cron: String = "",
    val once: Boolean = false,
    /** The bridge's own English for [cron]; safe to render directly. */
    val cronText: String? = null,
    val nextRunAt: Long? = null,
    /** Will not fire again — the one field that tells *finished* from *paused*. */
    val spent: Boolean = false,
    val gate: ScheduleGate? = null,
    val reviewsInFlight: Int = 0,
    val lastFiredAt: Long? = null,
    val lastSessionId: String? = null,
    /** `BLOCK`, `CONCERNS`, `CLEAN`, `error`, `done` — and `done` is the ordinary case. */
    val lastOutcome: String? = null,
    /** Why the last slot passed without a session. Null is not the same as healthy. */
    val lastSkipReason: String? = null,
    val lastError: String? = null,
    val runs: Int = 0,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Untitled schedule"
}

/** `git-commits` (with [ref], [fetch]) or `open-prs` (with [includeDrafts], [post]). */
@Serializable
data class ScheduleGate(
    val kind: String,
    val ref: String? = null,
    val fetch: Boolean? = null,
    val includeDrafts: Boolean? = null,
    val post: Boolean? = null,
)

@Serializable
data class ScheduleResponse(val schedule: Schedule)

/** `sessionIds` is the real answer — a PR gate starts one per pull request. */
@Serializable
data class ScheduleRunResponse(
    val sessionId: String? = null,
    val sessionIds: List<String> = emptyList(),
    val deferred: Int = 0,
)

@Serializable
data class CronDescription(
    val cron: String? = null,
    val text: String? = null,
    val next: Long? = null,
)

// ---------------------------------------------------------- slash commands

@Serializable
data class SlashCommandsResponse(
    val cwd: String? = null,
    val commands: List<SlashCommandInfo> = emptyList(),
)

@Serializable
data class SlashCommandInfo(
    /** No leading slash on the wire — the composer adds one. */
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
)
