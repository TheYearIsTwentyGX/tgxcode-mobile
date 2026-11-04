package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.background.BackgroundRules
import com.twentygx.tgxcode.data.background.BackgroundRules.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of "does this network carry a live stream?". */
class StreamVerdictRulesTest {

    @Test
    fun `a slow or failed connect does not mark the network as buffering`() {
        assertFalse(BackgroundRules.verdictAfter(false, Evidence.ConnectSlowOrFailed))
    }

    @Test
    fun `a response that opens and sends no hello does`() {
        assertTrue(BackgroundRules.verdictAfter(false, Evidence.OpenedWithoutHello))
    }

    @Test
    fun `a prompt hello clears the verdict`() {
        assertFalse(BackgroundRules.verdictAfter(true, Evidence.HelloPrompt))
    }

    @Test
    fun `a late hello proves nothing either way`() {
        assertTrue(BackgroundRules.verdictAfter(true, Evidence.HelloLate))
        assertFalse(BackgroundRules.verdictAfter(false, Evidence.HelloLate))
        // And a connect failing on a network already judged to buffer does not undo it.
        assertTrue(BackgroundRules.verdictAfter(true, Evidence.ConnectSlowOrFailed))
    }

    @Test
    fun `a change of network, origin or screen state resets it`() {
        for (e in listOf(Evidence.NetworkChanged, Evidence.OriginChanged, Evidence.Foreground, Evidence.Reconnect, Evidence.Reprobe)) {
            assertFalse("$e", BackgroundRules.verdictAfter(true, e))
        }
    }

    @Test
    fun `hello is prompt only within the grace period`() {
        assertEquals(Evidence.HelloPrompt, BackgroundRules.helloEvidence(300, 6_000))
        assertEquals(Evidence.HelloPrompt, BackgroundRules.helloEvidence(6_000, 6_000))
        assertEquals(Evidence.HelloLate, BackgroundRules.helloEvidence(6_001, 6_000))
    }

    @Test
    fun `only a phone that gave up retries on returning to the foreground`() {
        assertTrue(BackgroundRules.retryOnForeground(buffered = true))
        assertFalse(BackgroundRules.retryOnForeground(buffered = false))
    }

    @Test
    fun `a buffering network is re-tested with growing gaps up to a cap`() {
        val base = 2 * 60_000L
        assertEquals(base, BackgroundRules.reprobeDelayMillis(base, 0))
        assertEquals(base * 2, BackgroundRules.reprobeDelayMillis(base, 1))
        assertEquals(base * 4, BackgroundRules.reprobeDelayMillis(base, 2))
        assertEquals(BackgroundRules.MAX_REPROBE_MILLIS, BackgroundRules.reprobeDelayMillis(base, 30))
        // Never zero, never negative: a hot loop of reconnects is the failure to avoid.
        assertTrue(BackgroundRules.reprobeDelayMillis(base, -3) >= base)
    }
}
