package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.ToolResultEvent
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.repo.Conversation
import com.twentygx.tgxcode.data.repo.applyEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a tool result reaches a tool that is already on screen.
 *
 * On a live tail the call is in an earlier chunk, so the bridge sends a separate
 * `tool-result` event rather than a fresh copy of the call. Dropping it — which
 * `web/mobile.js` does — leaves the tool spinning for the rest of the session.
 */
class ToolResultPatchTest {

    private fun blank() = Conversation(
        sessionId = "s1",
        summary = SessionSummary(sessionId = "s1"),
    )

    @Test
    fun `a separate tool-result patches the call rather than appending a row`() {
        val call = ToolEvent(id = "toolu_1", name = "Bash")
        var convo = blank().applyEvents(listOf(call), 10)
        assertTrue((convo.events.single() as ToolEvent).isRunning)

        val patch = ToolResultEvent(
            id = "toolu_1:result",
            toolId = "toolu_1",
            status = "ok",
            result = com.twentygx.tgxcode.data.model.ToolResult(text = "done"),
        )
        convo = convo.applyEvents(listOf(patch), 20)

        assertEquals("must not add a row", 1, convo.events.size)
        val tool = convo.events.single() as ToolEvent
        assertFalse("the tool must stop spinning", tool.isRunning)
        assertEquals("ok", tool.status)
        assertEquals("done", tool.result?.text)
        assertEquals(20L, convo.offset)
    }

    @Test
    fun `a result whose call was never seen is dropped, not rendered blank`() {
        val convo = blank().applyEvents(
            listOf(ToolResultEvent(id = "toolu_x:result", toolId = "toolu_x", status = "ok")),
            5,
        )
        assertTrue(convo.events.isEmpty())
    }

    @Test
    fun `decodes a real tool-result event from the wire`() {
        val json = """
            {"id":"toolu_01QET:result","kind":"tool-result","toolId":"toolu_01QET",
             "ts":"2026-08-11T16:40:37.533Z","status":"ok",
             "resultTs":"2026-08-11T16:40:37.533Z","persistedPath":null,
             "result":{"text":"ok","stdout":null,"stderr":null,"patch":null,
                       "filePath":null,"interrupted":false,"backgroundTaskId":null}}
        """.trimIndent()
        val event = AppJson.decodeFromString(TranscriptEvent.serializer(), json)
        assertTrue(event is ToolResultEvent)
        assertEquals("toolu_01QET", (event as ToolResultEvent).toolId)
        assertEquals("ok", event.status)
    }

    @Test
    fun `decodes a subagent descriptor on a tool call`() {
        val json = """
            {"id":"t1","kind":"tool","name":"Agent","input":{},
             "agent":{"agentId":"abf99","agentType":"Explore","description":"Explore chat",
                      "spawnDepth":1,"hasTranscript":true,"model":"claude-opus-5",
                      "isAsync":false,"durationMs":135053,"tokens":60838,"toolUses":35}}
        """.trimIndent()
        val event = AppJson.decodeFromString(TranscriptEvent.serializer(), json) as ToolEvent
        assertEquals("Explore", event.agent?.agentType)
        assertEquals(35, event.agent?.toolUses)
        assertNull(event.status)
    }
}
