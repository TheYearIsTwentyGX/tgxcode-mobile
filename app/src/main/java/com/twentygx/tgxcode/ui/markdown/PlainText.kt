package com.twentygx.tgxcode.ui.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * A message as plain text that still *reads* like the markdown it came from.
 *
 * This is what "Copy without formatting" puts on the clipboard. It is for places that
 * will show markdown syntax as literal characters, such as a text message, a ticket
 * field or a form. Stripping the syntax outright would run paragraphs together and
 * make lists unreadable, so the layout the syntax described is kept:
 *
 * - one blank line between blocks,
 * - `-` for every bullet, the source number for ordered items, and nested lists
 *   indented under their parent,
 * - code verbatim with no fences,
 * - links as `text (url)`,
 * - tables as ` | `-joined rows.
 *
 * Emphasis, heading hashes and backticks are dropped. They are the parts that read as
 * noise once they are no longer rendered.
 *
 * The parser is the same GFM one [MarkdownText] renders with. So a line that shows as a
 * list item on screen is a list item here too, not a guess made by a regex.
 */
fun markdownToPlainText(markdown: String): String {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    return PlainText(markdown, tree).render()
}

private class PlainText(private val src: String, private val root: ASTNode) {

    /** `[label]: url` definitions, which reference-style links resolve against. */
    private val definitions = HashMap<String, String>()

    fun render(): String {
        collectDefinitions(root)
        return blocks(root.children, loose = true)
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim('\n')
    }

    // ---- Blocks -------------------------------------------------------------------

    /** Renders [nodes] as blocks, one blank line apart when [loose] and none when tight. */
    private fun blocks(nodes: List<ASTNode>, loose: Boolean): String =
        nodes.mapNotNull(::block).filter { it.isNotBlank() }
            .joinToString(if (loose) "\n\n" else "\n")

    private fun block(node: ASTNode): String? = when (node.type) {
        MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE,
        MarkdownElementTypes.LINK_DEFINITION -> null

        MarkdownElementTypes.PARAGRAPH -> inline(node.children).trim()

        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 ->
            node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
                ?.let { inline(it.children).trim() }.orEmpty()

        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 ->
            node.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT }
                ?.let { inline(it.children).trim() }.orEmpty()

        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> list(node)

        MarkdownElementTypes.BLOCK_QUOTE, GFMElementTypes.ALERT ->
            blocks(node.children.filter { it.type != MarkdownTokenTypes.BLOCK_QUOTE }, loose = true)
                .lines()
                .joinToString("\n") { if (it.isBlank()) ">" else "> $it" }

        MarkdownElementTypes.CODE_FENCE -> node.children
            .filterNot {
                it.type == MarkdownTokenTypes.CODE_FENCE_START ||
                    it.type == MarkdownTokenTypes.FENCE_LANG ||
                    it.type == MarkdownTokenTypes.CODE_FENCE_END
            }
            .joinToString("") { text(it) }
            .trimIndent()

        MarkdownElementTypes.CODE_BLOCK -> text(node).trimIndent()

        MarkdownTokenTypes.HORIZONTAL_RULE -> "---"

        GFMElementTypes.TABLE -> node.children
            .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
            .joinToString("\n") { row ->
                row.children.filter { it.type == GFMTokenTypes.CELL }
                    .joinToString(" | ") { inline(it.children).trim() }
            }

        MarkdownElementTypes.HTML_BLOCK, GFMElementTypes.BLOCK_MATH -> text(node).trim()

