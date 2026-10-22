package com.twentygx.tgxcode.data.model

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /api/sessions/:id/restart`. [force] stops background tasks rather than
 * refusing over them; absent is "refuse".
 */
@Serializable
data class RestartRequest(val force: Boolean? = null)

/** [restarted] false is "there was no process" — the next send starts a fresh one anyway. */
@Serializable
data class RestartResponse(
    val ok: Boolean = false,
    val restarted: Boolean = false,
    val status: RunnerStatus? = null,
)

/**
 * What a restart came to. A process keeps the tool list it started with, so this is
 * how a session picks up new tools without losing its conversation.
 */
sealed interface RestartOutcome {
    data class Restarted(val status: RunnerStatus?) : RestartOutcome
    data class NoProcess(val status: RunnerStatus?) : RestartOutcome
    /** A turn is running or starting, or an ask is waiting. Nothing to confirm past. */
    data object Busy : RestartOutcome
    /** Background tasks would stop. The one refusal a `force` retry gets past. */
    data class Background(val count: Int) : RestartOutcome
    data class Failed(val message: String) : RestartOutcome
}

/**
 * The two `409`s are told apart by their bodies, not their status: `{busy: true}` and
 * `{background: n}`. A `409` naming neither reads as busy, the safer of the two — it
 * offers no retry that would stop anything.
 */
fun restartOutcomeOf(result: ApiResult<RestartResponse>): RestartOutcome = when (result) {
    is ApiResult.Ok ->
        if (result.value.restarted) {
            RestartOutcome.Restarted(result.value.status)
        } else {
            RestartOutcome.NoProcess(result.value.status)
        }
    is ApiResult.Http -> if (result.status != 409) RestartOutcome.Failed(result.message()) else {
        val background = runCatching {
            (AppJson.parseToJsonElement(result.body.orEmpty()) as JsonObject)["background"]
                ?.jsonPrimitive?.intOrNull
        }.getOrNull()
        if (background != null && background > 0) {
            RestartOutcome.Background(background)
        } else {
            RestartOutcome.Busy
        }
    }
    is ApiResult.Offline, is ApiResult.AccessDenied -> RestartOutcome.Failed(result.message())
}
