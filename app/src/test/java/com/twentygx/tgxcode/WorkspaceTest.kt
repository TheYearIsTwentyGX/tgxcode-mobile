package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.Schedule
import com.twentygx.tgxcode.data.model.ScheduleGate
import com.twentygx.tgxcode.data.model.SlashCommandInfo
import com.twentygx.tgxcode.data.model.Snippet
import com.twentygx.tgxcode.data.model.SnippetParam
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.repo.appliesIn
import com.twentygx.tgxcode.data.repo.fillSnippet
import com.twentygx.tgxcode.data.repo.insertSnippet
import com.twentygx.tgxcode.ui.conversation.gitStatusWord
import com.twentygx.tgxcode.ui.conversation.laterPresets
import com.twentygx.tgxcode.ui.conversation.slashMatches
import com.twentygx.tgxcode.ui.schedules.ScheduleForm
import com.twentygx.tgxcode.ui.schedules.toBody
import com.twentygx.tgxcode.ui.snippets.SnippetForm
import com.twentygx.tgxcode.ui.snippets.toBody
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WorkspaceTest {

    private val params = listOf(
        SnippetParam(name = "branch", required = true),
        SnippetParam(name = "count", default = "5"),
    )

    @Test
    fun `a placeholder is filled, defaulted, or left as itself`() {
        val body = "Review {{branch}} with {{ count }} findings, not {{brnach}}."
        assertEquals(
            "Review feat with 5 findings, not {{brnach}}.",
            fillSnippet(body, params, mapOf("branch" to "feat")),
        )
        assertEquals(
            "an unanswered param with no default stays verbatim, never blank",
            "Review {{branch}} with 5 findings, not {{brnach}}.",
            fillSnippet(body, params, emptyMap()),
        )
    }

    @Test
    fun `snippets land where they say`() {
        assertEquals("new" to 3, insertSnippet("typed", "new", "overwrite"))
        assertEquals("typed\n\nnew", insertSnippet("typed", "new", "append").first)
        assertEquals("typed\nnew", insertSnippet("typed\n", "new", "append").first)
        assertEquals("new", insertSnippet("", "new", "append").first)
        assertEquals("tyXXped" to 4, insertSnippet("typed", "XX", "cursor", 2 until 2))
        assertEquals("replaces the selection", "tXd", insertSnippet("typed", "X", "cursor", 1 until 4).first)
    }

    @Test
    fun `projects match at a path boundary`() {
        val s = Snippet(id = "s", projects = listOf("/home/me/proj"))
        assertTrue(s.appliesIn("/home/me/proj"))
        assertTrue(s.appliesIn("/home/me/proj/web"))
        assertFalse(s.appliesIn("/home/me/proj-old"))
        assertTrue(Snippet(id = "all").appliesIn(null))
    }

    @Test
    fun `slash completion is a bare prefix list`() {
        val cmds = listOf(SlashCommandInfo("review"), SlashCommandInfo("revise-claude-md"), SlashCommandInfo("init"))
        assertEquals(listOf("review", "revise-claude-md"), slashMatches("/rev", cmds).map { it.name })
        assertTrue("past the first space it is arguments", slashMatches("/review now", cmds).isEmpty())
        assertTrue("a whole match needs no menu", slashMatches("/init", cmds).isEmpty())
        assertTrue(slashMatches("hello", cmds).isEmpty())
    }

    @Test
    fun `pausing a dontAsk schedule does not restate its mode`() {
        val s = Schedule(
            id = "x", enabled = true, cwd = "/r", prompt = "go", cron = "0 2 * * *", permissionMode = "dontAsk",
            gate = ScheduleGate(kind = "open-prs"),
        )
        val was = ScheduleForm.of(s)
        val body = was.copy(enabled = false).toBody(was)
        assertEquals(setOf("enabled"), body.keys)
        assertTrue("no change, nothing to send", was.toBody(was).isEmpty())
    }

    @Test
    fun `a new schedule sends everything, and clearing sends null`() {
        val form = ScheduleForm(cwd = "/r", prompt = "go", gate = "git-commits", ref = "origin/main")
        val body = form.toBody(null)
        assertTrue(body.keys.containsAll(listOf("cwd", "prompt", "cron", "permissionMode", "gate")))
        val json = AppJson.encodeToString(JsonObject.serializer(), body)
        assertTrue(json.contains("\"gate\":{\"kind\":\"git-commits\",\"ref\":\"origin/main\"}"))
        assertTrue("an inherit model is an explicit null", json.contains("\"model\":null"))
    }

    @Test
    fun `ungrouping a snippet sends groupId null and nothing else`() {
        val was = SnippetForm(title = "T", body = "B", groupId = "g1", permissionMode = "dontAsk")
        val json = AppJson.encodeToString(JsonObject.serializer(), was.copy(groupId = null).toBody(was))
        assertEquals("{\"groupId\":null}", json)
    }

    @Test
    fun `the whole-payload events decode`() {
        val later = SseCodec.decode(
            "later-changed",
            """{"at":1,"messages":[{"id":"m","sessionId":"s","text":"hi","permissionMode":"acceptEdits","at":5,"state":"pending","late":false}],"counts":{"total":1,"pending":1}}""",
        ) as StreamEvent.LaterChanged
        assertTrue(later.payload.messages.single().isPending)

        val snippets = SseCodec.decode(
            "snippets-changed",
            """{"snippets":[{"id":"seed-lgtm","title":"LGTM","body":"LGTM","insert":"overwrite","autoSubmit":true,"pinned":true,"params":[]}],"groups":[]}""",
        ) as StreamEvent.SnippetsChanged
        assertTrue(snippets.payload.snippets.single().autoSubmit)

        val schedules = SseCodec.decode(
            "schedules-changed",
            """{"schedules":[{"id":"x","enabled":true,"title":null,"cwd":"/r","prompt":"review it\nplease","cron":"0 2 * * *","cronText":"Every day at 2:00 AM","gate":{"kind":"open-prs","includeDrafts":true,"post":true}}]}""",
        ) as StreamEvent.SchedulesChanged
        assertEquals("review it", schedules.payload.schedules.single().displayTitle)
    }

    @Test
    fun `porcelain codes read as words`() {
        assertEquals("untracked", gitStatusWord("?"))
        assertEquals("modified", gitStatusWord(".M"))
        assertEquals("added", gitStatusWord("A."))
        assertEquals("conflict", gitStatusWord("UU"))
    }

    @Test
    fun `overnight means the coming 2am`() {
        val evening = laterPresets(LocalDateTime.of(2026, 10, 2, 22, 0)).first { it.first.startsWith("Overnight") }.second
        assertEquals(LocalDateTime.of(2026, 10, 3, 2, 0), evening)
        val small = laterPresets(LocalDateTime.of(2026, 10, 2, 1, 0)).first { it.first.startsWith("Overnight") }.second
        assertEquals(LocalDateTime.of(2026, 10, 2, 2, 0), small)
    }
}
