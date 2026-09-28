package com.twentygx.tgxcode.ui.conversation

import com.twentygx.tgxcode.core.PrettyJson
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turning a tool call into the one line the phone shows, and the block it expands
 * into. Pure string work, so it is all unit-testable with no Android on the
 * classpath.
 */

private val MCP_PREFIX = Regex("^mcp__.+?__(.+)$")

/**
 * Strips the `mcp__server__` prefix from an MCP tool name.
 *
 * Done here rather than with `TextOverflow.Ellipsis`, because CSS-style truncation
 * cuts from the right and would keep exactly the half that says nothing:
 * `mcp__plugin_playwright_playwright__browser_navigate` would render as
 * `mcp__plugin_playwri…` instead of `browser_navigate`.
 */
fun toolLabel(name: String?): String {
    val raw = name?.trim().orEmpty()
    if (raw.isEmpty()) return "tool"
    return MCP_PREFIX.find(raw)?.groupValues?.get(1) ?: raw
}

/**
 * The fields worth showing as a one-line summary, in the order they are worth
 * showing. `description` first because a tool that wrote one meant it.
 */
private val DESCRIPTION_FIELDS = listOf(
    "description", "command", "file_path", "path",
    "pattern", "prompt", "url", "subject", "query",
)

fun firstLine(text: String, max: Int = 90): String {
    val line = text.substringBefore('\n').trim()
    return if (line.length > max) line.take(max - 1) + "…" else line
}

fun toolDescription(input: JsonObject): String {
    for (field in DESCRIPTION_FIELDS) {
        val value = (input[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!value.isNullOrBlank()) return firstLine(value)
    }
    if (input.isEmpty()) return ""
    return firstLine(input.toString())
}

private fun renderValue(value: JsonElement): String =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: PrettyJson.encodeToString(JsonElement.serializer(), value)

/**
 * The expanded body: the inputs, then the result.
 *
 * `patch` before `text` before `stdout` is the same precedence the web client uses —
 * a diff is the most informative thing a tool can hand back, so it wins.
 */
fun toolBody(
    input: JsonObject,
    result: ToolResult? = null,
    persistedPath: String? = null,
): String {
    val sections = mutableListOf<String>()

    if (input.isNotEmpty()) {
        sections += input.entries.joinToString("\n") { (k, v) -> "$k: ${renderValue(v)}" }
    }

    if (result != null) {
        val body = result.patchText()
            ?: result.text?.takeIf { it.isNotBlank() }
            ?: result.stdout?.takeIf { it.isNotBlank() }
        if (body != null) sections += "— result —\n$body"
        result.stderr?.takeIf { it.isNotBlank() }?.let { sections += "— stderr —\n$it" }
        if (result.interrupted) sections += "(interrupted)"
    }

    if (!persistedPath.isNullOrBlank()) {
        sections += "(the full output was written to a file; open this session on the " +
            "desktop to read it)"
    }

    return sections.joinToString("\n\n").ifBlank { "(nothing recorded)" }
}

fun toolBody(event: ToolEvent): String =
    toolBody(event.input, event.result, event.persistedPath)

/** `failed`, blank, or the ellipsis that means still running. */
fun toolStatusChip(event: ToolEvent): String = when {
    event.status == "error" -> "failed"
    event.status != null -> ""
    else -> "…"
}
