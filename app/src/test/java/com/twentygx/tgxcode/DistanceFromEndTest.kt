package com.twentygx.tgxcode

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import com.twentygx.tgxcode.ui.conversation.distanceFromEnd
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The predicate the conversation's scroll-to-bottom rests on.
 *
 * Worth a unit test rather than an instrumentation one because the bug it replaces was
 * arithmetic, not rendering: the old version answered "we are already at the end" for a
 * list that had never been measured, so opening a session scrolled nowhere. Compose's
 * test rule idles and auto-advances the clock, which is exactly what hides that — but
 * these two interfaces are pure Kotlin, so the arithmetic can simply be asked directly.
 */
class DistanceFromEndTest {

    private fun item(index: Int, offset: Int, size: Int) = object : LazyListItemInfo {
        override val index = index
        override val key: Any = "e$index"
        override val offset = offset
        override val size = size
        override val contentType: Any? = null
    }

    private fun layout(
        visible: List<LazyListItemInfo>,
        totalItems: Int,
        viewportEnd: Int = 1_000,
    ) = object : LazyListLayoutInfo {
        override val visibleItemsInfo = visible
        override val viewportStartOffset = 0
        override val viewportEndOffset = viewportEnd
        override val totalItemsCount = totalItems
        override val viewportSize = IntSize(400, viewportEnd)
        override val orientation = Orientation.Vertical
        override val reverseLayout = false
        override val beforeContentPadding = 0
        override val afterContentPadding = 0
        override val mainAxisItemSpacing = 0
    }

    /**
     * The regression test for the reported bug. Nothing has been measured yet, which is
     * the state the scroll effect can legitimately observe on the frame a session
     * opens — and an unmeasured list is emphatically *not* at the end.
     */
    @Test
    fun `an unmeasured list is as far from the end as it gets`() {
        assertEquals(Int.MAX_VALUE, layout(emptyList(), totalItems = 120).distanceFromEnd())
    }

    @Test
    fun `the newest event being off screen is the same answer, whatever the offsets`() {
        val info = layout(
            listOf(item(0, offset = 0, size = 300), item(1, offset = 300, size = 300)),
            totalItems = 120,
        )
        assertEquals(Int.MAX_VALUE, info.distanceFromEnd())
    }

    @Test
    fun `the newest event ending flush with the fold is at the end`() {
        val info = layout(
            listOf(item(118, offset = 400, size = 200), item(119, offset = 600, size = 400)),
            totalItems = 120,
            viewportEnd = 1_000,
        )
        assertEquals(0, info.distanceFromEnd())
    }

    /** The keyboard, or the ask slot, taking the bottom of the viewport away. */
    @Test
    fun `the newest event overhanging the fold reports how far`() {
        val info = layout(
            listOf(item(119, offset = 600, size = 460)),
            totalItems = 120,
            viewportEnd = 1_000,
        )
        assertEquals(60, info.distanceFromEnd())
    }

    /**
     * A short transcript that does not fill the screen. Negative, not positive — the
     * scroll effect tests `> 0`, so this correctly asks for nothing and the content
     * stays top-aligned rather than sinking onto the composer.
     */
    @Test
    fun `content shorter than the viewport is already at the end`() {
        val info = layout(
            listOf(item(0, offset = 0, size = 120), item(1, offset = 120, size = 80)),
            totalItems = 2,
            viewportEnd = 1_000,
        )
        assertEquals(-800, info.distanceFromEnd())
    }
}
