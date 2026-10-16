package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.net.SseSignal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The Cloudflare Access service-token headers reach every client.
 *
 * Every client means every one, because `BridgeSse` builds its own `Request` and the
 * notification action uses its own client — a header that only rides on `api` would
 * leave the stream refused, and a refused stream is a bridge with no viewer.
 */
class AccessHeadersTest {

    private val server = MockWebServer()

    /** Set in [setUp]: `server.url` needs a started server. */
    @Volatile private lateinit var config: BridgeConfig

    private val clients = BridgeClients(
        tokenProvider = { config.token },
        accessProvider = { config.access },
    )

    @Before
    fun setUp() {
        server.start()
        config = configWith("id.access", "s3cret")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `both headers go out on every client`() {
        for (client in listOf(clients.api, clients.stream, clients.heldStream, clients.notificationAction)) {
            val recorded = call(client)
            assertEquals("id.access", recorded.headers["CF-Access-Client-Id"])
            assertEquals("s3cret", recorded.headers["CF-Access-Client-Secret"])
            // Alongside the bridge's own, not instead of them.
            assertEquals("Bearer ${"t".repeat(43)}", recorded.headers["Authorization"])
            assertEquals("1", recorded.headers["X-TGXCode-Client"])
        }
    }

    @Test
    fun `the event stream carries them`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body("event: hello\ndata: {\"clientId\":\"c1\"}\n\n")
                .build(),
        )
        val sse = BridgeSse(clients) { config }
        withTimeout(5_000) { sse.stream().first { it is SseSignal.Open } }
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/events", recorded.url.encodedPath)
        assertEquals("id.access", recorded.headers["CF-Access-Client-Id"])
        assertEquals("s3cret", recorded.headers["CF-Access-Client-Secret"])
    }

    @Test
    fun `a half-set pair sends neither`() {
        config = configWith("id.access", "")
        call(clients.api).assertNoAccessHeaders()

        config = configWith("", "s3cret")
        call(clients.api).assertNoAccessHeaders()
    }

    @Test
    fun `empty fields mean off`() {
        config = configWith("", "")
        call(clients.api).assertNoAccessHeaders()
    }

    @Test
    fun `a change is picked up on the next call`() {
        config = configWith("", "")
        call(clients.api).assertNoAccessHeaders()

        config = configWith("new.access", "fresh")
        val recorded = call(clients.api)
        assertEquals("new.access", recorded.headers["CF-Access-Client-Id"])
        assertEquals("fresh", recorded.headers["CF-Access-Client-Secret"])
    }

    @Test
    fun `a logged config shows neither secret`() {
        val text = configWith("id.access", "s3cret").toString()
        assertTrue(text, "s3cret" !in text)
        assertTrue(text, "t".repeat(43) !in text)
    }

    // ---------------------------------------------------------------- fixture

    private fun configWith(id: String, secret: String) =
        BridgeConfig(server.url("/"), "t".repeat(43), id, secret)

    private fun call(client: OkHttpClient): RecordedRequest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "application/json")
                .body("{}")
                .build(),
        )
        client.newCall(Request.Builder().url(config.url("api/overview")).build()).execute().close()
        return server.takeRequest(5, TimeUnit.SECONDS)!!
    }

    private fun RecordedRequest.assertNoAccessHeaders() {
        assertNull(headers["CF-Access-Client-Id"])
        assertNull(headers["CF-Access-Client-Secret"])
    }
}
