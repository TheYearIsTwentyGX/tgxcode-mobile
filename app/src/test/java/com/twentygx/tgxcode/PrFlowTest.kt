package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.repo.BridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * How PR status gets into the app, and the two ways it silently stops being true.
 *
 * Both failures here are invisible by inspection and neither produces an error:
 *
 *  1. `checkedAt` moves on every read, so leaving it in the equality test makes every
 *     poll emit and the Sessions list rebuild under the reader's thumb — which on a
 *     phone eats the tap it was aimed at.
 *  2. The stream delivers before `hello` arrives and runs `resync`, so a fetch can
 *     land *after* a push and overwrite it with an older answer. `prs-changed` fires
 *     only when the answer moves again, which on a quiet repository is twenty minutes
 *     — so that loss does not heal on its own.
 */
class PrFlowTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many times `/api/prs` has been read, so a test can prove a push sufficed. */
    private val prsReads = AtomicInteger()

    @Volatile private var prsBody = prs(status = "open", checkedAt = "2026-09-07T12:00:00.000Z")

    /** Appended to the SSE body after `hello`, or empty for no push at all. */
    @Volatile private var pushed: String = ""

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `a read populates the flow`() = harness { repo ->
        assertNull("nothing until something is read", repo.prs.value)

        repo.loadPrs()

        val payload = checkNotNull(repo.prs.value)
        assertEquals("open", payload.sessions["s1"]?.status)
        assertEquals(1, payload.sessions["s1"]?.total)
        assertEquals("2026-09-07T12:00:00.000Z", payload.checkedAt)
    }

    /**
     * The tap-eating bug, as a test.
     *
     * `checkedAt` is a max over the bridge's per-repository clocks, so it differs on
     * every single read. If it counted towards equality, `MutableStateFlow`'s dedupe
     * would never fire and the list would recompose on every poll forever.
     */
    @Test
    fun `a payload that differs only in its timestamp does not emit`() = harness { repo ->
        repo.loadPrs()
        val first = checkNotNull(repo.prs.value)

        prsBody = prs(status = "open", checkedAt = "2026-09-07T13:00:00.000Z")
        repo.loadPrs()

        assertEquals(
            "a new checkedAt alone must not emit",
            first.checkedAt,
            repo.prs.value?.checkedAt,
        )
    }

    /**
     * The other half of the same coin: `gh.error` **is** part of the comparison,
     * because a token expiring is a real change and the wording under every grey glyph
     * has to repaint when it happens.
     */
    @Test
    fun `a change in gh error does emit`() = harness { repo ->
        repo.loadPrs()

        prsBody = prs(
            status = "open",
            checkedAt = "2026-09-07T13:00:00.000Z",
            ghOk = false,
            ghError = "gh: bad credentials",
        )
        repo.loadPrs()

        assertEquals("gh: bad credentials", repo.prs.value?.gh?.error)
        assertEquals(false, repo.prs.value?.gh?.ok)
    }

    /**
     * A strictly older payload is dropped, whatever it says.
     *
     * This is the push-beats-fetch race. Without the guard, `resync`'s read landing a
     * moment after a push would replace "merged" with the "open" the bridge had
     * already moved on from — and nothing would correct it until the answer changed
     * again.
     */
    @Test
    fun `a payload older than the one held is dropped`() = harness { repo ->
        prsBody = prs(status = "merged", checkedAt = "2026-09-07T14:00:00.000Z")
        repo.loadPrs()

        prsBody = prs(status = "open", checkedAt = "2026-09-07T13:00:00.000Z")
        repo.loadPrs()

        assertEquals(
            "an older read must not overwrite a newer push",
            "merged",
            repo.prs.value?.sessions?.get("s1")?.status,
        )
    }

    /**
     * A bridge that has listed nothing yet reports `checkedAt: null`, and that is not
     * "older" — it is unknown. Treating it as older would drop the very first payload
     * of a cold start.
     */
    @Test
    fun `a null checkedAt is not treated as older`() = harness { repo ->
        prsBody = prs(status = "open", checkedAt = null)
        repo.loadPrs()

        assertEquals("open", repo.prs.value?.sessions?.get("s1")?.status)

        prsBody = prs(status = "merged", checkedAt = null)
        repo.loadPrs()

        assertEquals("merged", repo.prs.value?.sessions?.get("s1")?.status)
    }

    /**
     * The event carries the whole payload, so it applies with nothing to go and read.
     *
     * The one `/api/prs` this expects is `resync`'s, on `hello`. A second would mean
     * the client was treating the push as a nudge to refetch — which is what the
     * desktop deliberately removed, because it cost a request per index move for an
     * answer that had not changed.
     *
     * The pushed payload is deliberately *newer* than the served one, so the assertion
     * holds whichever of the two lands first — which is the point of the guard above.
     */
    @Test
    fun `prs-changed applies without a refetch`() = harness(
        // Set before the lease, because acquiring one opens the stream immediately —
        // the frame has to already be in the body the dispatcher is about to serve.
        push = "event: prs-changed\ndata: " +
            prs(status = "conflicting", checkedAt = "2026-09-07T15:00:00.000Z") + "\n\n",
    ) { repo ->
        val payload = await { repo.prs.first { it?.sessions?.get("s1")?.status == "conflicting" } }

        assertEquals("conflicting", payload?.sessions?.get("s1")?.status)
        assertEquals("a push must not trigger a read", 1, prsReads.get())
    }

    // ---------------------------------------------------------------- fixture

    /**
     * @param push an SSE frame to serve after `hello`, or empty for none. A non-empty
     *   push is what makes this take a lease and actually connect — most of these
     *   tests do not want one, because they call [BridgeRepository.loadPrs] directly
     *   and nothing should be racing the flow they assert on.
     */
    private fun harness(
        push: String = "",
        body: suspend (BridgeRepository) -> Unit,
    ) = runBlocking {
        pushed = push
        server.dispatcher = router()
        server.start()

        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        val clients = BridgeClients({ config.token })
        val repo = BridgeRepository(
            api = BridgeApi(clients) { config },
            sse = BridgeSse(clients) { config },
            scope = scope,
            helloGraceMillis = 400,
            pollMillis = 150,
        )
        val lease = if (push.isNotEmpty()) repo.acquire() else null
        try {
            body(repo)
        } finally {
            lease?.close()
        }
    }

    private fun router() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val ok = { json: String ->
                MockResponse.Builder().code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(json).build()
            }
            return when {
                path == "/api/events" -> MockResponse.Builder().code(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .body("event: hello\ndata: {\"clientId\":\"c1\"}\n\n" + pushed)
                    .build()
                path == "/api/prs" -> {
                    prsReads.incrementAndGet()
                    ok(prsBody)
                }
                path == "/api/health" -> ok("""{"ok":true,"sessions":0}""")
                path == "/api/overview" -> ok("""{"ready":true,"sessions":[]}""")
                path == "/api/sessions" -> ok("""{"sessions":[]}""")
                path == "/api/suggestions" -> ok("""{"suggestions":[]}""")
                path == "/api/drafts" -> ok("""{"drafts":[]}""")
                else -> ok("{}")
            }
        }
    }

    /**
     * **One line, and that is not cosmetic.** This doubles as an HTTP body and as an
     * SSE `data:` field, and a newline inside the latter terminates the field — the
     * continuation would be parsed as an unknown SSE field and dropped, leaving the
     * client to decode truncated JSON and quietly give up on the frame.
     */
    private fun prs(
        status: String,
        checkedAt: String?,
        ghOk: Boolean = true,
        ghError: String? = null,
    ): String {
        val error = ghError?.let { "\"$it\"" } ?: "null"
        val checked = checkedAt?.let { "\"$it\"" } ?: "null"
        return "{\"sessions\":{\"s1\":{\"status\":\"$status\",\"label\":\"$status\"," +
            "\"total\":1,\"counts\":{\"$status\":1}}}," +
            "\"gh\":{\"ok\":$ghOk,\"error\":$error},\"checkedAt\":$checked}"
    }

    private suspend fun <T> await(block: suspend () -> T): T = withTimeout(4_000) { block() }
}
