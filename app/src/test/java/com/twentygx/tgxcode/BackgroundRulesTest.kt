package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.background.BackgroundRules
import com.twentygx.tgxcode.data.background.BackgroundRules.Fallback
import com.twentygx.tgxcode.data.background.BackgroundRules.Fix
import com.twentygx.tgxcode.data.background.BackgroundRules.Pulse
import com.twentygx.tgxcode.data.background.BackgroundStatus
import com.twentygx.tgxcode.data.install.Claim
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallProgress
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.net.SseSignal
import com.twentygx.tgxcode.data.net.StreamPulse
import com.twentygx.tgxcode.data.repo.ConnState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Installs with the app closed: what the fallback check does with each queue it reads,
 * when the stream reconnects and how long it waits, when a "live" stream is really dead,
 * and what the Settings card says.
 */
class BackgroundRulesTest {

    private fun record(id: String, state: String, createdAt: Long = 1, progress: PhoneInstallProgress? = null) =
        PhoneInstall(id = id, state = state, createdAt = createdAt, progress = progress)

    private fun claim(id: String, outbox: PhoneInstallStatus? = null, waiting: Boolean = false) =
        Claim(id = id, claimedAt = 1, outbox = outbox, waitingForPermission = waiting)

    private fun fallback(
        installs: List<PhoneInstall>?,
        claims: List<Claim> = emptyList(),
        enabled: Boolean = true,
        canInstall: Boolean = true,
        inHand: Set<String> = emptySet(),
    ) = BackgroundRules.fallback(installs, claims, enabled, canInstall, inHand)

    // ---------------------------------------------------------- the fallback check

    @Test
    fun `no answer from the bridge is not an empty queue`() {
        assertEquals(Fallback.Unreachable, fallback(null))
    }

    @Test
    fun `an empty queue, or one with only finished records, is idle`() {
        assertEquals(Fallback.Idle, fallback(emptyList()))
        assertEquals(
            Fallback.Idle,
            fallback(
                listOf(
                    record("a", PhoneInstallState.INSTALLED),
                    record("b", PhoneInstallState.CANCELLED),
                    record("c", PhoneInstallState.FAILED),
                ),
            ),
        )
    }

    @Test
    fun `pending records are picked up, oldest first`() {
        val found = fallback(
            listOf(
                record("new", PhoneInstallState.PENDING, createdAt = 20),
                record("old", PhoneInstallState.PENDING, createdAt = 10),
            ),
        )
        assertEquals(Fallback.Work(listOf("old", "new"), emptyList()), found)
    }

    @Test
    fun `our own unfinished download is resumed, somebody else's is not`() {
        val installs = listOf(
            record("ours", PhoneInstallState.DOWNLOADING),
            record("theirs", PhoneInstallState.DOWNLOADING),
        )
        assertEquals(Fallback.Work(listOf("ours"), emptyList()), fallback(installs, listOf(claim("ours"))))
    }

    @Test
    fun `a claim with an unsent report is settled, not claimed again`() {
        val unsent = PhoneInstallStatus(PhoneInstallState.FAILED, "cut off")
        val found = fallback(listOf(record("a", PhoneInstallState.DOWNLOADING)), listOf(claim("a", outbox = unsent)))
        assertEquals(Fallback.Work(emptyList(), listOf("a")), found)
    }

    @Test
    fun `a self-update the desk still calls installing is settled`() {
        val found = fallback(listOf(record("self", PhoneInstallState.INSTALLING)), listOf(claim("self")))
        assertEquals(Fallback.Work(emptyList(), listOf("self")), found)
    }

    @Test
    fun `a claim waiting on the grant is settled only once the grant is there`() {
        val installs = listOf(record("a", PhoneInstallState.NEEDS_ACTION))
        val claims = listOf(claim("a", waiting = true))
        assertEquals(Fallback.Idle, fallback(installs, claims, canInstall = false))
        assertEquals(Fallback.Work(emptyList(), listOf("a")), fallback(installs, claims, canInstall = true))
    }

    @Test
    fun `an install waiting on a tap is left alone`() {
        assertEquals(Fallback.Idle, fallback(listOf(record("a", PhoneInstallState.NEEDS_ACTION)), listOf(claim("a"))))
    }

