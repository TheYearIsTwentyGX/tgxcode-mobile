package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.model.PatchHunk
import com.twentygx.tgxcode.data.model.ToolResult
import com.twentygx.tgxcode.ui.conversation.toolBody
import com.twentygx.tgxcode.ui.conversation.toolDescription
import com.twentygx.tgxcode.ui.conversation.toolLabel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTextTest {

    @Test
    fun `strips the mcp server prefix`() {
        assertEquals(
            "browser_navigate",
            toolLabel("mcp__plugin_playwright_playwright__browser_navigate"),
        )
    }

    @Test
    fun `leaves ordinary tool names alone`() {
        assertEquals("Bash", toolLabel("Bash"))
        assertEquals("tool", toolLabel(null))
        assertEquals("tool", toolLabel("  "))
    }

    @Test
    fun `description prefers the description field over the command`() {
        val input = JsonObject(
            mapOf(
                "command" to JsonPrimitive("git status --porcelain"),
                "description" to JsonPrimitive("Check git status"),
            ),
        )
        assertEquals("Check git status", toolDescription(input))
    }

    @Test
    fun `description falls back through the field order`() {
        val input = JsonObject(mapOf("pattern" to JsonPrimitive("TODO")))
        assertEquals("TODO", toolDescription(input))
    }

    @Test
    fun `description takes only the first line and clips it`() {
        val long = "a".repeat(200)
        val input = JsonObject(mapOf("command" to JsonPrimitive("$long\nsecond line")))
        val described = toolDescription(input)
        assertTrue(described.length <= 90)
        assertTrue(described.endsWith("…"))
    }

    @Test
    fun `a structured patch renders as a unified diff`() {
        val result = ToolResult(
            patch = listOf(
                PatchHunk(
                    oldStart = 69, oldLines = 8, newStart = 69, newLines = 13,
                    lines = listOf("   context", "-  removed", "+  added"),
                ),
            ),
        )
        val body = toolBody(JsonObject(emptyMap()), result)
        assertTrue(body.contains("@@ -69,8 +69,13 @@"))
        assertTrue(body.contains("-  removed"))
        assertTrue(body.contains("+  added"))
    }

    @Test
    fun `a patch outranks text in the result body`() {
        val result = ToolResult(
            text = "should not win",
            patch = listOf(PatchHunk(lines = listOf("+ hi"))),
        )
        assertTrue(toolBody(JsonObject(emptyMap()), result).contains("+ hi"))
    }

    @Test
    fun `an empty tool says so rather than rendering blank`() {
        assertEquals("(nothing recorded)", toolBody(JsonObject(emptyMap()), null))
    }
}
