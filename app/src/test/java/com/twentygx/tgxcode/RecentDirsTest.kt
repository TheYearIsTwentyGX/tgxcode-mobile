package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.Worktree
import com.twentygx.tgxcode.ui.newsession.recentDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The New-session directory list offers **projects**, never worktrees.
 *
 * A worktree is somewhere work is already in flight; starting a fresh session inside
 * one is nearly always a mistake, and a hard one to notice on a phone.
 */
class RecentDirsTest {

    private fun session(
        id: String,
        cwd: String?,
        projectCwd: String?,
        projectName: String?,
        worktree: String? = null,
    ) = SessionSummary(
        sessionId = id,
        cwd = cwd,
        projectCwd = projectCwd,
        projectName = projectName,
        worktree = worktree?.let { Worktree(name = it) },
    )

    @Test
    fun `worktree sessions collapse onto their project`() {
        val dirs = recentDirs(
            listOf(
                session(
                    "1",
                    cwd = "/home/d/Other/claude-sessions/.claude/worktrees/view-restore",
                    projectCwd = "/home/d/Other/claude-sessions",
                    projectName = "claude-sessions",
                    worktree = "view-restore",
                ),
                session(
                    "2",
                    cwd = "/home/d/Other/claude-sessions/.claude/worktrees/askui",
                    projectCwd = "/home/d/Other/claude-sessions",
                    projectName = "claude-sessions",
                    worktree = "askui",
                ),
            ),
        )

        assertEquals(1, dirs.size)
        assertEquals("/home/d/Other/claude-sessions", dirs.single().path)
        assertEquals("claude-sessions", dirs.single().label)
    }

    @Test
    fun `no worktree path is ever offered`() {
        val dirs = recentDirs(
            listOf(
                session(
                    "1",
                    cwd = "/home/d/LTCDataPlus/.claude/worktrees/add-company-flow",
                    projectCwd = "/home/d/LTCDataPlus",
                    projectName = "LTCDataPlus",
                    worktree = "add-company-flow",
                ),
            ),
        )
        assertTrue(dirs.none { it.path.contains(".claude/worktrees") })
        assertTrue(dirs.none { it.label.contains("add-company-flow") })
    }

    @Test
    fun `a session that entered a worktree later still resolves to the project`() {
        // cwd == projectCwd but a worktree name is set — the transcript-split case.
        val dirs = recentDirs(
            listOf(
                session(
                    "1",
                    cwd = "/home/d/Other/claude-sessions",
                    projectCwd = "/home/d/Other/claude-sessions",
                    projectName = "claude-sessions",
                    worktree = "notif-history",
                ),
            ),
        )
        assertEquals("/home/d/Other/claude-sessions", dirs.single().path)
        assertEquals("claude-sessions", dirs.single().label)
    }

    @Test
    fun `sessions with no project are skipped rather than offered as unknown`() {
        val dirs = recentDirs(
            listOf(
                session("1", cwd = null, projectCwd = null, projectName = "unknown"),
                session(
                    "2",
                    cwd = "/home/d/Other/tgxcode-mobile",
                    projectCwd = "/home/d/Other/tgxcode-mobile",
                    projectName = "tgxcode-mobile",
                ),
            ),
        )
        assertEquals(1, dirs.size)
        assertEquals("tgxcode-mobile", dirs.single().label)
    }

    @Test
    fun `order follows the session list, most recent project first`() {
        val dirs = recentDirs(
            listOf(
                session("1", "/a", "/a", "alpha"),
                session("2", "/b", "/b", "beta"),
                session("3", "/a", "/a", "alpha"),
            ),
        )
        assertEquals(listOf("alpha", "beta"), dirs.map { it.label })
    }

    @Test
    fun `a project with no name falls back to its directory name`() {
        val dirs = recentDirs(
            listOf(session("1", "/home/d/scratch", "/home/d/scratch", null)),
        )
        assertEquals("scratch", dirs.single().label)
    }

    @Test
    fun `temp directories are not projects`() {
        val dirs = recentDirs(
            listOf(
                session("1", "/tmp/probe-qaJigb", "/tmp/probe-qaJigb", "probe-qaJigb"),
                session("2", "/tmp", "/tmp", "tmp"),
                session("3", "/var/tmp/x", "/var/tmp/x", "x"),
                session("4", "/home/d/Real", "/home/d/Real", "Real"),
            ),
        )
        assertEquals(listOf("Real"), dirs.map { it.label })
    }

    @Test
    fun `claude machinery directories are not projects`() {
        val dirs = recentDirs(
            listOf(
                session(
                    "1",
                    "/home/d/.claude/jobs/ad1cc4c2/tmp",
                    "/home/d/.claude/jobs/ad1cc4c2/tmp",
                    "tmp",
                ),
                session("2", "/home/d/Real", "/home/d/Real", "Real"),
            ),
        )
        assertEquals(listOf("Real"), dirs.map { it.label })
    }

    /**
     * The home directory is the dangerous one: it looks like an ordinary entry and
     * is the worst possible place to start a session by accident.
     */
    @Test
    fun `a directory containing several projects is a container, not a project`() {
        val dirs = recentDirs(
            listOf(
                session("1", "/home/d", "/home/d", "d"),
                session("2", "/home/d/Alpha", "/home/d/Alpha", "Alpha"),
                session("3", "/home/d/Beta", "/home/d/Beta", "Beta"),
            ),
        )
        assertEquals(listOf("Alpha", "Beta"), dirs.map { it.label }.sorted())
    }

    @Test
    fun `a project with one nested session is still offered`() {
        // Only one child, so the parent is a project that happens to have a session
        // in a subdirectory — not a container.
        val dirs = recentDirs(
            listOf(
                session("1", "/home/d/Alpha", "/home/d/Alpha", "Alpha"),
                session("2", "/home/d/Alpha/sub", "/home/d/Alpha/sub", "sub"),
            ),
        )
        assertTrue("Alpha" in dirs.map { it.label })
    }
}
