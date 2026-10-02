package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

enum class RunnerState { Stopped, Starting, Idle, Busy, Error, Unknown }

@Serializable
data class QueuedMessage(
    val id: String? = null,
    val text: String? = null,
    val at: Long? = null,
    /**
     * Already handed to the CLI, to be folded into the running turn at its next tool
     * step. It keeps its place at the front: `reorder` ignores its id, and dropping it
     * can lose the race and answer `409`.
     */
    val handed: Boolean = false,
)

/**
 * A model/effort change the bridge is holding until background work finishes, because
 * applying it restarts the process and would stop that work.
 */
@Serializable
data class PendingSettings(
    val model: String? = null,
    val effort: String? = null,
)

/**
 * This bridge's process for a session — liveness, not content.
 *
 * `docs/api.md` calls liveness "the one sanctioned exception" to the rule that
 * everything rendered comes from the transcript. It is state *about* a turn, not
 * content *of* one.
 *
 * [pendingPermission] is the field that matters most on open: an ask may already be
 * outstanding when a client attaches, and this is what remembers it. A client that
 * only listens for the `permission-request` event misses every ask that predates it,
 * which on a phone is most of them.
 */
@Serializable
data class RunnerStatus(
    val sessionId: String? = null,
    val state: String? = null,
    val activity: String? = null,
    val model: String? = null,
    /**
     * The `--effort` the process was started with. **Only here**: the transcript does
     * not record it, so no summary carries it, and a session with no process has
     * nothing to report.
     */
    val effort: String? = null,
    val permissionMode: String? = null,
    val cwd: String? = null,
    val verb: String? = null,
    val detail: String? = null,
    val error: String? = null,
    val errorKind: String? = null,
    val queued: Int = 0,
    val queue: List<QueuedMessage> = emptyList(),
    val pendingPermission: Ask? = null,
    val canPrompt: Boolean = true,
    val busySince: Long? = null,
    /**
     * Tasks the process started and has not finished. A session can be `idle` with this
     * above zero, and while it is a model/effort change is held as [pendingSettings].
     */
    val background: Int = 0,
    val pendingSettings: PendingSettings? = null,
    /** Busy, and silent past the threshold, with nothing waiting on a person. */
    val stalled: Boolean = false,
    val lastActivityAt: Long? = null,
    val claudeVersion: String? = null,
) {
    val runnerState: RunnerState
        get() = when (state) {
            "stopped" -> RunnerState.Stopped
            "starting" -> RunnerState.Starting
            "idle" -> RunnerState.Idle
            "busy" -> RunnerState.Busy
            "error" -> RunnerState.Error
            else -> RunnerState.Unknown
        }

    /** The Stop button exists only while there is something to stop. */
    val isWorking: Boolean
        get() = runnerState == RunnerState.Busy || runnerState == RunnerState.Starting
}

/**
 * What the model and effort pickers offer, matching the desktop's `#model` and
 * `#effort` selects. `""` is `inherit` in both — no flag, so Claude Code decides.
 *
 * A value the runner reports that is not in the list (a summary carries the full model
 * id the transcript recorded, `claude-opus-…`, not the alias) shows as `inherit`, as
 * the desktop's `paintModel` does: a picker cannot show an option it does not have.
 */
object SessionChoices {
    val MODELS = listOf("", "opus", "sonnet", "haiku", "fable")
    val EFFORTS = listOf("", "low", "medium", "high", "xhigh", "max")

    fun knownModel(value: String?): String = value?.takeIf { it in MODELS }.orEmpty()
    fun knownEffort(value: String?): String = value?.takeIf { it in EFFORTS }.orEmpty()

    fun label(value: String): String = value.ifBlank { "inherit" }
}