        else -> if (node.children.isEmpty()) text(node).trim() else blocks(node.children, loose = true)
    }

    private fun list(node: ASTNode): String {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val loose = hasBlankLine(node.children) || items.any { hasBlankLine(it.children) }
        return items.joinToString(if (loose) "\n\n" else "\n") { item(it, loose) }
    }

    private fun item(item: ASTNode, loose: Boolean): String {
        val marker = item.children.firstOrNull()?.let {
            when (it.type) {
                MarkdownTokenTypes.LIST_NUMBER -> text(it).trim() + " "
                else -> "- "
            }
        } ?: "- "
        val body = item.children.filterNot {
            it.type == MarkdownTokenTypes.LIST_BULLET || it.type == MarkdownTokenTypes.LIST_NUMBER
        }
        // A task item's box sits beside the paragraph rather than inside it.
        val box = body.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }?.let { checkBox(it) }.orEmpty()
        val content = box + blocks(body.filter { it.type != GFMTokenTypes.CHECK_BOX }, loose)
        val indent = " ".repeat(marker.length)
        return content.lines().mapIndexed { i, line ->
            when {
                i == 0 -> marker + line
                line.isBlank() -> ""
                else -> indent + line
            }
        }.joinToString("\n")
    }

    /** True when two line ends meet with nothing but spaces between them. */
    private fun hasBlankLine(nodes: List<ASTNode>): Boolean {
        var eols = 0
        for (n in nodes) {
            when (n.type) {
                MarkdownTokenTypes.EOL -> if (++eols >= 2) return true
                MarkdownTokenTypes.WHITE_SPACE -> Unit
                else -> eols = 0
            }
        }
        return false
    }

    private fun checkBox(node: ASTNode): String =
        if (text(node).contains('x', ignoreCase = true)) "[x] " else "[ ] "

    // ---- Inlines ------------------------------------------------------------------

    private fun inline(nodes: List<ASTNode>): String {
        val out = StringBuilder()
        var afterBreak = false
        var afterHardBreak = false
        for (n in nodes) {
            // A soft break renders as a space. Skip the next line's indentation, and the
            // `>` that continues a quote, so the result has a single space where the
            // line break was. A hard break is followed by its own EOL, which is skipped too.
            if (afterBreak && (n.type == MarkdownTokenTypes.WHITE_SPACE || n.type == MarkdownTokenTypes.BLOCK_QUOTE)) continue
            if (afterHardBreak && n.type == MarkdownTokenTypes.EOL) continue
            afterBreak = false
            afterHardBreak = false
            when (n.type) {
                MarkdownTokenTypes.EOL -> { out.append(' '); afterBreak = true }
                MarkdownTokenTypes.HARD_LINE_BREAK -> { out.append('\n'); afterBreak = true; afterHardBreak = true }
                GFMTokenTypes.CHECK_BOX -> out.append(checkBox(n))
                else -> out.append(span(n))
            }
        }
        return out.toString()
    }

    private fun span(node: ASTNode): String = when (node.type) {
        MarkdownElementTypes.EMPH, MarkdownElementTypes.STRONG, GFMElementTypes.STRIKETHROUGH ->
            inline(node.children.trimEdges(MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE))

        MarkdownElementTypes.CODE_SPAN ->
            node.children.trimEdges(MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.ESCAPED_BACKTICKS)
                .joinToString("") { text(it) }
                .let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }

        MarkdownElementTypes.INLINE_LINK -> {
            val label = linkText(node)
            val url = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.let { text(it).trim().removeSurrounding("<", ">") }.orEmpty()
            link(label, url)
        }

        MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val labelNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val url = labelNode?.let { definitions[normalizeLabel(text(it))] }
            when {
                url == null -> leaf(text(node))
                node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                    link(inline(labelNode.children.trimEdges(MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET)), url)
                else -> link(linkText(node), url)
            }
        }

        MarkdownElementTypes.AUTOLINK ->
            text(node).removeSurrounding("<", ">")

        MarkdownElementTypes.IMAGE ->
            node.children.firstOrNull { it.children.isNotEmpty() }?.let { linkText(it) }.orEmpty()

        GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.HTML_TAG -> text(node)

        else -> if (node.children.isEmpty()) leaf(text(node)) else inline(node.children)
    }

    private fun linkText(link: ASTNode): String =
        link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            ?.let { inline(it.children.trimEdges(MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET)) }
            .orEmpty()

    /** `text (url)`, and just the URL when that is all the text said. */
    private fun link(label: String, url: String): String = when {
        url.isBlank() -> label
        label.isBlank() || label == url || "mailto:$label" == url -> url
        else -> "$label ($url)"
    }

    /** Ordinary text, with backslash escapes and the common entities decoded. */
    private fun leaf(raw: String): String =
        ENTITY.replace(ESCAPE.replace(raw) { it.groupValues[1] }) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) ->
                    name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED_ENTITIES[name]
            } ?: m.value
        }

    // ---- Helpers ------------------------------------------------------------------

    private fun collectDefinitions(node: ASTNode) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            val url = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            if (label != null && url != null) {
                definitions.putIfAbsent(normalizeLabel(text(label)), text(url).trim().removeSurrounding("<", ">"))
            }
            return
        }
        node.children.forEach(::collectDefinitions)
    }

    private fun normalizeLabel(label: String): String =
        label.removeSurrounding("[", "]").trim().replace(WHITESPACE, " ").lowercase()

    private fun text(node: ASTNode): String = node.getTextInNode(src).toString()

    /** Drops the leading and trailing delimiter tokens (`**`, `` ` ``, `[`) around a span. */
    private fun List<ASTNode>.trimEdges(vararg types: IElementType): List<ASTNode> =
        dropWhile { it.type in types }.dropLastWhile { it.type in types }

    private companion object {
        val ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")
        val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]+);""")
        val WHITESPACE = Regex("""\s+""")
        val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©",
        )
    }
}
