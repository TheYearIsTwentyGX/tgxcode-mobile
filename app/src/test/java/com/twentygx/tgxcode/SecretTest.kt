package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.Prefs
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SecretAnswerBody
import com.twentygx.tgxcode.data.model.SecretAsk
import com.twentygx.tgxcode.data.model.SecretCardMode
import com.twentygx.tgxcode.data.model.SecretOutcome
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.model.secretCardHint
import com.twentygx.tgxcode.data.model.secretCardMode
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.repo.Conversation
import com.twentygx.tgxcode.data.repo.withSecretFrom
import com.twentygx.tgxcode.service.AskNotifications
import com.twentygx.tgxcode.ui.conversation.SecretValue
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The secret ask, against `docs/api.md` §*Secrets*: the payloads as the bridge sends them,
 * which card it draws, and the value's short life in the card.
 */
class SecretTest {

    private val json = """{"id":"q1Yv3kE7xRzA","requestId":"q1Yv3kE7xRzA","kind":"secret","tool":"secret",
        "displayName":"Secret","sessionId":"s1","name":"STRIPE_TEST_KEY",
        "why":"To run the payment tests against Stripe's test mode.",
        "cwd":"/home/me/shop/.claude/worktrees/pay","project":"/home/me/shop",
        "exists":false,"askedAt":1793220157720,"remoteAnswer":false}"""

    private val ask = AppJson.decodeFromString<SecretAsk>(json)

    @Test
    fun `secret-ask decodes whole`() {
        val event = SseCodec.decode("secret-ask", json) as StreamEvent.SecretAsked
        assertEquals("STRIPE_TEST_KEY", event.ask.name)
        assertEquals("/home/me/shop", event.ask.project)
        assertEquals("s1", event.ask.sessionId)
        assertEquals(1793220157720, event.ask.askedAt)
        assertFalse(event.ask.remoteAnswer)
    }

    @Test
    fun `an old bridge's ask, with no remoteAnswer, reads as off`() {
        val old = AppJson.decodeFromString<SecretAsk>("""{"id":"a","name":"K"}""")
        assertFalse(old.remoteAnswer)
        assertEquals(SecretCardMode.Off, secretCardMode(old, remote = true, allowRemote = null, encrypted = true))
    }

    @Test
    fun `secret-resolved decodes, and says how it ended`() {
        val event = SseCodec.decode(
            "secret-resolved",
            """{"id":"a","requestId":"a","sessionId":"s1","name":"K","outcome":"cancelled","reason":"the agent stopped waiting"}""",
        ) as StreamEvent.SecretResolved
        assertEquals("cancelled", event.outcome)
        assertEquals("K — Withdrawn — the agent stopped waiting.", SecretOutcome.word(event.name, event.outcome, event.reason))
        assertEquals("K — Saved.", SecretOutcome.word("K", "saved"))
        assertEquals("K — Denied.", SecretOutcome.word("K", "denied"))
        assertEquals("Secret — Answered.", SecretOutcome.word(null, null))
    }

    @Test
    fun `runner status carries secretAsk`() {
        val status = AppJson.decodeFromString<RunnerStatus>("""{"sessionId":"s1","secretAsk":$json}""")
        assertEquals("STRIPE_TEST_KEY", status.secretAsk?.name)
        assertNull(status.pendingPermission)
    }

    @Test
    fun `a board card's secret ask keeps its fields`() {
        val board = AppJson.decodeFromString<Overview>("""{"sessions":[{"sessionId":"s1","ask":$json}]}""")
        val card = board.sessions.single()
        assertEquals(AskKind.Secret, card.ask?.askKind)
        assertEquals("STRIPE_TEST_KEY", card.ask?.secret?.name)
        assertEquals("/home/me/shop", card.ask?.secret?.project)
        assertTrue(card.why, "STRIPE_TEST_KEY" in card.why)
        assertNull("not mistaken for a sudo ask", card.ask?.sudo)
    }

    @Test
    fun `prefs carry secrets allowRemote, and an old bridge has none`() {
        assertEquals(true, AppJson.decodeFromString<Prefs>("""{"secrets":{"allowRemote":true}}""").secrets?.allowRemote)
        assertNull(AppJson.decodeFromString<Prefs>("""{"sudo":{"allowRemote":true}}""").secrets)
    }

    @Test
    fun `remote with the switch off is read-only, and says where to turn it on`() {
        val mode = secretCardMode(ask, remote = true, allowRemote = false, encrypted = true)
        assertEquals(SecretCardMode.Off, mode)
        val hint = secretCardHint(ask, mode)!!
        assertTrue(hint, "Turn on in Settings → Secrets at your desk" in hint)
    }

    @Test
    fun `remote with the switch on takes the value`() {
        val mode = secretCardMode(ask, remote = true, allowRemote = true, encrypted = true)
        assertEquals(SecretCardMode.Enter, mode)
        assertNull(secretCardHint(ask, mode))
    }