    @Test
    fun `work already in hand is not started twice`() {
        val installs = listOf(record("a", PhoneInstallState.PENDING), record("b", PhoneInstallState.PENDING))
        assertEquals(Fallback.Work(listOf("b"), emptyList()), fallback(installs, inHand = setOf("a")))
        assertEquals(Fallback.Idle, fallback(installs, inHand = setOf("a", "b")))
    }

    @Test
    fun `installs switched off picks nothing up but still settles what is owed`() {
        val installs = listOf(record("p", PhoneInstallState.PENDING), record("a", PhoneInstallState.DOWNLOADING))
        assertEquals(Fallback.Disabled, fallback(installs, enabled = false))
        val unsent = PhoneInstallStatus(PhoneInstallState.INSTALLED)
        assertEquals(
            Fallback.Work(emptyList(), listOf("a")),
            fallback(installs, listOf(claim("a", outbox = unsent)), enabled = false),
        )
    }

    @Test
    fun `the summary reads as a sentence for the card`() {
        assertEquals("2 to install, 1 to settle", Fallback.Work(listOf("a", "b"), listOf("c")).summary)
        assertEquals("nothing waiting", Fallback.Idle.summary)
    }

    // ---------------------------------------------------------- reconnecting

    @Test
    fun `backoff is immediate after a good run and capped with jitter after that`() {
        assertEquals(0L, BackgroundRules.backoffMillis(0))
        repeat(50) {
            val first = BackgroundRules.backoffMillis(1, Random(it))
            assertTrue(first in 250L..500L)
            val late = BackgroundRules.backoffMillis(40, Random(it))
            assertTrue(late in BackgroundRules.MAX_BACKOFF_MILLIS / 2..BackgroundRules.MAX_BACKOFF_MILLIS)
        }
    }

    @Test
    fun `a run that reached hello resets the count, one that did not climbs`() {
        assertEquals(0, BackgroundRules.nextAttempt(5, sawHello = true, buffered = false, sawOpen = true))
        assertEquals(6, BackgroundRules.nextAttempt(5, sawHello = false, buffered = false, sawOpen = true))
        // Buffered: hello never comes, so the socket opening is the success.
        assertEquals(0, BackgroundRules.nextAttempt(5, sawHello = false, buffered = true, sawOpen = true))
        assertEquals(6, BackgroundRules.nextAttempt(5, sawHello = false, buffered = true, sawOpen = false))
    }

    @Test
    fun `only a real change of network reconnects`() {
        // Registering reports the network already in use.
        assertFalse(BackgroundRules.reconnectOnNetwork(null, 100, first = true))
        // Wi-Fi to cellular.
        assertTrue(BackgroundRules.reconnectOnNetwork(100, 200, first = false))
        // The same network again (a capabilities refresh, say).
        assertFalse(BackgroundRules.reconnectOnNetwork(100, 100, first = false))
        // Lost: nothing to reconnect on.
        assertFalse(BackgroundRules.reconnectOnNetwork(100, null, first = false))
        // Back after none.
        assertTrue(BackgroundRules.reconnectOnNetwork(null, 100, first = false))
    }

    @Test
    fun `a live stream with no byte for three pings is stale`() {
        val now = 1_000_000L
        assertFalse(BackgroundRules.streamStale(ConnState.Live, now - 30_000, now))
        assertTrue(BackgroundRules.streamStale(ConnState.Live, now - 10 * 60_000, now))
        // Nothing has ever arrived: not evidence of anything yet.
        assertFalse(BackgroundRules.streamStale(ConnState.Live, 0, now))
        // Buffered mode: silence is expected.
        assertFalse(BackgroundRules.streamStale(ConnState.Polling, now - 10 * 60_000, now))
    }

    @Test
    fun `the pulse reconnects a dead stream or a stalled backoff, nothing else`() {
        val now = 1_000_000L
        val quiet = now - 10 * 60_000
        assertEquals(Pulse.Reconnect, BackgroundRules.pulse(ConnState.Live, quiet, now, wanted = true))
        assertEquals(Pulse.Nothing, BackgroundRules.pulse(ConnState.Live, now - 5_000, now, wanted = true))
        assertEquals(Pulse.Reconnect, BackgroundRules.pulse(ConnState.Offline, 0, now, wanted = true))
        assertEquals(Pulse.Reconnect, BackgroundRules.pulse(ConnState.Connecting, 0, now, wanted = true))
        // Refused: the next attempt would be refused the same way.
        assertEquals(Pulse.Nothing, BackgroundRules.pulse(ConnState.NotPaired, quiet, now, wanted = true))
        assertEquals(Pulse.Nothing, BackgroundRules.pulse(ConnState.AccessDenied, quiet, now, wanted = true))
        // Nobody holds the connection.
        assertEquals(Pulse.Nothing, BackgroundRules.pulse(ConnState.Offline, quiet, now, wanted = false))
    }

