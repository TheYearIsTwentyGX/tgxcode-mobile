package com.twentygx.tgxcode.data.model

import com.twentygx.tgxcode.core.toolDisplayText
import com.twentygx.tgxcode.core.toolDisplayName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * An orchestrator session and everything it is carrying — the whole
 * `GET /api/sessions/:id/orchestrator` payload, which is also exactly what the
 * `orchestrator` event pushes, so a store holding one never has to refetch.
 *
 * A session that is not one answers `{orchestratorId, enabled: false}`, which decodes
 * to this with every list empty; [enabled] is the only field worth reading then.
 */
@Serializable
data class OrchestratorPayload(
    val orchestratorId: String,
    val enabled: Boolean = false,
    val settings: OrchestratorSettings = OrchestratorSettings(),
    val paused: OrchestratorPause? = null,
    val summary: OrchestratorSummary? = null,
    val workers: List<OrchestratorWorker> = emptyList(),
    val pendingSpawns: List<PendingSpawn> = emptyList(),
    val openTasks: List<OpenTask> = emptyList(),
    val unread: Int = 0,
    /** Newest first, at most 100 — a missing item is unknown, not never-happened. */
    val inbox: List<InboxItem> = emptyList(),
)

@Serializable
data class OrchestratorSettings(
    /** 1–10. */
    val maxRunning: Int = 3,
    /** Whether a worker gets a worktree of its own when nothing else decides. */
    val worktree: Boolean = true,
    val usageStop: UsageStop = UsageStop(),
)

/**
 * The usage cutoff. With [window] `all`, each window in [limits] has its own threshold
 * and a null one is left out; otherwise [percent] applies to [window] alone.
 */
@Serializable
data class UsageStop(
    val enabled: Boolean = true,
    val percent: Int = 90,
    val window: String = "five_hour",
    val limits: UsageLimits = UsageLimits(),
    val autoResume: Boolean = false,
    /** Who last set [autoResume]; null while it is still what the orchestrator began with. */
    val autoResumeBy: AutoResumeBy? = null,
) {
    companion object {
        val WINDOWS = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "all")

        fun windowLabel(window: String): String = when (window) {
            "five_hour" -> "5-hour"
            "seven_day" -> "weekly"
            "seven_day_opus" -> "weekly Opus"
            "seven_day_sonnet" -> "weekly Sonnet"
            "all" -> "any window"
            else -> window.replace('_', ' ')
        }
    }
}

@Serializable
data class UsageLimits(
    val five_hour: Int? = null,
    val seven_day: Int? = null,
    val seven_day_opus: Int? = null,
    val seven_day_sonnet: Int? = null,
) {
    fun of(window: String): Int? = when (window) {
        "five_hour" -> five_hour
        "seven_day" -> seven_day
        "seven_day_opus" -> seven_day_opus
        "seven_day_sonnet" -> seven_day_sonnet
        else -> null
    }

    fun with(window: String, value: Int?): UsageLimits = when (window) {
        "five_hour" -> copy(five_hour = value)
        "seven_day" -> copy(seven_day = value)
        "seven_day_opus" -> copy(seven_day_opus = value)
        "seven_day_sonnet" -> copy(seven_day_sonnet = value)
        else -> this
    }
}

@Serializable
data class AutoResumeBy(
    /** `user` or `agent` — the orchestrator's own `set_auto_resume`. */
    val by: String = "user",
    val at: Long = 0,
    val reason: String? = null,
)

/**
 * The usage cutoff stopped it. [resumeAt] is when auto-resume will lift it, null when
 * it will not by itself; [interrupted] are the sessions stopped mid-turn.
 */
@Serializable
data class OrchestratorPause(
    val reason: String = "",
    val at: Long = 0,
    val resumeAt: Long? = null,
    val by: Int? = null,
    val interrupted: List<String> = emptyList(),
)

/**
 * The orchestrator's brief for the user, from its `set_summary` tool. Any part may be
 * empty — an orchestrator that has not filled a field leaves it null or `[]`.
 */