    @Test
    fun `the pref outranks the ask's own remoteAnswer, which it falls back to`() {
        val on = ask.copy(remoteAnswer = true)
        assertEquals(SecretCardMode.Off, secretCardMode(on, true, allowRemote = false, encrypted = true))
        assertEquals(SecretCardMode.Enter, secretCardMode(on, true, allowRemote = null, encrypted = true))
        assertEquals(SecretCardMode.Off, secretCardMode(ask, true, allowRemote = null, encrypted = true))
    }

    @Test
    fun `health not read yet counts as remote, and a local bridge may`() {
        assertEquals(SecretCardMode.Off, secretCardMode(ask, remote = null, allowRemote = null, encrypted = true))
        assertEquals(SecretCardMode.Enter, secretCardMode(ask, remote = false, allowRemote = false, encrypted = true))
    }

    @Test
    fun `a name set while the ask waited is not offered for replacing`() {
        val set = ask.copy(exists = true, remoteAnswer = true)
        val mode = secretCardMode(set, remote = true, allowRemote = true, encrypted = true)
        assertEquals(SecretCardMode.AlreadySet, mode)
        assertTrue("STRIPE_TEST_KEY" in secretCardHint(set, mode)!!)
    }

    @Test
    fun `an origin that is not encrypted beats everything, even a local bridge`() {
        for (remote in listOf(true, false, null)) {
            assertEquals(SecretCardMode.Insecure, secretCardMode(ask.copy(remoteAnswer = true), remote, true, encrypted = false))
        }
    }

    @Test
    fun `only HTTPS, loopback and the tailnet count as encrypted`() {
        fun origin(url: String) = BridgeConfig(url.toHttpUrl(), "t".repeat(43)).encryptedOrigin
        assertTrue(origin("https://tgxcode.com/"))
        assertTrue(origin("https://10.11.64.43:45888/"))
        assertTrue(origin("http://127.0.0.1:45888/"))
        assertTrue(origin("http://localhost:45888/"))
        assertTrue(origin("http://10.0.2.2:45888/"))
        assertTrue(origin("http://tg-dylanh.taild5c420.ts.net/"))
        // What the debug overlay's cleartext allowance would otherwise let through.
        assertFalse(origin("http://10.11.64.43:45888/"))
        assertFalse(origin("http://tgxcode.com/"))
        assertFalse(origin("http://evil.ts.net.example.com/"))
    }

    @Test
    fun `submitting clears the field before the send sees it`() {
        val value = SecretValue()
        value.text = "fake-typed-value"
        var sent: String? = null
        var fieldWhenSent: String? = null
        assertTrue(value.submit { sent = it; fieldWhenSent = value.text })
        assertEquals("fake-typed-value", sent)
        assertEquals("", fieldWhenSent)
        assertEquals("", value.text)
    }

    @Test
    fun `an empty field sends nothing`() {
        val value = SecretValue()
        var sent = false
        assertFalse(value.submit { sent = true })
        assertFalse(sent)
    }

    @Test
    fun `what is typed is sent exactly as typed`() {
        val value = SecretValue()
        value.text = "  fake with spaces \t"
        var sent: String? = null
        value.submit { sent = it }
        assertEquals("  fake with spaces \t", sent)
    }

    @Test
    fun `clear empties it, and no toString shows it`() {
        val value = SecretValue()
        value.text = "fake-typed-value"
        assertFalse("fake-typed-value" in value.toString())
        value.clear()
        assertEquals("", value.text)
        assertFalse("fake-typed-value" in SecretAnswerBody("fake-typed-value").toString())
    }

    private fun conversation(secretAsk: SecretAsk? = null) =
        Conversation(sessionId = "s1", summary = SessionSummary(sessionId = "s1"), secretAsk = secretAsk)

    @Test
    fun `a status without the ask takes the card down`() {
        val gone = conversation(ask).withSecretFrom(RunnerStatus(sessionId = "s1"))
        assertNull(gone.secretAsk)
        assertEquals("STRIPE_TEST_KEY — Answered.", gone.secretOutcome)
    }

    @Test
    fun `the same ask, unchanged, keeps the conversation`() {
        val open = conversation(ask)
        assertSame(open, open.withSecretFrom(RunnerStatus(sessionId = "s1", secretAsk = ask)))
    }

    @Test
    fun `a status that says the name is now set turns the card read-only`() {
        val set = ask.copy(exists = true)
        assertTrue(conversation(ask).withSecretFrom(RunnerStatus(sessionId = "s1", secretAsk = set)).secretAsk!!.exists)
    }

    @Test
    fun `the notification says the name and the agent's reason, and the lock screen neither`() {
        val body = AskNotifications.secretBody(ask)
        assertTrue(body, "STRIPE_TEST_KEY" in body)
        assertTrue(body, "payment tests" in body)
        assertEquals("STRIPE_TEST_KEY", AskNotifications.secretBody(ask.copy(why = "")))
        assertFalse("STRIPE" in AskNotifications.SECRET_LOCKED_TITLE)
    }
}
