package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.RestartOutcome
import com.twentygx.tgxcode.data.model.RestartRequest
import com.twentygx.tgxcode.data.model.RestartResponse
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.restartOutcomeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `POST /api/sessions/:id/restart`, in the shapes the bridge sends. */
class RestartOutcomeTest {

    private fun decode(json: String) =
        ApiResult.Ok(AppJson.decodeFromString(RestartResponse.serializer(), json))

    private fun conflict(body: String) =
        ApiResult.Http(409, "refused", body)

    @Test
    fun `a restart carries the new runner status`() {
        val outcome = restartOutcomeOf(
            decode("""{"ok":true,"restarted":true,"status":{"sessionId":"s1","state":"idle","queued":0,"queue":[]}}"""),
        )
        assertTrue(outcome is RestartOutcome.Restarted)
        assertEquals("idle", (outcome as RestartOutcome.Restarted).status?.state)
    }

    @Test
    fun `restarted false is no process, not a failure`() {
        val outcome = restartOutcomeOf(decode("""{"ok":true,"restarted":false,"status":{"state":"stopped"}}"""))
        assertTrue(outcome is RestartOutcome.NoProcess)
    }

    @Test
    fun `the two 409s are told apart by their bodies`() {
        assertEquals(RestartOutcome.Busy, restartOutcomeOf(conflict("""{"error":"busy","busy":true}""")))
        assertEquals(
            RestartOutcome.Background(3),
            restartOutcomeOf(conflict("""{"error":"3 background tasks","background":3}""")),
        )
    }

    @Test
    fun `a 409 naming neither reads as busy, which offers no forcing retry`() {
        assertEquals(RestartOutcome.Busy, restartOutcomeOf(conflict("not json")))
        assertEquals(RestartOutcome.Busy, restartOutcomeOf(ApiResult.Http(409, null)))
    }

    @Test
    fun `any other refusal is a failure with its message`() {
        assertEquals(
            RestartOutcome.Failed("Not found"),
            restartOutcomeOf(ApiResult.Http(404, "Not found", """{"error":"Not found"}""")),
        )
    }

    @Test
    fun `force is sent only when asked for`() {
        assertEquals("{}", AppJson.encodeToString(RestartRequest.serializer(), RestartRequest()))
        assertEquals("{\"force\":true}", AppJson.encodeToString(RestartRequest.serializer(), RestartRequest(force = true)))
    }

    @Test
    fun `only a live process offers a restart`() {
        assertTrue(RunnerStatus(state = "idle").hasProcess)
        assertTrue(RunnerStatus(state = "busy").hasProcess)
        assertFalse(RunnerStatus(state = "stopped").hasProcess)
        assertFalse(RunnerStatus(state = "error").hasProcess)
    }
}
