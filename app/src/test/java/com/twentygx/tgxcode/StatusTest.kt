package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.model.NotificationRow
import com.twentygx.tgxcode.data.model.QuotaWindow
import com.twentygx.tgxcode.data.model.ReadMarks
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.repo.Inbox
import com.twentygx.tgxcode.data.repo.withRead
import com.twentygx.tgxcode.ui.status.percentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {

    @Test
    fun `the new stream events decode`() {
        val standing = SseCodec.decode(
            "standing-changed",
            """{"sessionId":"s1","standing":{"text":"waiting on review","source":"model","at":5}}""",
        ) as StreamEvent.StandingChanged
        assertEquals("waiting on review", standing.standing?.text)

        val stalled = SseCodec.decode(
            "runner-stalled",
            """{"sessionId":"s1","stalled":true,"lastActivityAt":1,"busySince":0,"stalledAfterMs":300000}""",
        ) as StreamEvent.RunnerStalled
        assertTrue(stalled.stalled)

        val list = SseCodec.decode(
            "task-list",
            """{"sessionId":"s1","source":"todo","items":[{"id":null,"subject":"Write it","status":"in_progress"}],
               "done":0,"total":1,"current":"Writing it","idle":false,"ts":null,"truncated":0}""",
        ) as StreamEvent.TaskListChanged
        assertTrue(list.list.items.single().isActive)

        val quota = SseCodec.decode(
            "quota",
            """{"version":1,"now":1000,"windows":[{"type":"five_hour","label":"5-hour","shortLabel":"5h",
               "usedPercent":42.4,"usedPercentAt":900,"resetsAt":5000,"status":"allowed"}],"events":[]}""",
        ) as StreamEvent.QuotaChanged
        assertEquals("5h", quota.quota.windows.single().name)

        val row = SseCodec.decode(
            "notification",
            """{"id":"n1","at":10,"type":"stalled","sessionId":"s1","title":"T","loud":true,"read":false,"unread":3}""",
        ) as StreamEvent.NotificationFiled
        assertEquals(3, row.row.unread)

        val read = SseCodec.decode("notification-read", """{"sessionId":null,"at":50,"unread":0}""")
            as StreamEvent.NotificationRead
        assertNull(read.sessionId)
    }

    @Test
    fun `a percentage nobody reported is a dash, not zero`() {
        assertEquals("—", percentText(QuotaWindow(type = "five_hour")))
        assertEquals("42%", percentText(QuotaWindow(type = "five_hour", usedPercent = 42.4)))
    }

    @Test
    fun `a reading older than half an hour is stale, by the bridge's clock`() {
        val window = QuotaWindow(type = "five_hour", usedPercent = 3.0, usedPercentAt = 1_000)
        assertFalse(window.isStale(now = 1_000 + 29 * 60))
        assertTrue(window.isStale(now = 1_000 + 31 * 60))
        assertTrue("never stamped is stale", QuotaWindow(type = "x").isStale(now = 5))
    }

    @Test
    fun `watermarks only move forward and cover by session or all`() {
        val marks = ReadMarks(all = 100, sessions = mapOf("s1" to 500))
        assertTrue(marks.covers(NotificationRow(id = "a", at = 400, sessionId = "s1")))
        assertFalse(marks.covers(NotificationRow(id = "b", at = 400, sessionId = "s2")))
        assertTrue(marks.covers(NotificationRow(id = "c", at = 90, sessionId = null)))
        assertEquals(500L, marks.advance("s1", 200).sessions["s1"])
        assertEquals(100L, marks.advance(null, 50).all)
    }

    @Test
    fun `marking read restamps rows and trusts the bridge's count`() {
        val inbox = Inbox(
            rows = listOf(
                NotificationRow(id = "a", at = 10, sessionId = "s1", loud = true),
                NotificationRow(id = "b", at = 20, sessionId = "s2", loud = true),
            ),
            unread = 7,
        )
        val local = inbox.withRead(ReadMarks(sessions = mapOf("s1" to 15)), unread = null)
        assertTrue(local.rows[0].read)
        assertFalse(local.rows[1].read)
        assertEquals("estimated from the rows in hand", 1, local.unread)
        assertEquals(6, inbox.withRead(ReadMarks(), unread = 6).unread)
    }
}
