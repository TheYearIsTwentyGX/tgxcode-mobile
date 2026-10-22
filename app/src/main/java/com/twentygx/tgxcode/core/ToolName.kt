package com.twentygx.tgxcode.core

/**
 * A tool's name as a person reads it: `mcp__tgxcode__set_summary` is drawn as
 * "TGXCode: Set Summary", and `Bash` stays `Bash`.
 *
 * Ported from the desktop's `web/tool-name.js` (TGXCode#232), rule for rule, and
 * pinned by `ToolNameTest`, which carries `test/tool-name.test.js`'s cases over
 * verbatim. Change the two together, or the phone and the desk name one tool two ways.
 *
 * **Display only.** Everything that *matches* on a tool name — a permission rule, a
 * notification's dedupe, the orchestrator's own tools — keeps reading the raw name.
 * And text the bridge has already made friendly (an ask's `displayName`, a runner's
 * `activity`) is drawn as it came; this leaves a friendly string unchanged anyway,
 * because nothing friendly starts with `mcp__`.
 */

/**
 * Servers whose name the general rule would get wrong. Keyed on the server as it
 * appears in the tool name, and again on what is left after the `claude_ai_` or
 * `plugin_<x>_` prefix comes off, so `plugin_design_figma` finds `figma`.
 */
private val SERVERS = mapOf(
    "tgxcode" to "TGXCode",
    "claude_ai_Claude_Docs" to "Claude Docs",
    "github" to "GitHub",
    "gitlab" to "GitLab",
    "figma" to "Figma",
    "amplitude-eu" to "Amplitude EU",
    "hubspot" to "HubSpot",
    "devbrowser" to "DevBrowser",
)

/** Words that read wrong in Title Case. A plural keeps its `s` lower: "List PRs". */
private val ACRONYMS = setOf(
    "api", "url", "pr", "id", "mcp", "apk", "ui", "html", "json", "css",
    "sql", "http", "https", "ai", "ci", "cli", "ssh", "pdf", "csv", "eu", "uuid",
)

private val LOWER_THEN_UPPER = Regex("([a-z0-9])([A-Z])")
private val CAPS_THEN_WORD = Regex("([A-Z]+)([A-Z][a-z])")
private val SEPARATORS = Regex("[\\s_-]+")
private val HAS_UPPER = Regex("[A-Z]")
private val CLAUDE_AI_PREFIX = Regex("^claude_ai_")
private val PLUGIN_PREFIX = Regex("^plugin_[^_]+_")

/** Split snake_case, kebab-case and camelCase into Title Case words. */
private fun words(s: String): String =
    s.replace(LOWER_THEN_UPPER, "$1 $2")
        .replace(CAPS_THEN_WORD, "$1 $2")
        .split(SEPARATORS)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { w ->
            val lower = w.lowercase()
            when {
                lower in ACRONYMS -> lower.uppercase()
                lower.endsWith("s") && lower.dropLast(1) in ACRONYMS -> lower.dropLast(1).uppercase() + "s"
                w.length > 1 && w == w.uppercase() && HAS_UPPER.containsMatchIn(w) -> w
                else -> w.substring(0, 1).uppercase() + w.substring(1)
            }
        }

private fun serverName(server: String): String {
    SERVERS[server]?.let { return it }
    val bare = server.replace(CLAUDE_AI_PREFIX, "").replace(PLUGIN_PREFIX, "")
    return SERVERS[bare] ?: SERVERS[bare.lowercase()] ?: words(bare)
}

/**
 * The name to draw for a tool. Anything that is not `mcp__<server>__<tool>` comes back
 * untouched, built-ins included.
 */
fun toolDisplayName(name: String): String {
    if (!name.startsWith("mcp__")) return name
    val rest = name.substring(5)
    val at = rest.indexOf("__")
    if (at <= 0 || at + 2 >= rest.length) return name
    return "${serverName(rest.substring(0, at))}: ${words(rest.substring(at + 2))}"
}
