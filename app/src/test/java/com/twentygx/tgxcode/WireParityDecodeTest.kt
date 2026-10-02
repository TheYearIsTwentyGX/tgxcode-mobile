package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.HandoffEvent
import com.twentygx.tgxcode.data.model.PeerMessageEvent
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SessionDetail
import com.twentygx.tgxcode.data.model.SessionsResponse
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.ui.conversation.toolStatusChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fields the desktop gained after this client was written, decoded from shapes
 * taken out of `docs/api.md`.
 */
class WireParityDecodeTest {

    @Test
    fun `a summary carries standing, later, schedule and the six-field runner`() {
        val json = """
            {"sessions":[{
              "sessionId":"s1","title":"Fix it",
              "standing":{"text":"PR #150 opened, waiting on review","source":"model","at":1700000000000},
              "later":{"pending":2,"nextAt":1700000500000},
              "schedule":{"id":"sch1","title":"Nightly review"},
              "runner":{"state":"idle","activity":null,"detail":null,"queued":0,
                        "claudeVersion":"2.1.280","stalled":false}
            }],"ready":true}
        """.trimIndent()
        val s = AppJson.decodeFromString(SessionsResponse.serializer(), json).sessions.single()
        assertEquals("PR #150 opened, waiting on review", s.standingLine)
        assertEquals(2, s.later?.pending)
        assertEquals("Nightly review", s.schedule?.title)
        assertFalse(s.isStalled)
    }

    @Test
    fun `the standing line is hidden while a turn is working`() {
        val json = """
            {"sessions":[{"sessionId":"s1",
              "standing":{"text":"old news","source":"extract","at":1},
              "runner":{"state":"busy","stalled":true}}]}
        """.trimIndent()
        val s = AppJson.decodeFromString(SessionsResponse.serializer(), json).sessions.single()
        assertNull(s.standingLine)
        assertTrue(s.isStalled)
    }

    @Test
    fun `a five-field runner from an older bridge is not stalled`() {
        val json = """{"sessions":[{"sessionId":"s1","runner":{"state":"busy","queued":1}}]}"""
        val s = AppJson.decodeFromString(SessionsResponse.serializer(), json).sessions.single()
        assertFalse(s.isStalled)
    }

    @Test
    fun `the full runner carries effort, background and held settings`() {
        val json = """
            {"sessionId":"s1","state":"idle","model":"opus","effort":"high",
             "permissionMode":"acceptEdits","queued":1,
             "queue":[{"id":"q1","text":"and then","at":1,"attachments":[],"handed":true}],
             "background":1,"pendingSettings":{"model":"sonnet","effort":null},
             "stalled":false,"lastActivityAt":5}
        """.trimIndent()
        val r = AppJson.decodeFromString(RunnerStatus.serializer(), json)
        assertEquals("high", r.effort)
        assertEquals(1, r.background)
        assertEquals("sonnet", r.pendingSettings?.model)
        assertNull(r.pendingSettings?.effort)
        assertTrue(r.queue.single().handed)
    }

    @Test
    fun `user events keep the parent a branch is cut from`() {
        val json = """
            {"summary":{"sessionId":"s1"},"offset":10,"events":[
              {"id":"u1","kind":"user","text":"first","parent":null},
              {"id":"u2","kind":"user","text":"second","parent":"abc-123","origin":"human"},
              {"id":"u3","kind":"user","text":"from a peer","parent":"def","origin":"peer"}
            ]}
        """.trimIndent()
        val users = AppJson.decodeFromString(SessionDetail.serializer(), json)
            .events.filterIsInstance<UserEvent>()
        assertNull(users[0].parent)
        assertEquals("abc-123", users[1].parent)
        assertTrue(users[1].fromHuman)
        assertFalse(users[2].fromHuman)
    }

    @Test
    fun `peer messages and handoffs decode as their own kinds`() {
        val json = """
            {"summary":{"sessionId":"s1"},"offset":10,"events":[
              {"id":"p1","kind":"peer-message","from":"/tmp/sock","fromName":"api-worker","text":"done"},
              {"id":"h1","kind":"handoff","text":"take this","from":"s0","fromTitle":"Planner",
               "fromProject":"api","title":"Ship it"}
            ]}
        """.trimIndent()
        val events = AppJson.decodeFromString(SessionDetail.serializer(), json).events
        assertEquals("api-worker", (events[0] as PeerMessageEvent).fromName)
        assertEquals("Planner", (events[1] as HandoffEvent).fromTitle)
    }

    @Test
    fun `a pending tool shows as running, not finished`() {
        val pending = ToolEvent(id = "t1", name = "Bash", status = "pending")
        val absent = ToolEvent(id = "t2", name = "Bash")
        val done = ToolEvent(id = "t3", name = "Bash", status = "ok")
        assertTrue(pending.isRunning)
        assertEquals("…", toolStatusChip(pending))
        assertEquals("…", toolStatusChip(absent))
        assertEquals("", toolStatusChip(done))
        assertEquals("failed", toolStatusChip(ToolEvent(id = "t4", status = "error")))
    }
}
