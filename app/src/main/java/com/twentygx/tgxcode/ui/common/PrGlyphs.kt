package com.twentygx.tgxcode.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The nine PR glyphs, transcribed from `web/app.js:830-870`.
 *
 * **Three icon families, so hue is never the only signal.** The branch shape is the
 * PR's own lifecycle, a speech bubble is a human's verdict on it, and a bare mark is
 * CI's. Two of these states are red and two are yellow; anyone who cannot separate
 * them by hue reads them by shape instead. The two settled states each leave their
 * family and take a silhouette of their own — a curve was all that separated `merged`
 * from `open`, and at this size that is nothing.
 *
 * ### Why a Canvas and not nine vector drawables
 *
 * Two of the nine are dashed: `prDraft`'s connector — the same branch, *not joined up
 * yet* — and `checkWait`'s ring. **Neither an Android `VectorDrawable` nor Compose's
 * `ImageVector` can express a dash**: the former has `trimPath*` but no dash array,
 * and the latter's `addPath` takes a stroke width, cap and join but no `PathEffect`.
 * So the two states where the dash *is* the meaning would have to be hand-authored as
 * a sequence of dots and arc segments — an approximation that will silently drift the
 * next time the web glyph changes, in a file whose neighbours (`ui/theme/Color.kt:6`)
 * were pointedly "read from source rather than approximated".
 *
 * Drawing them instead keeps every `d` string byte-for-byte from `web/app.js`, keeps
 * the circles as circles rather than two-arc path data, and expresses a dash as a
 * dash. The cost is that a `Canvas` has no `contentDescription` of its own, so
 * [PrGlyph] takes one.
 */

/** One stroked figure in the 24x24 viewport the web icons are drawn in. */
private sealed interface Fig {
    val width: Float
    val cap: StrokeCap
    val join: StrokeJoin
    val dash: FloatArray?

    /** An SVG `d` string, verbatim. */
    class P(
        d: String,
        override val width: Float = 1.8f,
        override val cap: StrokeCap = StrokeCap.Butt,
        override val join: StrokeJoin = StrokeJoin.Miter,
        override val dash: FloatArray? = null,
    ) : Fig {
        /**
         * Parsed once, not per frame, and shared across every row showing this glyph —
         * a `Path` is only ever read while drawing, and draws are serialised.
         *
         * Left on the default synchronised `lazy` deliberately: nothing guarantees the
         * draw pass stays on one thread for the life of the process, and the unsafe
         * mode can publish a half-written field to a second one. The lock is a
         * monomorphic check after the first call.
         */
        val path: Path by lazy { PathParser().parsePathString(d).toPath() }
    }

    /** `<circle>`, which has no path equivalent worth writing out. */
    class C(
        val cx: Float,
        val cy: Float,
        val r: Float,
        override val width: Float = 1.8f,
        override val cap: StrokeCap = StrokeCap.Butt,
        override val join: StrokeJoin = StrokeJoin.Miter,
        override val dash: FloatArray? = null,
    ) : Fig
}

/** Two dots and an elbow: a branch off a trunk. */
private val BRANCH_ELBOW = "M6.5 14.9V8.5a2 2 0 0 1 2-2h6.4"

/** The speech bubble both review verdicts sit in. */
private val BUBBLE =
    "M20 14.4a2.5 2.5 0 0 1-2.5 2.5H9.3L5 20.3V6.1a2.5 2.5 0 0 1 2.5-2.5h10A2.5 2.5 0 0 1 20 6.1Z"

private val PR = listOf(
    Fig.C(6.5f, 17.5f, 2.6f),
    Fig.C(17.5f, 6.5f, 2.6f),
    Fig.P(BRANCH_ELBOW, cap = StrokeCap.Round),
)

/**
 * The same branch, not joined up yet.
 *
 * Dotted rather than dashed, and that is the web comment's own reasoning: a dash
 * pattern on a path this short is invisible at this size, where round caps with gaps
 * wider than the marks change the outline instead of just its texture — and the
 * outline is the only thing that still reads.
 */
private val PR_DRAFT = listOf(
    Fig.C(6.5f, 17.5f, 2.6f),
    Fig.C(17.5f, 6.5f, 2.6f),
    Fig.P(BRANCH_ELBOW, width = 1.9f, cap = StrokeCap.Round, dash = floatArrayOf(0.1f, 3.5f)),
)

/** Landed: an arrow arriving at the trunk. */
private val PR_MERGED = listOf(
    Fig.P("M18.8 4.6v14.8", width = 2f, cap = StrokeCap.Round),
    Fig.P("M4.4 12h9.8", cap = StrokeCap.Round),
    Fig.P("m10.6 8.3 3.9 3.7-3.9 3.7", cap = StrokeCap.Round, join = StrokeJoin.Round),
)

