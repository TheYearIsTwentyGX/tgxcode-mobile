package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.background.BackgroundRules
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The verdict "this network will not carry a live stream", end to end against a mock
 * bridge. The bug it guards: one slow connect, or one bad minute, left the app polling
 * and showing the banner until the process died, because nothing but a change of origin
 * ever cleared the verdict and the clock behind it started before the socket was open.
 */
class StreamVerdictTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opens = AtomicInteger()
    private val states = CopyOnWriteArrayList<ConnState>()

    /** What `/api/events` does for the Nth open (1-based). */
    @Volatile private var events: (Int) -> MockResponse = { prompt() }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    /**
     * `hello` at once, then pings trickling for about a minute so the stream stays open
     * as the real bridge's does — a body that simply ended would look like a drop.
     */
    private fun prompt(headersDelayMs: Long = 0): MockResponse {
        val hello = "event: hello\ndata: {\"clientId\":\"c1\"}\n\n"
        return MockResponse.Builder().code(200)
            .setHeader("Content-Type", "text/event-stream")
            .headersDelay(headersDelayMs, TimeUnit.MILLISECONDS)
            .body(hello + ": ping\n\n".repeat(400))
            .throttleBody(hello.length.toLong(), 1, TimeUnit.SECONDS)
            .build()
    }

    /** Headers land, the body is held: what a buffering proxy does. */
    private fun buffered() = MockResponse.Builder().code(200)
        .setHeader("Content-Type", "text/event-stream")
        .bodyDelay(60, TimeUnit.SECONDS)
        .body("event: hello\ndata: {\"clientId\":\"c1\"}\n\n")
        .build()

    // ---------------------------------------------------------------- the old bug

    @Test
    fun `a connect slower than the grace period is not a buffering network`() = harness { repo ->
        // Headers take 1.2s against a 400ms grace: DNS/TLS/radio-wake slowness. The old
        // timer started before the socket opened and called this buffering.
        events = { prompt(headersDelayMs = 1_200) }
        awaitState(repo, ConnState.Live)
        delay(700)
        assertFalse("must never have fallen back to polling", states.contains(ConnState.Polling))
        assertEquals(1, opens.get())
    }

    @Test
    fun `a connect that fails is offline, not buffering`() = harness { repo ->
        events = { MockResponse.Builder().code(503).build() }
        awaitState(repo, ConnState.Offline)
        delay(1_200) // three grace periods
        assertFalse(states.contains(ConnState.Polling))
        assertFalse(states.contains(ConnState.Live))
    }

    // --------------------------------------------------------------- self-healing

    @Test
    fun `a hello on the held socket heals and goes back to the normal stream once`() = harness { repo ->
        // Open 1: hello held back past the grace period -> verdict "buffered".
        // Open 2 (held): the network is fine again, hello comes at once -> heal.
        // Open 3 (normal client): hello -> Live, polling stops.
        events = { n -> if (n == 1) buffered() else prompt() }
        val resyncs = CopyOnWriteArrayList<Unit>()
        val hellos = CopyOnWriteArrayList<StreamEvent>()
        val c = scope.launch { repo.resyncs.collect { resyncs.add(it) } }
        val h = scope.launch { repo.events.collect { if (it is StreamEvent.Hello) hellos.add(it) } }
        awaitState(repo, ConnState.Polling)
        awaitState(repo, ConnState.Live)
        delay(800) // long enough for a stray resync, a reopen, or polling to show itself
        c.cancel()
        h.cancel()

        assertEquals("slow open, held open, normal reopen — and no more", 3, opens.get())
        assertEquals(ConnState.Live, repo.connection.value)
        // Only the normal stream's hello is handled. The one on the held socket proved
        // the transport, and handling it as well would resync twice.
        assertEquals("hellos handled: $hellos", 1, hellos.size)
        // The poll that covered the gap may or may not have finished its own before the
        // hello stopped it; what matters is that there is no third.
        assertTrue("resyncs: ${resyncs.size}", resyncs.size in 1..2)
    }

    @Test
    fun `a network change clears a buffered verdict and reopens normally`() = harness { repo ->
        events = { n -> if (n <= 2) buffered() else prompt() }
        awaitState(repo, ConnState.Polling)
        awaitEventOpens(2) // normal then held
        repo.reconnect(BackgroundRules.Evidence.NetworkChanged)
        awaitState(repo, ConnState.Live)
        // And the banner state never flickered through Offline/Connecting on the way.
        val afterPolling = states.dropWhile { it != ConnState.Polling }
        assertFalse(afterPolling.contains(ConnState.Connecting))
        assertFalse(afterPolling.contains(ConnState.Offline))
    }

    @Test
    fun `returning to the foreground retries a buffered verdict`() = harness { repo ->
        events = { n -> if (n <= 2) buffered() else prompt() }
        awaitState(repo, ConnState.Polling)
        awaitEventOpens(2)
        repo.onForeground()
        awaitState(repo, ConnState.Live)
    }

    @Test
    fun `foreground leaves a live stream alone`() = harness { repo ->
        awaitState(repo, ConnState.Live)
        repo.onForeground()
        delay(500)
        assertEquals(1, opens.get())
    }

    // ------------------------------------------------------ a truly buffering proxy

    @Test
    fun `a truly buffering transport is re-tested and returns to polling without flapping`() = harness(reprobe = 250) { repo ->
        events = { buffered() }
        awaitState(repo, ConnState.Polling)
        // normal, held, then re-tests: each is another normal open, then held again.
        awaitEventOpens(6)
        val afterFirstPolling = states.dropWhile { it != ConnState.Polling }
        assertTrue(
            "the banner state must not flap during re-tests: $afterFirstPolling",
            afterFirstPolling.all { it == ConnState.Polling },
        )
        assertEquals(ConnState.Polling, repo.connection.value)
    }

    // ---------------------------------------------------------------- fixture

    private fun harness(reprobe: Long = 60_000, body: suspend (BridgeRepository) -> Unit) = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/api/events") return events(opens.incrementAndGet())
                val ok = { json: String ->
                    MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(json).build()
                }
                return when (path) {
                    "/api/overview" -> ok("""{"at":1,"ready":true,"waiting":0,"running":0,"sessions":[]}""")
                    "/api/health" -> ok("""{"ok":true,"remote":true,"sessions":0}""")
                    "/api/sessions" -> ok("""{"sessions":[]}""")
                    "/api/suggestions" -> ok("""{"suggestions":[]}""")
                    "/api/drafts" -> ok("""{"drafts":[]}""")
                    else -> ok("{}")
                }
            }
        }
        server.start()
        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        val clients = BridgeClients({ config.token })
        val repo = BridgeRepository(
            api = BridgeApi(clients) { config },
            sse = BridgeSse(clients) { config },
            scope = scope,
            helloGraceMillis = 400,
            pollMillis = 100,
            reprobeMillis = reprobe,
        )
        val watcher = scope.launch { repo.connection.collect { states.add(it) } }
        val lease = repo.acquire()
        try {
            body(repo)
        } finally {
            lease.close()
            watcher.cancel()
        }
    }

    private suspend fun awaitState(repo: BridgeRepository, want: ConnState) =
        withTimeout(10_000) { repo.connection.first { it == want } }

    private suspend fun awaitEventOpens(n: Int) = withTimeout(15_000) {
        while (opens.get() < n) delay(20)
    }
}
