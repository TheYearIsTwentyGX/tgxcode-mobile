package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.SudoAnswerBody
import com.twentygx.tgxcode.data.model.SudoAsk
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * A sudo password goes to the socket and nowhere else.
 *
 * Driven through the real [BridgeApi] and [BridgeRepository] — the same request code
 * every route uses — with [Tgx.tap] reading every line that would reach logcat, on every
 * outcome the bridge documents and a dropped connection besides. Each case also checks
 * the password did reach the bridge, so a test that passes because nothing was sent is
 * not possible.
 */
class SudoLoggingTest {

    private val password = "c0rrect-H0rse-battery-staple"
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

    /** The body the bridge received, which must be exactly `{"password": …}`. */
    private fun sentBody(): String {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/sudo/SF46dju6Stzl/answer", recorded.url.encodedPath)
        assertEquals("POST", recorded.method)
        return recorded.body!!.utf8()
    }

    private fun assertNeverLogged(vararg also: Any?) {
        synchronized(logged) {
            for (line in logged) assertFalse("password in a log line: $line", password in line)
        }
        for (thing in also) assertFalse("password in $thing", password in thing.toString())
    }

    @Test
    fun `the body carries the password and nothing logs it, on every outcome`() = runBlocking {
        for ((code, json) in listOf(
            200 to """{"ok":true}""",
            403 to """{"error":"remote callers may not answer sudo","remote":true}""",
            409 to """{"error":"already answered"}""",
            500 to """{"error":"boom"}""",
        )) {
            logged.clear()
            answer(code, json)
            val result = api.answerSudo("SF46dju6Stzl", password)
            assertEquals(
                password,
                AppJson.decodeFromString<SudoAnswerBody>(sentBody()).password,
            )
            if (code == 200) {
                assertTrue(result is ApiResult.Ok)
            } else {
                assertEquals(code, (result as ApiResult.Http).status)
                // The failure *was* logged — the tap sees lines — just not the body.
                assertTrue(logged.toString(), logged.any { "/api/sudo/SF46dju6Stzl/answer -> $code" in it })
            }
            assertNeverLogged(result)
        }
    }

    @Test
    fun `an unreachable bridge logs its exception without the body`() = runBlocking {
        // A port nothing listens on any more: the request fails in the socket layer.
        val gone = MockWebServer().apply { start() }
        val config = BridgeConfig(gone.url("/"), "t".repeat(43))
        gone.close()
        val offline = BridgeApi(BridgeClients({ config.token })) { config }
        val result = offline.answerSudo("SF46dju6Stzl", password)
        assertTrue(result.toString(), result is ApiResult.Offline)
        assertTrue(logged.toString(), logged.any { it.startsWith("request failed") })
        assertNeverLogged(result, (result as ApiResult.Offline).cause.stackTraceToString())
    }

    @Test
    fun `the repository's messages say what happened, not what was typed`() = runBlocking {
        val messages = Collections.synchronizedList(mutableListOf<UserMessage>())
        val collecting = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repo.messages.collect { messages += it }
        }
        val ask = SudoAsk(id = "SF46dju6Stzl", sessionId = "s1")

        answer(403, """{"error":"remote callers may not answer sudo","remote":true}""")
        assertFalse(repo.answerSudo(ask, password))
        answer(500, """{"error":"boom"}""")
        assertFalse(repo.answerSudo(ask, password))
        answer(409, """{"error":"already answered"}""")
        assertTrue(repo.answerSudo(ask, password))
        repeat(3) { sentBody() }
        collecting.cancel()

        assertEquals(3, messages.size)
        assertTrue(messages[0].text, "Approve at the desktop" in messages[0].text)
        assertNeverLogged(*messages.toTypedArray())
    }

    @Test
    fun `the body's own toString is redacted`() {
        val body = SudoAnswerBody(password)
        assertFalse(password in body.toString())
        // Serialization reads the field, not toString.
        assertTrue(password in AppJson.encodeToString(SudoAnswerBody.serializer(), body))
    }

    @Test
    fun `deny sends no body worth the name`() = runBlocking {
        answer(200, """{"ok":true}""")
        assertTrue(api.denySudo("SF46dju6Stzl") is ApiResult.Ok)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/sudo/SF46dju6Stzl/deny", recorded.url.encodedPath)
        assertEquals("{}", recorded.body!!.utf8())
    }
}
