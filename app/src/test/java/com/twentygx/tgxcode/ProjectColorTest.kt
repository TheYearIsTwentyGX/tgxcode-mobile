package com.twentygx.tgxcode

import androidx.compose.ui.graphics.Color
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.ui.theme.parseHex
import com.twentygx.tgxcode.ui.theme.projectColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectColorTest {

    private val colors = mapOf(
        "/home/you/proj" to "#ff0000",
        "/home/you/proj/.claude/worktrees/own" to "#00ff00",
        "/home/you/bad" to "red",
    )

    @Test
    fun `a project and its worktrees wear its colour`() {
        assertEquals(Color(0xFFFF0000), projectColor("/home/you/proj", colors))
        assertEquals(Color(0xFFFF0000), projectColor("/home/you/proj/.claude/worktrees/feature", colors))
        assertEquals(Color(0xFFFF0000), projectColor("/home/you/proj/", colors))
    }

    @Test
    fun `the boundary is a path segment, not a string prefix`() {
        assertNull(projectColor("/home/you/project", colors))
    }

    @Test
    fun `the longest root wins, so a worktree's own colour is kept`() {
        assertEquals(Color(0xFF00FF00), projectColor("/home/you/proj/.claude/worktrees/own/web", colors))
    }

    @Test
    fun `no colour, a relative path, or a value that is not hex draws nothing`() {
        assertNull(projectColor("/elsewhere", colors))
        assertNull(projectColor("proj", colors))
        assertNull(projectColor(null, colors))
        assertNull(projectColor("/home/you/bad", colors))
        assertEquals(Color(0xFFAABBCC), parseHex("#abc"))
    }

    @Test
    fun `the prefs event carries the colours`() {
        val event = SseCodec.decode(
            "prefs",
            """{"version":1,"projects":{"colors":{"/home/you/proj":"#8541ec"},"backdropTint":true}}""",
        ) as StreamEvent.PrefsChanged
        assertEquals("#8541ec", event.prefs.projects?.colors?.get("/home/you/proj"))
    }
}
