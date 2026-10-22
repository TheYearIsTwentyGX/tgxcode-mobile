package com.twentygx.tgxcode

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app does when the network will not carry a stream.
 *
 * The failure being defended against is silent and therefore untestable by observing
 * an error: the request succeeds, the content type is right, and then nothing arrives.
 * `bodyDelay` reproduces it exactly — headers land, the body is held — which is what
 * a Cloudflare quick tunnel was measured doing for 75 seconds while serving ordinary
 * requests in 60ms.
 *
 * One fixture rather than three files: every case here needs the same buffered
 * `/api/events` plus a routed set of ordinary GETs, and duplicating that setup three
 * times would be the least interesting part of the file.
 */
class PollingFallbackTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many times `/api/events` has been opened, i.e. whether it is being retried. */
    private val eventOpens = AtomicInteger()

    /** Every ordinary GET, so a test can prove polling stopped. */
    private val polls = AtomicInteger()

    /** Swapped between rounds to drive a change through the poll. */
    @Volatile private var overviewBody = board(waiting = 0, ask = null)

    @Volatile private var runnerBody = runner(mode = "acceptEdits", ask = null)

    /** Flipped to make the bridge start refusing the token. */
    @Volatile private var refuseToken = false

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `a stream that opens and sends nothing falls back to polling`() = harness { repo ->
        assertEquals(ConnState.Polling, repo.awaitConnection(ConnState.Polling))

        // The point of holding the socket: we keep reopening it rather than giving up,
        // because `clients.size > 0` on the bridge is what stops asks being auto-denied.
        assertTrue("expected /api/events to be held open", eventOpens.get() >= 1)

        // And the board arrived by asking.
        val board = repo.board.first { it != null }
        assertEquals(2, board!!.sessions.size)
    }

    @Test
    fun `a board that differs only in its timestamp does not emit`() = harness { repo ->
        repo.awaitConnection(ConnState.Polling)
        val first = repo.board.first { it != null }!!

        // Same content, new clock. `at` changes on every single read, so keying on it
        // would rebuild the list under the reader's thumb every few seconds.
        overviewBody = board(waiting = 0, ask = null, at = 999_999)
        delay(600)
        assertEquals("a new `at` alone must not emit", first.at, repo.board.value!!.at)

        // A real change still gets through, which is the half that matters for
        // notifications: BridgeService reconciles off this flow and nothing else.
        overviewBody = board(waiting = 1, ask = "req-1", at = 1_000_000)
        val changed = await { repo.board.first { it?.waiting == 1 } }
        assertEquals("req-1", changed!!.sessions.first().ask?.requestId)
    }

    @Test
    fun `an ask noticed by a poll is announced as if it had been pushed`() = harness { repo ->
        repo.awaitConnection(ConnState.Polling)
        repo.openSession("s1")

        // Concurrent, because it is appended from the polling coroutine and read here.
        val seen = CopyOnWriteArrayList<StreamEvent>()
        val collector = scope.launch { repo.askEvents.collect { seen.add(it) } }

        runnerBody = runner(mode = "acceptEdits", ask = "req-7")
        val raised = await { repo.conversation.first { it?.ask != null } }
        assertEquals("req-7", raised!!.ask!!.requestId)
        // pendingPermission carries no sessionId of its own; without stamping one in,
        // every answer would POST to a null session.
        assertEquals("s1", raised.ask.sessionId)

        // The liveness read is `?tail=0`, whose `truncated` describes the whole
        // transcript. Folding it in would put a "load all" button over a conversation
        // already fully in hand.
        assertNull("the liveness read must not touch truncated", raised.truncated)
        assertEquals(1, raised.events.size)

        runnerBody = runner(mode = "acceptEdits", ask = null)
        val cleared = await { repo.conversation.first { it?.ask == null } }
        // Polling never learns *how* it was answered — the outcome rides on the event we
        // are not receiving — so the neutral word is the honest one.
        assertEquals("answered", cleared!!.lastOutcome)

        // Wait for the announcement, not for the state change that precedes it by a
        // line. Notifications ride on askEvents and on nothing else, so both edges
        // having been announced is the actual claim here.
        await { while (seen.none { it is StreamEvent.PermissionResolved }) delay(20) }
        collector.cancel()

        assertEquals(
            listOf("PermissionRequested", "PermissionResolved"),
            seen.map { it::class.simpleName },
        )
    }

    @Test
    fun `a polled runner keeps its permission mode`() = harness { repo ->
        repo.awaitConnection(ConnState.Polling)
        repo.openSession("s1")
        val convo = await { repo.conversation.first { it?.runner != null } }
        // If this drops to null, `modeFor` falls through to "auto" and the next message
        // sent quietly drops the session out of acceptEdits — an absent permissionMode
        // means "set it to auto", not "leave it alone".
        assertEquals("acceptEdits", convo!!.runner!!.permissionMode)
    }

    @Test
    fun `a refused token stops the poll rather than being retried forever`() = harness { repo ->
        repo.awaitConnection(ConnState.Polling)

        refuseToken = true
        repo.awaitConnection(ConnState.NotPaired)

        // Whatever was already in flight when the token was refused still lands — a
        // read that has left cannot be recalled, and each of those merely 401s once.
        // The claim is about what happens *after* that: nothing.
        delay(600)
        val settled = repo.pollRequestsOver(900)

        // A token the bridge refuses will keep being refused. The stream loop bails out
        // on this; a poll that did not would spend the battery rediscovering the same
        // 401 every couple of seconds until the app was killed.
        assertEquals("polling should have stopped, not slowed", 0, settled)
        assertEquals(ConnState.NotPaired, repo.connection.value)
    }

    // ---------------------------------------------------------------- fixture

    private fun harness(body: suspend (BridgeRepository) -> Unit) = runBlocking {
        server.dispatcher = router()
        server.start()

        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        val clients = BridgeClients({ config.token })
        val repo = BridgeRepository(
            api = BridgeApi(clients) { config },
            sse = BridgeSse(clients) { config },
            scope = scope,
            // Short enough that the test does not sit for six seconds, long enough
            // that it is not racing the first request.
            helloGraceMillis = 400,
            pollMillis = 150,
        )
        val lease = repo.acquire()
        try {
            body(repo)
        } finally {
            lease.close()
        }
    }

    private fun router() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            if (refuseToken && path != "/api/events") {
                polls.incrementAndGet()
                return MockResponse.Builder().code(401)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"error":"unauthorized"}""").build()
            }
            if (path != "/api/events") polls.incrementAndGet()
            val ok = { json: String ->
                MockResponse.Builder().code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(json).build()
            }
            return when {
                path == "/api/events" -> {
                    eventOpens.incrementAndGet()
                    // The whole point: a 200 with the right content type whose body
                    // never arrives. `hello` is written by the bridge immediately and
                    // held by the proxy, so this is what the client actually sees.
                    MockResponse.Builder().code(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .bodyDelay(60, TimeUnit.SECONDS)
                        .body("event: hello\ndata: {\"clientId\":\"c1\"}\n\n")
                        .build()
                }
                path == "/api/overview" -> ok(overviewBody)
                path == "/api/health" -> ok("""{"ok":true,"remote":true,"sessions":2}""")
                path == "/api/sessions" -> ok("""{"sessions":[]}""")
                path == "/api/suggestions" -> ok("""{"suggestions":[]}""")
                path == "/api/drafts" -> ok("""{"drafts":[]}""")
                path.endsWith("/since") -> ok("""{"events":[],"offset":10,"reset":false}""")
                path.endsWith("/suggestions") -> ok("""{"suggestions":{}}""")
                path.startsWith("/api/sessions/") -> ok(sessionDetail(request))
                else -> ok("{}")
            }
        }
    }

    /**
     * `?tail=0` returns everything but the transcript; `?tail=120` is the open read.
     * Modelling both is what lets the ask test prove the liveness call leaves
     * `truncated` alone.
     */
    private fun sessionDetail(request: RecordedRequest): String {
        val tail = request.url.queryParameter("tail")
        val events = if (tail == "0") {
            """[]"""
        } else {
            """[{"kind":"assistant","id":"e1","ts":"2026-01-01T00:00:00Z","text":"hi"}]"""
        }
        val truncated = if (tail == "0") ""","truncated":{"dropped":1,"total":1}""" else ""
        return """{"summary":{"sessionId":"s1","title":"One"},"events":$events,""" +
            """"offset":10,"runner":$runnerBody$truncated}"""
    }

    private fun board(waiting: Int, ask: String?, at: Long = 1L): String {
        val askJson = ask?.let { ""","ask":{"requestId":"$it","kind":"tool","tool":"Bash"}""" } ?: ""
        return """{"at":$at,"ready":true,"waiting":$waiting,"running":0,"sessions":[""" +
            """{"sessionId":"s1","title":"One","reason":"here"$askJson},""" +
            """{"sessionId":"s2","title":"Two","reason":"here"}]}"""
    }

    private fun runner(mode: String, ask: String?): String {
        val askJson = ask?.let {
            ""","pendingPermission":{"requestId":"$it","kind":"tool","tool":"Bash"}"""
        } ?: ""
        return """{"sessionId":"s1","state":"busy","permissionMode":"$mode"$askJson}"""
    }

    /** How many ordinary GETs arrive in [millis] — 0 means the poll really has stopped. */
    private suspend fun BridgeRepository.pollRequestsOver(millis: Long): Int {
        val before = polls.get()
        delay(millis)
        return polls.get() - before
    }

    private suspend fun BridgeRepository.awaitConnection(want: ConnState): ConnState =
        withTimeout(10_000) { connection.first { it == want } }

    /** Every wait in this file is bounded, so a regression fails rather than hangs. */
    private suspend fun <T> await(block: suspend () -> T): T = withTimeout(10_000) { block() }
}
