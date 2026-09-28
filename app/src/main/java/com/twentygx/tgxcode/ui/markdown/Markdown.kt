package com.twentygx.tgxcode.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The only file in the app that imports the markdown *renderer*. The one other
 * markdown import is [markdownToPlainText], beside this file, which walks the parser's
 * AST directly.
 *
 * Wrapped deliberately so the choice stays reversible: if the renderer ever fights
 * the theme, the fallback is a hand-rolled `AnnotatedString` pass over the same AST
 * (`org.jetbrains:markdown`, which this is built on), and it touches this package only.
 *
 * Worth knowing: the phone client has **no syntax highlighting** — `mobile.js`
 * imports `markdown.js` and never `highlight.js` — so the hardest requirement a
 * markdown renderer usually carries simply is not one here.
 *
 * All headings flatten to one size, matching mobile.css, where h1/h2/h3 are all
 * 15px. A phone transcript is not a document and a large H1 mid-conversation reads
 * as a mistake.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val heading = MaterialTheme.typography.bodyLarge

    /**
     * Parsed on this thread, during composition — not on a background dispatcher a
     * frame or two later.
     *
     * The convenience overload parses asynchronously and renders an *empty* loading
     * slot while it waits, so an assistant row is 0dp tall on the frame it first
     * appears. Two bugs fall out of that and the transcript had both. A scroll to the
     * end back-fills through rows it measures as weightless and lands at the top
     * instead. And reading back through history makes every row pop in and shove the
     * ones below it, because the list measured them empty first.
     *
     * The cost is a synchronous parse per row. It is bounded by what a LazyColumn
     * composes — the handful of visible rows plus prefetch, never the 120-event tail —
     * and a phone-sized message parses in well under a frame.
     *
     * `immediate` is not on the Material 3 overload, so the state is built here and
     * handed to it; that lands on the same renderer the string overload calls anyway,
     * one step down. Every other default — GFM flavour, its parser, link lookup, no
     * retained state — is identical either way.
     *
     * Worth knowing: `immediate` does not replace the async parse, it precedes it. The
     * state still re-parses once in the background and arrives at the same result, so
     * there is no second `Loading` frame to flicker through.
     */
    val state = rememberMarkdownState(content = text, immediate = true)

    Markdown(
        markdownState = state,
        modifier = modifier,
        colors = markdownColor(
            text = MaterialTheme.colorScheme.onSurface,
            codeBackground = tgx.s2,
            inlineCodeBackground = tgx.s2,
            dividerColor = tgx.outlineSoft,
            tableBackground = tgx.s2,
        ),
        typography = markdownTypography(
            h1 = heading,
            h2 = heading,
            h3 = heading,
            h4 = heading,
            h5 = heading,
            h6 = heading,
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            ordered = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyMedium,
            list = MaterialTheme.typography.bodyMedium,
            code = TgxType.mono12,
            inlineCode = TgxType.mono125,
            quote = MaterialTheme.typography.bodyMedium,
        ),
    )
}
