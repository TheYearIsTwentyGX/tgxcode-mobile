package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.AgentFollow
import com.twentygx.tgxcode.data.model.AssistantEvent
import com.twentygx.tgxcode.data.model.SessionDetail
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.model.SubscribeRequest
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.ToolResultEvent
import com.twentygx.tgxcode.data.model.TurnMark
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.repo.Conversation
import com.twentygx.tgxcode.data.repo.applyEvents
import com.twentygx.tgxcode.data.repo.earlierStart
import com.twentygx.tgxcode.data.repo.prependEvents
import com.twentygx.tgxcode.ui.conversation.turnRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnWindowTest {

    private fun conv(windowStart: Long) =
        Conversation(sessionId = "s", summary = SessionSummary(sessionId = "s"), windowStart = windowStart)

    @Test
    fun `a turns window decodes its rail and start`() {
        val json = """
            {"summary":{"sessionId":"s"},"offset":900,"events":[],
             "turns":[{"kind":"turn","id":"u1","offset":0,"ts":null,"text":"first","command":null,"images":0},
                      {"kind":"plan","id":"t9","offset":0,"name":"ExitPlanMode","status":"ok"},
                      {"kind":"turn","id":"u2","offset":400,"text":"second"}],
             "window":{"start":400,"startTurn":1}}
        """.trimIndent()
        val detail = AppJson.decodeFromString(SessionDetail.serializer(), json)
        assertEquals(3, detail.turns.size)
        assertEquals(400L, detail.window?.start)
        assertEquals("Plan", detail.turns[1].label())
    }

    @Test
    fun `a result whose call is above the window waits for it`() {
        // The newer stretch is loaded first and carries the result for an older call.
        val opened = conv(windowStart = 500).applyEvents(
            listOf(
                ToolResultEvent(id = "t1:result", toolId = "t1", status = "ok"),
                AssistantEvent(id = "a2", text = "after"),
            ),
            newOffset = 900,
        )
        assertTrue("held, not dropped", "t1" in opened.orphanResults)

        val earlier = opened.prependEvents(
            listOf(UserEvent(id = "u1", text = "go"), ToolEvent(id = "t1", name = "Bash", status = "pending")),
            from = 0,
        )
        val tool = earlier.events.filterIsInstance<ToolEvent>().single()
        assertEquals("ok", tool.status)
        assertFalse(tool.isRunning)
        assertTrue(earlier.orphanResults.isEmpty())
        assertEquals(listOf("u1", "t1", "a2"), earlier.events.map { it.id })
        assertEquals(1, earlier.indexById["t1"])
        assertEquals(0L, earlier.windowStart)
        assertEquals("the live tail is untouched", 900L, earlier.offset)
    }

    @Test
    fun `prepending skips what the window already holds`() {
        val opened = conv(windowStart = 100).applyEvents(listOf(AssistantEvent(id = "a1")), 300)
        val merged = opened.prependEvents(listOf(UserEvent(id = "u0"), AssistantEvent(id = "a1")), from = 0)
        assertEquals(listOf("u0", "a1"), merged.events.map { it.id })
    }

    @Test
    fun `a whole transcript drops a result it has no call for`() {
        val whole = conv(windowStart = 0).applyEvents(
            listOf(ToolResultEvent(id = "x:result", toolId = "x")),
            newOffset = 10,
        )
        assertTrue(whole.orphanResults.isEmpty())
    }

    @Test
    fun `load earlier counts back by turns, not by plans`() {
        val turns = listOf(
            TurnMark(kind = "turn", id = "u1", offset = 0),
            TurnMark(kind = "turn", id = "u2", offset = 100),
            TurnMark(kind = "plan", id = "p", offset = 100),
            TurnMark(kind = "turn", id = "u3", offset = 200),
            TurnMark(kind = "turn", id = "u4", offset = 300),
        )
        assertEquals(100L, earlierStart(turns, windowStart = 300, count = 2))
        assertEquals("fewer than asked reaches the top", 0L, earlierStart(turns, windowStart = 300, count = 8))
    }

    @Test
    fun `the rail adds turns said since it was built, newest first`() {
        val c = conv(windowStart = 0).copy(turns = listOf(TurnMark(kind = "turn", id = "u1", text = "old")))
            .applyEvents(listOf(UserEvent(id = "u1", text = "old"), UserEvent(id = "u2", text = "new")), 50)
        assertEquals(listOf("u2", "u1"), turnRail(c).map { it.id })
    }

    @Test
    fun `a subagent is followed by an object, and its tail names the tool call`() {
        val body = AppJson.encodeToString(
            SubscribeRequest.serializer(),
            SubscribeRequest(clientId = "c", sessionId = "s", agent = AgentFollow("toolu_1", 42)),
        )
        assertTrue(body.contains("\"agent\":{\"toolUseId\":\"toolu_1\",\"offset\":42}"))

        val tail = SseCodec.decode(
            "agent-tail",
            """{"sessionId":"s","toolUseId":"toolu_1","events":[{"id":"e","kind":"assistant","text":"hi"}],"offset":80}""",
        ) as StreamEvent.AgentTail
        assertEquals("toolu_1", tail.toolUseId)
        assertEquals(1, tail.events.size)
    }
}