    @Test
    fun `the service stays awake for the start of an outage only`() {
        val now = 1_000_000L
        assertTrue(BackgroundRules.holdAwake(ConnState.Connecting, now - 10_000, now))
        assertFalse(BackgroundRules.holdAwake(ConnState.Offline, now - 10 * 60_000, now))
        assertFalse(BackgroundRules.holdAwake(ConnState.Live, null, now))
        assertFalse(BackgroundRules.holdAwake(ConnState.NotPaired, now, now))
    }

    @Test
    fun `ping comments count as life even though no event is delivered`() = runBlocking {
        val ticks = AtomicLong(1_000)
        val pulse = StreamPulse { ticks.incrementAndGet() }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .body(": ping\n\n: ping\n\n")
                    .build(),
            )
            server.start()
            val config = BridgeConfig(server.url("/"), "t".repeat(43))
            val sse = BridgeSse(BridgeClients({ config.token }, streamPulse = pulse)) { config }
            val signals = withTimeout(10_000) { sse.stream().toList() }
            assertTrue("no event was delivered", signals.none { it is SseSignal.Message })
            // The headers, and then the comment bytes themselves.
            assertTrue("expected the body's bytes to beat, got ${pulse.lastByteAt}", pulse.lastByteAt > 1_001)
        }
    }

    // ---------------------------------------------------------- the card

    private val healthy = BackgroundStatus(
        stayConnected = true,
        serviceRunning = true,
        connection = ConnState.Live,
        lastConnectedAt = 1,
        lastEventAt = 1,
        batteryExempt = true,
        canInstall = true,
        notificationsAllowed = true,
        lastCheckAt = 0,
        lastCheckResult = "nothing waiting",
    )

    private fun rows(status: BackgroundStatus, now: Long = 60_000) = BackgroundRules.rows(status, now) { "${it}ms" }

    @Test
    fun `a healthy phone has no failing row`() {
        val rows = rows(healthy)
        assertTrue(rows.joinToString { "${it.label}=${it.ok}" }, rows.all { it.ok })
        assertEquals(6, rows.size)
    }

    @Test
    fun `each failing item gets its fix`() {
        val broken = BackgroundStatus(
            stayConnected = true,
            serviceRunning = false,
            connection = ConnState.Offline,
            batteryExempt = false,
            canInstall = false,
            notificationsAllowed = false,
            lastCheckAt = null,
        )
        val fixes = rows(broken).filter { !it.ok }.map { it.fix }
        assertEquals(
            listOf(Fix.StayConnected, Fix.Reconnect, Fix.BatteryExempt, Fix.InstallPermission, Fix.Notifications, Fix.CheckNow),
            fixes,
        )
    }

    @Test
    fun `stay connected off says installs wait, and offers to turn it on`() {
        val row = rows(healthy.copy(stayConnected = false, serviceRunning = false)).first()
        assertFalse(row.ok)
        assertEquals(Fix.StayConnected, row.fix)
        assertEquals("Turn on", row.fixLabel)
    }

    @Test
    fun `a fallback check that has not run for three periods is flagged`() {
        val late = rows(healthy, now = BackgroundRules.CHECK_LATE_AFTER_MILLIS + 1).last()
        assertFalse(late.ok)
    }

    @Test
    fun `not paired offers no reconnect`() {
        val row = rows(healthy.copy(connection = ConnState.Unconfigured))[1]
        assertFalse(row.ok)
        assertNull(row.fix)
    }

    @Test
    fun `the battery prompt is asked once, and never once exempt`() {
        assertTrue(BackgroundRules.promptForBattery(exempt = false, alreadyAsked = false))
        assertFalse(BackgroundRules.promptForBattery(exempt = false, alreadyAsked = true))
        assertFalse(BackgroundRules.promptForBattery(exempt = true, alreadyAsked = false))
    }
}
