package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.data.model.Snippet
import com.twentygx.tgxcode.data.model.SnippetParam

/**
 * The rules for using a snippet, all pure and all the desktop's, so the phone and the
 * desk put the same text in the same place.
 */

/** `\{\{\s*(\w+)\s*\}\}` — the bridge's `fillBody` expression, down to the character. */
private val PLACEHOLDER = Regex("""\{\{\s*(\w+)\s*\}\}""")

/**
 * Whether a snippet applies in [cwd]: everywhere when [Snippet.projects] is empty, else
 * a prefix match **at a path boundary** — `/home/me/proj` covers `/home/me/proj/web` and
 * not `/home/me/proj-old`, which is another repository sharing fourteen characters.
 */
fun Snippet.appliesIn(cwd: String?): Boolean {
    if (projects.isEmpty()) return true
    val here = cwd?.trimEnd('/') ?: return false
    return projects.any { root ->
        val r = root.trimEnd('/')
        here == r || here.startsWith("$r/")
    }
}

/**
 * Fill the body from [answers], and leave alone what it cannot answer.
 *
 * Only a *declared* placeholder is a question; anything else stays verbatim, because
 * `{{` is not reserved punctuation in prose. An unanswered one falls back to its
 * default and then to itself — **never to the empty string**, which would quietly
 * delete part of a message somebody wrote.
 */
fun fillSnippet(body: String, params: List<SnippetParam>, answers: Map<String, String>): String {
    val defaults = params.associate { it.name to it.default }
    return PLACEHOLDER.replace(body) { match ->
        val key = match.groupValues[1]
        if (key !in defaults) return@replace match.value
        answers[key]?.takeIf { it.isNotEmpty() } ?: defaults[key] ?: match.value
    }
}

/**
 * Where the filled text lands in the box, as `web/snippets/popover.js` puts it.
 *
 * `append` leaves a blank line between, unless the box already ends in a break — two
 * paragraphs run together read as one. `cursor` replaces the selection, or inserts at
 * the caret; with no caret known it lands at the end.
 *
 * @return the new text, and where the caret goes after it.
 */
fun insertSnippet(current: String, text: String, how: String, selection: IntRange? = null): Pair<String, Int> =
    when (how) {
        "overwrite" -> text to text.length
        "append" -> {
            val lead = if (current.isEmpty() || current.endsWith("\n")) "" else "\n\n"
            val next = current + lead + text
            next to next.length
        }
        else -> {
            val from = (selection?.first ?: current.length).coerceIn(0, current.length)
            val to = (selection?.last?.plus(1) ?: from).coerceIn(from, current.length)
            val next = current.substring(0, from) + text + current.substring(to)
            next to from + text.length
        }
    }
