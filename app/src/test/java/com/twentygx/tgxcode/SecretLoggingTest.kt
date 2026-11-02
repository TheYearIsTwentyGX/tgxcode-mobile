package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.SecretAnswerBody
import com.twentygx.tgxcode.data.model.SecretAsk
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.BridgeSse
import com.twentygx.tgxcode.data.repo.BridgeRepository
import com.twentygx.tgxcode.data.repo.UserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * A secret's value goes to the socket and nowhere else, and not even there over a
 * connection that would carry it in clear text.
 *
 * Driven through the real [BridgeApi] and [BridgeRepository], the same request code every
 * route uses, with [Tgx.tap] reading every line that would reach logcat, on every outcome
 * the bridge documents and a dropped connection besides. Each case also checks the value
 * did reach the bridge, so a test that passes because nothing was sent is not possible.
 */
class SecretLoggingTest {

    private val value = "fake-not-a-credential-c0rrect-H0rse"
    private val server = MockWebServer()
    private val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var api: BridgeApi
    private lateinit var repo: BridgeRepository

    @Before
    fun setUp() {
        server.start()
        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        val clients = BridgeClients({ config.token })
        api = BridgeApi(clients) { config }
        repo = BridgeRepository(api, BridgeSse(clients) { config }, scope)
        Tgx.tap = { logged += it }
    }

    @After
    fun tearDown() {
        Tgx.tap = null
        scope.cancel()
        server.close()
    }

    private fun answer(code: Int, json: String) = server.enqueue(
        MockResponse.Builder().code(code)
            .setHeader("Content-Type", "application/json")
            .body(json)
            .build(),
    )

    /** The body the bridge received, which must be exactly `{"value": …}`. */
    private fun sentBody(): String {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/secrets/asks/q1Yv3kE7xRzA/answer", recorded.url.encodedPath)
        assertEquals("POST", recorded.method)
        return recorded.body!!.utf8()
    }

    private fun assertNeverLogged(vararg also: Any?) {
        synchronized(logged) {
            for (line in logged) assertFalse("value in a log line: $line", value in line)
        }
        for (thing in also) assertFalse("value in $thing", value in thing.toString())
    }

    @Test
    fun `the body carries the value and nothing logs it, on every outcome`() = runBlocking {
        for ((code, json) in listOf(
            200 to """{"ok":true}""",
            400 to """{"error":"the value is larger than 16 KB"}""",
            403 to """{"error":"answering a secret ask from a phone is off","remote":true}""",
            409 to """{"error":"K is already set; change it at your desk","exists":true}""",
            500 to """{"error":"could not save the secret"}""",
        )) {
            logged.clear()
            answer(code, json)
            val result = api.answerSecret("q1Yv3kE7xRzA", value)
            val sent = sentBody()
            assertEquals(value, AppJson.decodeFromString<SecretAnswerBody>(sent).value)
            assertEquals("the value and nothing else", """{"value":"$value"}""", sent)
            if (code == 200) {
                assertTrue(result is ApiResult.Ok)
            } else {
                assertEquals(code, (result as ApiResult.Http).status)
                // The failure *was* logged — the tap sees lines — just not the body.
                assertTrue(logged.toString(), logged.any { "/api/secrets/asks/q1Yv3kE7xRzA/answer -> $code" in it })
            }
            assertNeverLogged(result)
        }
    }

    @Test
    fun `an unreachable bridge logs its exception without the body`() = runBlocking {
        val gone = MockWebServer().apply { start() }
        val config = BridgeConfig(gone.url("/"), "t".repeat(43))
        gone.close()
        val offline = BridgeApi(BridgeClients({ config.token })) { config }
        val result = offline.answerSecret("q1Yv3kE7xRzA", value)
        assertTrue(result.toString(), result is ApiResult.Offline)
        assertTrue(logged.toString(), logged.any { it.startsWith("request failed") })
        assertNeverLogged(result, (result as ApiResult.Offline).cause.stackTraceToString())
    }

    @Test
    fun `nothing is sent to an origin that is not encrypted`() = runBlocking {
        // Nothing listens there. A request that went out would come back Offline, so the
        // status 0 sentence can only be the guard's, returned before any socket.
        val config = BridgeConfig("http://192.0.2.10:45888/".toHttpUrl(), "t".repeat(43))
        val cleartext = BridgeApi(BridgeClients({ config.token })) { config }
        assertFalse(cleartext.encryptedOrigin())
        val result = cleartext.answerSecret("q1Yv3kE7xRzA", value)
        assertEquals(0, (result as ApiResult.Http).status)
        assertTrue(result.error!!, "not encrypted" in result.error!!)
        assertNeverLogged(result)
    }

    @Test
    fun `with no bridge configured nothing is sent either`() = runBlocking {
        val none = BridgeApi(BridgeClients({ null })) { null }
        assertFalse(none.encryptedOrigin())
        assertNull(none.originHost())
        assertEquals(0, (none.answerSecret("q1Yv3kE7xRzA", value) as ApiResult.Http).status)
    }

    @Test
    fun `the repository's messages say what happened, not what was typed`() = runBlocking {
        val messages = Collections.synchronizedList(mutableListOf<UserMessage>())
        val collecting = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repo.messages.collect { messages += it }
        }
        val ask = SecretAsk(id = "q1Yv3kE7xRzA", sessionId = "s1", name = "K")

        answer(403, """{"error":"answering a secret ask from a phone is off — turn it on in Settings › Secrets at your desk","remote":true}""")
        assertFalse(repo.answerSecret(ask, value))
        answer(500, """{"error":"could not save the secret"}""")
        assertFalse(repo.answerSecret(ask, value))
        answer(409, """{"error":"that secret ask was already answered or has gone"}""")
        assertTrue("already over, so the card stays down", repo.answerSecret(ask, value))
        answer(409, """{"error":"K is already set; change it at your desk","exists":true}""")
        assertFalse("still open, so the card stays up", repo.answerSecret(ask, value))
        repeat(4) { sentBody() }
        collecting.cancel()

        assertEquals(4, messages.size)
        assertTrue(messages[0].text, "Settings › Secrets" in messages[0].text)
        assertEquals("Already answered somewhere else", messages[2].text)
        assertTrue(messages[3].text, "already set" in messages[3].text)
        assertTrue(messages[3].isError)
        assertNeverLogged(*messages.toTypedArray())
    }

    @Test
    fun `the body's own toString is redacted`() {
        val body = SecretAnswerBody(value)
        assertFalse(value in body.toString())
        // Serialization reads the field, not toString.
        assertTrue(value in AppJson.encodeToString(SecretAnswerBody.serializer(), body))
    }

    @Test
    fun `deny sends no body worth the name`() = runBlocking {
        answer(200, """{"ok":true}""")
        assertTrue(api.denySecret("q1Yv3kE7xRzA") is ApiResult.Ok)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/secrets/asks/q1Yv3kE7xRzA/deny", recorded.url.encodedPath)
        assertEquals("{}", recorded.body!!.utf8())
    }
}
