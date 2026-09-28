package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

enum class RunnerState { Stopped, Starting, Idle, Busy, Error, Unknown }

@Serializable
data class QueuedMessage(
    val id: String? = null,
    val text: String? = null,
    val at: Long? = null,
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
    val permissionMode: String? = null,
    val cwd: String? = null,
    val error: String? = null,
    val errorKind: String? = null,
    val queued: Int = 0,
    val queue: List<QueuedMessage> = emptyList(),
    val pendingPermission: Ask? = null,
    val canPrompt: Boolean = true,
    val busySince: Long? = null,
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
