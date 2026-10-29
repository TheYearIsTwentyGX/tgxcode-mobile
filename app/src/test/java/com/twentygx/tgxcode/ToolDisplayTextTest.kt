package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.toolDisplayText
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.BoardCard
import com.twentygx.tgxcode.data.model.CardRunner
import com.twentygx.tgxcode.data.model.ChatImage
import com.twentygx.tgxcode.data.model.Headline
import com.twentygx.tgxcode.data.model.describe
import com.twentygx.tgxcode.ui.orchestrator.inboxNudgeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [toolDisplayText]: tool names inside text the bridge composed, and where it is applied. */
class ToolDisplayTextTest {

    @Test
    fun `converts a name inside a sentence`() {
        assertEquals(
            "Running TGXCode: Next Message…",
            toolDisplayText("Running mcp__tgxcode__next_message…"),
        )
    }

    @Test
    fun `converts every name, and keeps the punctuation around them`() {
        assertEquals(
            "Wants to run GitHub: List PRs, then (Figma: Get File).",
            toolDisplayText("Wants to run mcp__github__list_prs, then (mcp__plugin_design_figma__get_file)."),
        )
        assertEquals("`TGXCode: Set Summary`", toolDisplayText("`mcp__tgxcode__set_summary`"))
    }

    @Test
    fun `a whole name converts as toolDisplayName does`() {
        assertEquals("Amplitude EU: Run Query", toolDisplayText("mcp__plugin_data_amplitude-eu__run_query"))
    }

    @Test
    fun `a trailing hyphen stays outside the name`() {
        assertEquals("TGXCode: Set Summary - done", toolDisplayText("mcp__tgxcode__set_summary - done"))
        assertEquals("TGXCode: Set Summary- done", toolDisplayText("mcp__tgxcode__set_summary- done"))
    }

    @Test
    fun `leaves text with no tool name alone, as the same instance`() {
        val plain = "Running Bash: npm test"
        assertSame(plain, toolDisplayText(plain))
        assertEquals("", toolDisplayText(""))
    }

    @Test
    fun `does not touch mcp__ in the middle of an identifier, or a name with no tool`() {
        assertEquals("my_mcp__thing", toolDisplayText("my_mcp__thing"))
        assertEquals("see mcp__tgxcode", toolDisplayText("see mcp__tgxcode"))
    }

    @Test
    fun `an ask's composed displayName is converted, and its raw tool kept`() {
        val ask = Ask(requestId = "r", tool = "mcp__tgxcode__start_task", displayName = "mcp__tgxcode__start_task")
        assertEquals("TGXCode: Start Task", ask.toolLabel)
        assertEquals("mcp__tgxcode__start_task", ask.tool)
    }

    @Test
    fun `a card's why and headline read friendly, and a repeat is still suppressed`() {
        val card = BoardCard(
            sessionId = "s",
            runner = CardRunner(state = "busy", activity = "Running mcp__tgxcode__next_message"),
            headlines = listOf(Headline("Running mcp__tgxcode__next_message")),
        )
        assertEquals("Running TGXCode: Next Message", card.why)
        assertNull(card.headline)
    }

    @Test
    fun `an image label reads friendly`() {
        val image = ChatImage(key = "k", label = "mcp__plugin_playwright_playwright__browser_take_screenshot", bytes = 2048)
        assertEquals("Playwright: Browser Take Screenshot · 2 KB", image.describe())
    }

    @Test
    fun `the orchestrator's nudge summary reads friendly`() {
        assertEquals(
            "1 item waiting — Wants TGXCode: Next Message",
            inboxNudgeText(1, null, "Wants mcp__tgxcode__next_message"),
        )
    }
}
