package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.toolDisplayName
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.ui.conversation.toolLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The desktop's `test/tool-name.test.js`, case for case. When a case changes there,
 * it changes here: a tool the desk calls one thing and the phone another reads as two
 * tools.
 */
class ToolNameTest {

    private val cases = listOf(
        // Our own server, with its branding.
        "mcp__tgxcode__set_summary" to "TGXCode: Set Summary",
        "mcp__tgxcode__report_to_orchestrator" to "TGXCode: Report To Orchestrator",
        // A server with underscores of its own: split on the first `__` only.
        "mcp__claude_ai_Claude_Docs__batch" to "Claude Docs: Batch",
        "mcp__claude_ai_Some_Connector__do_thing" to "Some Connector: Do Thing",
        // A plugin's server loses its `plugin_<x>_` prefix.
        "mcp__plugin_design_figma__get_file" to "Figma: Get File",
        "mcp__plugin_engineering_datadog__query_metrics" to "Datadog: Query Metrics",
        "mcp__plugin_data_amplitude-eu__run_query" to "Amplitude EU: Run Query",
        // camelCase and kebab-case tools.
        "mcp__tgxcode__getUsage" to "TGXCode: Get Usage",
        "mcp__my-server__list-open-items" to "My Server: List Open Items",
        // Acronyms stay upper case, a plural keeps its `s`, an all-caps word stays.
        "mcp__tgxcode__install_on_phone_apk" to "TGXCode: Install On Phone APK",
        "mcp__github__get_pr_url" to "GitHub: Get PR URL",
        "mcp__github__list_prs" to "GitHub: List PRs",
        "mcp__tgxcode__fetchApiId" to "TGXCode: Fetch API ID",
        "mcp__AWS_tools__describe" to "AWS Tools: Describe",
        // Built-ins and anything not shaped like an MCP name come back as they are.
        "Bash" to "Bash",
        "Read" to "Read",
        "ExitPlanMode" to "ExitPlanMode",
        "mcp__nodelimiter" to "mcp__nodelimiter",
        "mcp__server__" to "mcp__server__",
        "" to "",
    )

    @Test
    fun `names read the same as on the desktop`() {
        for ((raw, want) in cases) assertEquals(raw, want, toolDisplayName(raw))
    }

    @Test
    fun `no name stays no name`() {
        // The desktop's null and undefined cases; here the type system carries them.
        val none: String? = null
        assertNull(none?.let(::toolDisplayName))
    }

    @Test
    fun `the helper is idempotent`() {
        for ((raw, _) in cases) {
            assertEquals(raw, toolDisplayName(raw), toolDisplayName(toolDisplayName(raw)))
        }
    }

    @Test
    fun `a transcript tool row draws the friendly name`() {
        assertEquals("Playwright: Browser Navigate", toolLabel("mcp__plugin_playwright_playwright__browser_navigate"))
        assertEquals("Bash", toolLabel("Bash"))
        assertEquals("tool", toolLabel(null))
    }

    @Test
    fun `an ask prefers the bridge's display name and converts only the raw one`() {
        assertEquals(
            "TGXCode: Set Summary",
            Ask(requestId = "r", tool = "mcp__tgxcode__set_summary").toolLabel,
        )
        assertEquals(
            "Set the summary",
            Ask(requestId = "r", tool = "mcp__tgxcode__set_summary", displayName = "Set the summary").toolLabel,
        )
        // The raw name is still there for anything that matches on it.
        assertEquals("mcp__tgxcode__set_summary", Ask(requestId = "r", tool = "mcp__tgxcode__set_summary").tool)
    }
}
