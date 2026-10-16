package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.net.SseSignal
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.ConnState
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Telling Cloudflare Access's refusals apart from the bridge's.
 *
 * Every shape here is one `docs/api.md` §*Cloudflare Access, when it is in front*
 * names. The cases that matter most are the near-misses: the bridge's own JSON 401
 * must stay "not paired", and a 530 from a tunnel with nothing behind it must stay
 * "offline" — neither is the service token's fault.
 */
class AccessChallengeTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var respond: (RecordedRequest) -> MockResponse = { json(200, "{}") }

    private val eventOpens = AtomicInteger()
    private val polls = AtomicInteger()

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    // ------------------------------------------------------------ the classifier

    @Test
    fun `a redirect to the Access login is AccessDenied, and is not followed`() = api { api ->
        respond = { accessRedirect() }
        assertEquals(ApiResult.AccessDenied(302), api.overview())
        // One request, to us. Following it would have sent the client secret to
        // cloudflareaccess.com — OkHttp only strips Authorization across hosts.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect to any other host is AccessDenied`() = api { api ->
        respond = {
            MockResponse.Builder().code(302)
                .setHeader("Location", "https://login.example.net/somewhere").build()
        }
        assertEquals(ApiResult.AccessDenied(302), api.overview())
    }

    @Test
    fun `a 403 HTML page is AccessDenied`() = api { api ->
        respond = { html(403) }
        assertEquals(ApiResult.AccessDenied(403), api.overview())
    }

    @Test
    fun `a strict-mode 401 with an empty body is AccessDenied, not NotPaired`() = api { api ->
        respond = { MockResponse.Builder().code(401).build() }
        assertEquals(ApiResult.AccessDenied(401), api.overview())
    }

    @Test
    fun `a 403 with a cf-access header is AccessDenied even as JSON`() = api { api ->
        respond = {
            json(403, """{"error":"forbidden"}""").newBuilder()
                .setHeader("cf-access-domain", "tgxcode.com").build()
        }
        assertEquals(ApiResult.AccessDenied(403), api.overview())
    }

    @Test
    fun `a login page served as 200 is AccessDenied`() = api { api ->
        respond = { html(200) }
        assertEquals(ApiResult.AccessDenied(200), api.overview())
    }

    @Test
    fun `the bridge's own JSON 401 stays a plain 401`() = api { api ->
        respond = { json(401, """{"error":"unauthorized","hint":"send the token"}""") }
        val result = api.overview()
        assertTrue(result.toString(), result is ApiResult.Http && result.status == 401)
    }

    @Test
    fun `the bridge's own JSON 403 stays a plain 403`() = api { api ->
        respond = { json(403, """{"error":"remote callers may not do that"}""") }
        val result = api.overview()
        assertTrue(result.toString(), result is ApiResult.Http && result.status == 403)
    }

    @Test
    fun `a 530 from a tunnel with no bridge is not Access`() = api { api ->
        respond = { html(530) }
        val result = api.overview()
        assertTrue(result.toString(), result is ApiResult.Http && result.status == 530)
    }

    @Test
    fun `the stream reports a redirect to Access as accessDenied`() = runBlocking {
        respond = { accessRedirect() }
        server.dispatcher = router()
        server.start()
        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        val sse = BridgeSse(BridgeClients({ config.token }), { config })
        val closed = withTimeout(5_000) { sse.stream().first { it is SseSignal.Closed } }
        assertTrue(closed.toString(), (closed as SseSignal.Closed).accessDenied)
    }

    @Test
    fun `the stream reports a 200 login page as accessDenied`() = runBlocking {
        respond = { html(200) }
        server.dispatcher = router()
        server.start()
        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        val sse = BridgeSse(BridgeClients({ config.token }), { config })
        val closed = withTimeout(5_000) { sse.stream().first { it is SseSignal.Closed } }
        assertTrue(closed.toString(), (closed as SseSignal.Closed).accessDenied)
    }

    // ------------------------------------------------------------ the repository

    /**
     * The case the whole feature exists for. A refused `/api/events` must not leave the
     * app on a yellow dot retrying, nor fall back to polling and look healthy — the
     * bridge has no viewer, so every ask is being auto-denied, and the user has to be
     * told why.
     */
    @Test
    fun `a refused stream is AccessDenied, terminal, and does not poll`() = repo { repo ->
        respond = { accessRedirect() }
        assertEquals(ConnState.AccessDenied, repo.awaitConnection(ConnState.AccessDenied))

        val opens = eventOpens.get()
        val pollsBefore = polls.get()
        // Well past the hello watchdog and several poll intervals.
        delay(1_500)
        assertEquals("the stream must not be retried", opens, eventOpens.get())
        assertEquals("nothing should poll a refused edge", pollsBefore, polls.get())
        assertEquals(ConnState.AccessDenied, repo.connection.value)
    }

    @Test
    fun `strict Access answering 401 everywhere is AccessDenied, not NotPaired`() = repo { repo ->
        respond = { html(401) }
        assertEquals(ConnState.AccessDenied, repo.awaitConnection(ConnState.AccessDenied))
        delay(800)
        assertEquals(ConnState.AccessDenied, repo.connection.value)
    }

    @Test
    fun `a probe that gets through clears AccessDenied`() = repo { repo ->
        respond = { accessRedirect() }
        repo.awaitConnection(ConnState.AccessDenied)

        respond = { json(200, """{"sessions":[]}""") }
        val state = repo.probePairing()
        assertTrue(state.toString(), state != ConnState.AccessDenied && state != ConnState.NotPaired)
    }

    // ---------------------------------------------------------------- fixture

    private fun api(body: suspend (BridgeApi) -> Unit) = runBlocking {
        server.dispatcher = router()
        server.start()
        val config = BridgeConfig(server.url("/"), "t".repeat(43), "id.access", "s3cret")
        val clients = BridgeClients({ config.token }, { config.access })
        body(BridgeApi(clients) { config })
    }

    private fun repo(body: suspend (BridgeRepository) -> Unit) = runBlocking {
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
        val lease = repo.acquire()
        try {
            body(repo)
        } finally {
            lease.close()
        }
    }

    private fun router() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.url.encodedPath == "/api/events") eventOpens.incrementAndGet() else polls.incrementAndGet()
            return respond(request)
        }
    }

    private fun accessRedirect() = MockResponse.Builder().code(302)
        .setHeader(
            "Location",
            "https://team.cloudflareaccess.com/cdn-cgi/access/login/tgxcode.com?kid=abc&redirect_url=%2Fapi%2Fevents",
        )
        .build()

    private fun html(code: Int) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .body("<!DOCTYPE html><title>Sign in ・ Cloudflare Access</title>")
        .build()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private suspend fun BridgeRepository.awaitConnection(want: ConnState): ConnState =
        withTimeout(10_000) { connection.first { it == want } }
}
