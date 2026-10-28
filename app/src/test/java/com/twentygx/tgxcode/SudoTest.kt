package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.Prefs
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.model.SudoAsk
import com.twentygx.tgxcode.data.model.SudoCardMode
import com.twentygx.tgxcode.data.model.SudoOutcome
import com.twentygx.tgxcode.data.model.sudoCardMode
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.repo.Conversation
import com.twentygx.tgxcode.data.repo.withSudoFrom
import com.twentygx.tgxcode.ui.conversation.SudoPassword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sudo ask, against `docs/api.md` §*Approving sudo*: the payloads as the bridge
 * sends them, which card it draws, and the password's short life in the card.
 */
class SudoTest {

    /** `docs/api.md`'s own example, with `runsShell` and a retry. */
    private val askJson = """
        { "id": "SF46dju6Stzl", "requestId": "SF46dju6Stzl", "kind": "sudo",
          "tool": "sudo", "displayName": "sudo", "sessionId": "s1",
          "command": "ls -la '/root/a b'", "sudoLine": "sudo -A ls -la '/root/a b'",
          "argv": ["sudo", "-A", "ls", "-la", "/root/a b"],
          "cwd": "/home/me/project", "user": "root", "group": null, "chdir": null,
          "mode": "run", "runsShell": true,
          "context": "sudo -A ls -la '/root/a b' && make install",
          "attempt": 2, "askedAt": 1793220157720, "remoteAnswer": false }
    """.trimIndent()

    // ---------------------------------------------------------------- parsing

    @Test
    fun `sudo-ask decodes whole`() {
        val ask = (SseCodec.decode("sudo-ask", askJson) as StreamEvent.SudoAsked).ask
        assertEquals("SF46dju6Stzl", ask.id)
        assertEquals("s1", ask.sessionId)
        assertEquals("sudo -A ls -la '/root/a b'", ask.line)
        assertEquals(listOf("sudo", "-A", "ls", "-la", "/root/a b"), ask.argv)
        assertEquals("root", ask.runsAs)
        assertEquals("/home/me/project", ask.where)
        assertTrue(ask.runsShell)
        assertEquals(2, ask.attempt)
        assertEquals(1793220157720, ask.askedAt)
        assertFalse(ask.remoteAnswer)
        // The Bash command says more than the sudo line, so it is shown.
        assertEquals("sudo -A ls -la '/root/a b' && make install", ask.wholeCommand)
    }

    @Test
    fun `a Bash command that is only the sudo line is not shown twice`() {
        val ask = AppJson.decodeFromString<SudoAsk>(
            """{"id":"a","sudoLine":"sudo -A whoami","context":"sudo -A whoami "}""",
        )
        assertNull(ask.wholeCommand)
        assertNull(ask.copy(context = null).wholeCommand)
    }

    @Test
    fun `who and where read as the desktop card does`() {
        val ask = SudoAsk(id = "a", user = "www", group = "adm", cwd = "/srv", chdir = "/var/www")
        assertEquals("www:adm", ask.runsAs)
        assertEquals("/var/www (from /srv)", ask.where)
        assertEquals("root", SudoAsk(id = "b").runsAs)
        assertEquals("unknown", SudoAsk(id = "b").where)
    }

    @Test
    fun `sudo-resolved decodes, and says how it ended`() {
        val done = SseCodec.decode(
            "sudo-resolved",
            """{"id":"x","requestId":"x","sessionId":"s1","outcome":"cancelled","reason":"the Bash call timed out"}""",
        ) as StreamEvent.SudoResolved
        assertEquals("x", done.id)
        assertEquals("s1", done.sessionId)
        assertEquals("sudo — Withdrawn — the Bash call timed out.", SudoOutcome.word(done.outcome, done.reason))
        assertEquals("sudo — Password sent.", SudoOutcome.word("approved"))
        assertEquals("sudo — Denied.", SudoOutcome.word("denied"))
    }

    @Test
    fun `runner status carries sudoAsk beside pendingPermission`() {
        val status = SseCodec.decode(
            "runner-status",
            """{"sessionId":"s1","state":"busy","pendingPermission":{"requestId":"p1","kind":"tool","tool":"Bash"},
                "sudoAsk":$askJson}""",
        ) as StreamEvent.RunnerStatusChanged
        assertEquals("p1", status.status.pendingPermission?.requestId)
        assertEquals("SF46dju6Stzl", status.status.sudoAsk?.id)

        val none = AppJson.decodeFromString<RunnerStatus>("""{"sessionId":"s1","state":"idle","sudoAsk":null}""")
        assertNull(none.sudoAsk)
    }

