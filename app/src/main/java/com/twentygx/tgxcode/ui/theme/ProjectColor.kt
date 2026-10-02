package com.twentygx.tgxcode.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colour somebody gave the project a directory belongs to — `projectColor()` in the
 * desktop's `web/app.js`, ported rule for rule.
 *
 * **Prefix at a path boundary, longest first.** The map is keyed by project root and
 * what is looked up is a session's working directory: often a worktree under
 * `<proj>/.claude/worktrees/`, or a subdirectory. Matching on the prefix is what lets a
 * worktree wear its checkout's colour, and the boundary is spelled out so
 * `/home/you/proj` cannot claim `/home/you/project`. Longest wins, so a worktree given a
 * colour of its own keeps it.
 *
 * A stored value that is not `#rgb` or `#rrggbb` gives no colour, as the desktop's
 * `hexAccent` gate does.
 */
fun projectColor(dir: String?, colors: Map<String, String>): Color? {
    val here = dir?.trim()?.trimEnd('/') ?: return null
    if (!here.startsWith("/")) return null
    var best: String? = null
    var deepest = -1
    for ((rootRaw, hex) in colors) {
        val root = rootRaw.trimEnd('/')
        if (here != root && !here.startsWith("$root/")) continue
        if (root.length <= deepest) continue
        deepest = root.length
        best = hex
    }
    return best?.let(::parseHex)
}

/** `#rgb` / `#rrggbb` into a colour, or null for anything else. */
fun parseHex(hex: String): Color? {
    val raw = hex.trim().removePrefix("#")
    val full = when {
        raw.length == 3 && raw.all { it.isHexDigit() } -> raw.map { "$it$it" }.joinToString("")
        raw.length == 6 && raw.all { it.isHexDigit() } -> raw
        else -> return null
    }
    return Color(0xFF000000 or full.toLong(16))
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** The colour map, provided once at the root so any row can ask without being handed it. */
val LocalProjectColors = compositionLocalOf<Map<String, String>> { emptyMap() }

/** The accent for [dir], or null — in which case nothing is drawn and nothing changes. */
@Composable
fun projectAccent(dir: String?): Color? = projectColor(dir, LocalProjectColors.current)