private val PR_CLOSED = listOf(
    Fig.C(12f, 12f, 7.6f),
    Fig.P("M8.3 15.7 15.7 8.3", cap = StrokeCap.Round),
)

private val REVIEW_OK = listOf(
    Fig.P(BUBBLE, join = StrokeJoin.Round),
    Fig.P("m9.4 10.1 2 2 3.5-3.7", cap = StrokeCap.Round, join = StrokeJoin.Round),
)

private val REVIEW_CHANGES = listOf(
    Fig.P(BUBBLE, join = StrokeJoin.Round),
    Fig.P("M9.3 10.2h6.4", cap = StrokeCap.Round),
)

private val CHECK_FAIL = listOf(
    Fig.P("m6.8 6.8 10.4 10.4", width = 2.2f, cap = StrokeCap.Round),
    Fig.P("M17.2 6.8 6.8 17.2", width = 2.2f, cap = StrokeCap.Round),
)

private val CHECK_WAIT = listOf(
    Fig.C(12f, 12f, 7.6f, cap = StrokeCap.Round, dash = floatArrayOf(3.3f, 2.9f)),
)

private val CONFLICT = listOf(
    Fig.P("M12 4.3 21 19.5H3Z", join = StrokeJoin.Round),
    Fig.P("M12 9.9v3.7", cap = StrokeCap.Round),
    // A zero-length segment with a round cap, which is how the web draws the dot.
    Fig.P("M12 16.6h.01", width = 2f, cap = StrokeCap.Round),
)

/**
 * Which glyph a status wears.
 *
 * `unknown` borrows the plain branch, exactly as `PR_ICON` does — the grey is what
 * separates it, and the sheet is what explains it.
 */
private fun figuresFor(look: PrLook): List<Fig> = when (look) {
    PrLook.Open, PrLook.Unknown -> PR
    PrLook.Draft -> PR_DRAFT
    PrLook.Merged -> PR_MERGED
    PrLook.Closed -> PR_CLOSED
    PrLook.Approved -> REVIEW_OK
    PrLook.Changes -> REVIEW_CHANGES
    PrLook.ChecksFailed -> CHECK_FAIL
    PrLook.ChecksPending -> CHECK_WAIT
    PrLook.Conflicting -> CONFLICT
}

/** Half of the desktop's `breathe`, which is 1 → 0.3 → 1 over 1.6s. */
private const val BREATHE_HALF_MS = 800

/**
 * What a pull request has come to, as one glyph.
 *
 * @param contentDescription never null on a row — it merges into the row's
 *   announcement, so a row reads as "title, worktree · 5m · 12 turns, conflicts with
 *   the base branch". A status-bearing glyph with no description is a silent row for a
 *   screen-reader user. Null is right only where a neighbouring `Text` already says
 *   the same thing, as the conversation chips do.
 */
@Composable
fun PrGlyph(
    look: PrLook,
    contentDescription: String?,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val figures = figuresFor(look)
    val tint = prTint(look)
    val fade = prFade(look)
    val reduced = rememberReducedMotion()

    // Still moving, so it breathes — the same light every in-flight thing here uses.
    val alpha: State<Float> = if (look != PrLook.ChecksPending || reduced) {
        remember(fade) { mutableFloatStateOf(fade) }
    } else {
        rememberInfiniteTransition(label = "pr-breathe").animateFloat(
            initialValue = fade,
            targetValue = fade * 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(BREATHE_HALF_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pr-breathe-alpha",
        )
    }

    Canvas(
        modifier
            .size(size)
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription }
            )
    ) {
        // Reading the animated value here rather than in a Modifier.graphicsLayer
        // keeps it a draw-phase read: the frame repaints, nothing recomposes.
        val a = alpha.value
        // `scale` takes the stroke widths with it, so 1.8 at 24px stays proportional
        // at any size — and so do the dash intervals.
        scale(this.size.minDimension / 24f, pivot = Offset.Zero) {
            figures.forEach { figure -> draw(figure, tint, a) }
        }
    }
}

private fun DrawScope.draw(figure: Fig, tint: androidx.compose.ui.graphics.Color, alpha: Float) {
    val style = Stroke(
        width = figure.width,
        cap = figure.cap,
        join = figure.join,
        pathEffect = figure.dash?.let { PathEffect.dashPathEffect(it, 0f) },
    )
    when (figure) {
        is Fig.P -> drawPath(figure.path, tint, alpha = alpha, style = style)
        is Fig.C -> drawCircle(
            color = tint,
            radius = figure.r,
            center = Offset(figure.cx, figure.cy),
            alpha = alpha,
            style = style,
        )
    }
}
