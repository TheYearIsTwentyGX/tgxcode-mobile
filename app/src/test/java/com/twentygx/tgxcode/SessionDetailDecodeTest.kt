package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.SessionDetail
import com.twentygx.tgxcode.data.model.ToolEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes a payload captured verbatim from the live bridge.
 *
 * Worth having as a fixture rather than a hand-written sample: the bridge sends a
 * dozen fields `docs/api.md` does not document, and the whole point of the model
 * layer is that it survives them.
 */
class SessionDetailDecodeTest {

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("session_detail.json")) {
            "session_detail.json missing from test resources"
        }.bufferedReader().readText()

    @Test
    fun `decodes a real session detail`() {
        val detail = AppJson.decodeFromString(SessionDetail.serializer(), fixture())

        assertTrue("expected events", detail.events.isNotEmpty())
        assertTrue("expected a byte offset", detail.offset > 0)
        assertTrue(
            "every event should keep its id",
            detail.events.all { it.id.isNotBlank() },
        )
    }

    @Test
    fun `tool events keep their free-form input`() {
        val detail = AppJson.decodeFromString(SessionDetail.serializer(), fixture())
        val tools = detail.events.filterIsInstance<ToolEvent>()
        assertTrue("fixture should contain tool calls", tools.isNotEmpty())
        assertTrue(
            "at least one tool should carry input",
            tools.any { it.input.isNotEmpty() },
        )
    }

    @Test
    fun `unknown event kinds do not fail the batch`() {
        val json = """
            {"summary":{"sessionId":"s1"},
             "events":[
               {"id":"a","kind":"assistant","text":"hi"},
               {"id":"b","kind":"something-new-from-a-later-bridge"},
               {"id":"c","kind":"assistant","text":"still here"}
             ],
             "offset":42}
        """.trimIndent()
        val detail = AppJson.decodeFromString(SessionDetail.serializer(), json)
        assertEquals(3, detail.events.size)
    }
}
