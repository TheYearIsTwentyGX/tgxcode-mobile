package com.twentygx.tgxcode.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * [MarkdownText]'s one-line sibling: a single [Text] of inline markdown (emphasis, code
 * spans, links) in the caller's [style], so it truncates with [maxLines] like any other
 * text. Same parser, same span and link styling and link handling as the block renderer;
 * only the block layer is dropped.
 *
 * Line breaks are folded to spaces first, so a stray `-`, `#` or `>` at the start of a
 * line cannot turn the text into a list or heading. Anything that still does not parse
 * as a single paragraph falls back to plain text.
 */
@Composable
fun MarkdownInline(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val flat = remember(text) { text.trim().replace(NEWLINES, " ") }
    CompositionLocalProvider(
        LocalMarkdownColors provides tgxMarkdownColors(),
        LocalMarkdownTypography provides tgxMarkdownTypography(),
    ) {
        val settings = annotatorSettings()
        val styled = remember(flat, settings) {
            val paragraph = MarkdownParser(FLAVOUR).buildMarkdownTreeFromString(flat)
                .children.singleOrNull { it.type == MarkdownElementTypes.PARAGRAPH }
            if (paragraph == null) AnnotatedString(flat)
            else buildAnnotatedString { buildMarkdownAnnotatedString(flat, paragraph, settings) }
        }
        Text(styled, modifier, color = color, style = style, maxLines = maxLines, overflow = overflow)
    }
}

private val FLAVOUR = GFMFlavourDescriptor()
private val NEWLINES = Regex("""\s*\n\s*""")