@Serializable
data class OrchestratorSummary(
    /** Markdown, at most 1500 characters. */
    val text: String = "",
    val at: Long = 0,
    val goal: String? = null,
    val decisions: List<BriefDecision> = emptyList(),
    /** PR numbers in landing order; one may belong to no worker here. */
    val mergeOrder: List<Int> = emptyList(),
)

/**
 * A question for the user. [id] is a hash of [text], so it — and a recorded [answer] —
 * survives the orchestrator rewriting its brief as long as the question stays the same.
 */
@Serializable
data class BriefDecision(
    val id: String,
    val text: String = "",
    val options: List<String> = emptyList(),
    val workerId: String? = null,
    val at: Long = 0,
    val answer: String? = null,
    val answeredAt: Long? = null,
)

/** Where a worker or a queued spawn began, when it was a task the user handed over. */
@Serializable
data class TaskOrigin(
    /** `draft` or `dialog`. */
    val from: String? = null,
    val draftId: String? = null,
    val draftCreatedAt: Long? = null,
    val title: String? = null,
)

@Serializable
data class WorkerPr(
    val number: Int = 0,
    val url: String? = null,
    val repo: String? = null,
    val title: String? = null,
    /** The rail's PR words, or `unknown` until the bridge's cache has seen it. */
    val status: String = "unknown",
    val label: String? = null,
)

@Serializable
data class OrchestratorWorker(
    val id: String,
    val title: String? = null,
    val cwd: String? = null,
    val worktree: Worktree? = null,
    val spawnedAt: Long = 0,
    val closedAt: Long? = null,
    /** `idle`, `busy`, `starting`, `stopped` or `error`. */
    val state: String = "idle",
    /** `tool`, `plan` or `question` — what it is blocked on, if anything. */
    val pending: String? = null,
    val pr: WorkerPr? = null,
    val origin: TaskOrigin? = null,
) {
    val closed: Boolean get() = closedAt != null
}

@Serializable
data class PendingSpawn(
    val id: String,
    val title: String? = null,
    val at: Long = 0,
    val origin: TaskOrigin? = null,
)

/** A task handed over and not yet started, taken back or gone. Oldest first. */
@Serializable
data class OpenTask(
    val id: String,
    val at: Long = 0,
    /** `new` (the orchestrator has not read it) or `read`. */
    val status: String = "new",
    /** The first 8000 characters of the prompt. */
    val text: String = "",
    val cwd: String = "",
    val title: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String = "auto",
    val test: Boolean = false,
    val worktree: Worktree? = null,
    val origin: TaskOrigin? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: origin?.title?.takeIf { it.isNotBlank() }
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Untitled task"
}

@Serializable
data class InboxAsk(
    val kind: String? = null,
    val tool: String? = null,
    val displayName: String? = null,
    val input: JsonObject = JsonObject(emptyMap()),
) {
    /** As [Ask.toolLabel]: the bridge's name when it sent one, else the raw one made readable. */
    val toolLabel: String? get() = displayName?.let(::toolDisplayText) ?: tool?.let(::toolDisplayName)
}

/**
 * One inbox row. [kind] is `question`, `update` or `done` (a worker's own report),
 * `turn` (a turn's final message), `plan`, `permission` or `ask` (a worker's asks,
 * still pending on the worker itself), `note` (the bridge) or `task` (the user's).
 */
@Serializable
data class InboxItem(
    val id: String,
    val workerId: String? = null,
    val kind: String = "",
    val text: String = "",
    val at: Long = 0,
    val seq: Long = 0,
    /** `new`, `read`, `resolved` or `stale`. */
    val status: String = "new",
    val requestId: String? = null,
    val ask: InboxAsk? = null,
    val outcome: String? = null,
    val by: String? = null,
    val settledAt: Long? = null,
) {
    val open: Boolean get() = status == "new" || status == "read"
    val isAsk: Boolean get() = kind == "plan" || kind == "permission" || kind == "ask"
}