    @Test
    fun `a board card's sudo ask keeps its sudo fields`() {
        val board = AppJson.decodeFromString<Overview>(
            """{"sessions":[{"sessionId":"s1","reason":"ask","ask":$askJson,"askFor":"you"},
                            {"sessionId":"s2","reason":"ask","ask":{"requestId":"p","kind":"tool","tool":"Bash"}}]}""",
        )
        val sudoCard = board.sessions[0]
        assertEquals(AskKind.Sudo, sudoCard.ask?.askKind)
        assertEquals("sudo -A ls -la '/root/a b'", sudoCard.ask?.sudo?.sudoLine)
        assertTrue(sudoCard.ask?.sudo?.runsShell == true)
        assertEquals("Wants your password for sudo ls -la '/root/a b'", sudoCard.why)

        // A tool ask is untouched.
        val toolCard = board.sessions[1]
        assertEquals(AskKind.Tool, toolCard.ask?.askKind)
        assertNull(toolCard.ask?.sudo)
    }

    @Test
    fun `prefs carry sudo allowRemote`() {
        val prefs = AppJson.decodeFromString<Prefs>("""{"sudo":{"enabled":true,"allowRemote":true},"projects":{}}""")
        assertTrue(prefs.sudo!!.allowRemote)
        assertNull(AppJson.decodeFromString<Prefs>("""{}""").sudo)
    }

    // -------------------------------------------------------------- the card's mode

    @Test
    fun `remote with allowRemote off is read-only`() {
        val ask = SudoAsk(id = "a", remoteAnswer = false)
        assertEquals(SudoCardMode.ReadOnly, sudoCardMode(ask, remote = true, allowRemote = false))
        assertEquals(SudoCardMode.ReadOnly, sudoCardMode(ask, remote = true, allowRemote = null))
    }

    @Test
    fun `remote with allowRemote on takes the password`() {
        val ask = SudoAsk(id = "a", remoteAnswer = false)
        assertEquals(SudoCardMode.Approve, sudoCardMode(ask, remote = true, allowRemote = true))
    }

    @Test
    fun `the pref outranks the ask's own remoteAnswer, which it falls back to`() {
        // The switch turned off after the ask was sent: the latest prefs win.
        assertEquals(SudoCardMode.ReadOnly, sudoCardMode(SudoAsk(id = "a", remoteAnswer = true), true, false))
        // No prefs read yet: the ask says.
        assertEquals(SudoCardMode.Approve, sudoCardMode(SudoAsk(id = "a", remoteAnswer = true), true, null))
    }

    @Test
    fun `a local caller always may, and health not read yet counts as remote`() {
        assertEquals(SudoCardMode.Approve, sudoCardMode(SudoAsk(id = "a"), remote = false, allowRemote = false))
        assertEquals(SudoCardMode.ReadOnly, sudoCardMode(SudoAsk(id = "a"), remote = null, allowRemote = false))
    }

    // -------------------------------------------------------------- the password

    @Test
    fun `submitting clears the field before the send sees it`() {
        val field = SudoPassword()
        field.text = "hunter2"
        var sent: String? = null
        var fieldDuringSend: String? = null
        assertTrue(field.submit { sent = it; fieldDuringSend = field.text })
        assertEquals("hunter2", sent)
        assertEquals("", fieldDuringSend)
        assertEquals("", field.text)
    }

    @Test
    fun `an empty field sends nothing`() {
        val field = SudoPassword()
        var called = false
        assertFalse(field.submit { called = true })
        assertFalse(called)
    }

    @Test
    fun `clear empties it, and toString never shows it`() {
        val field = SudoPassword()
        field.text = "hunter2"
        assertFalse("hunter2" in field.toString())
        field.clear()
        assertEquals("", field.text)
    }

    // ------------------------------------------------------- the card comes down

    private fun conversation() = Conversation(sessionId = "s1", summary = SessionSummary(sessionId = "s1"))

    @Test
    fun `a status without the ask takes the card down`() {
        val ask = AppJson.decodeFromString<SudoAsk>(askJson)
        val up = conversation().withSudoFrom(RunnerStatus(sessionId = "s1", sudoAsk = ask))
        assertEquals("SF46dju6Stzl", up.sudoAsk?.id)
        assertNull(up.sudoOutcome)

        // Same ask again: nothing changes, so nothing redraws under the user's thumb.
        assertSame(up, up.withSudoFrom(RunnerStatus(sessionId = "s1", sudoAsk = ask)))

        val down = up.withSudoFrom(RunnerStatus(sessionId = "s1", sudoAsk = null))
        assertNull(down.sudoAsk)
        assertEquals("sudo — Answered.", down.sudoOutcome)
    }

    @Test
    fun `a retry replaces the card`() {
        val ask = AppJson.decodeFromString<SudoAsk>(askJson)
        val up = conversation().withSudoFrom(RunnerStatus(sudoAsk = ask))
        val retry = up.withSudoFrom(RunnerStatus(sudoAsk = ask.copy(id = "next", attempt = 3)))
        assertEquals("next", retry.sudoAsk?.id)
        assertEquals(3, retry.sudoAsk?.attempt)
    }
}