/** One row of `GET /api/orchestrators`, the list a task can be sent to. */
@Serializable
data class OrchestratorRow(
    val orchestratorId: String,
    val title: String? = null,
    val cwd: String? = null,
    val projectName: String? = null,
    val live: Boolean = false,
    val state: String = "stopped",
    val paused: OrchestratorRowPause? = null,
    val running: Int = 0,
    val maxRunning: Int = 0,
    val queued: Int = 0,
    val openTasks: Int = 0,
    val workers: Int = 0,
    val unread: Int = 0,
    val lastActivity: Long = 0,
    val test: Boolean = false,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled orchestrator"
}

@Serializable
data class OrchestratorRowPause(val reason: String = "", val resumeAt: Long? = null)

@Serializable
data class OrchestratorsResponse(val orchestrators: List<OrchestratorRow> = emptyList())

/** On a session summary marked as an orchestrator: unread items, open workers, paused. */
@Serializable
data class OrchestratorBadge(
    val inbox: Int = 0,
    val workers: Int = 0,
    val paused: Boolean = false,
)

/** On a session an orchestrator started. Its orchestrator may be gone; draw it plain then. */
@Serializable
data class WorkerBadge(
    val orchestratorId: String,
    val title: String? = null,
    val closed: Boolean = false,
)

// ------------------------------------------------------------------ the board

/**
 * On an orchestrator's board card: the workers it carries. [ids] are worker cards that
 * are elsewhere in the board's `sessions`; [quiet] have no card of their own at all.
 */
@Serializable
data class BoardWorkers(
    val ids: List<String> = emptyList(),
    val queued: List<PendingSpawn> = emptyList(),
    val idle: Int = 0,
    val finished: Int = 0,
    /** False when the orchestrator is on the board only because of its workers. */
    val own: Boolean = true,
    val paused: Boolean = false,
    val quiet: List<QuietWorker> = emptyList(),
)

@Serializable
data class QuietWorker(
    val id: String,
    val title: String? = null,
    /** `idle` (open) or `finished` (closed). */
    val state: String = "idle",
    val at: Long? = null,
    val standing: String? = null,
)

// ------------------------------------------------------------------- writes

/** `PUT /api/sessions/:id/orchestrator`. Only what is set is sent. */
@Serializable
data class OrchestratorPut(
    val enabled: Boolean? = null,
    val settings: SettingsPatch? = null,
)

@Serializable
data class SettingsPatch(
    val maxRunning: Int? = null,
    val worktree: Boolean? = null,
    val usageStop: UsageStopPatch? = null,
)

/**
 * A partial cutoff. [limits] is sent whole when sent: a `null` threshold in it is a
 * real value — "leave that window out" — so it is encoded with its nulls.
 */
@Serializable
data class UsageStopPatch(
    val enabled: Boolean? = null,
    val percent: Int? = null,
    val window: String? = null,
    val limits: JsonObject? = null,
    val autoResume: Boolean? = null,
)

@Serializable
data class DecisionAnswered(val text: String)

@Serializable
data class DecisionAnsweredResponse(val decision: BriefDecision? = null)

/** `POST …/orchestrator/tasks`: a stored draft, or a form's fields, or both. */
@Serializable
data class TaskFiling(
    val draftId: String? = null,
    val fields: TaskFields? = null,
)

@Serializable
data class TaskFields(
    val cwd: String,
    val prompt: String,
    val permissionMode: String,
    val test: Boolean,
    val model: String? = null,
    val effort: String? = null,
)

@Serializable
data class TaskFiled(
    val ok: Boolean = false,
    val paused: Boolean = false,
    val draftRemoved: Boolean = false,
)

@Serializable
data class TaskWithdrawn(val ok: Boolean = false, val draft: Draft? = null)

@Serializable
data class InboxDismissed(val ok: Boolean = false)
