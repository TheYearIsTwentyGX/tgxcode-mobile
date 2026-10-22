package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.install.Claim
import com.twentygx.tgxcode.data.install.PhoneInstallRules
import com.twentygx.tgxcode.data.install.PhoneInstallRules.RangeAnswer
import com.twentygx.tgxcode.data.install.ProgressThrottle
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallProgress
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.model.StreamEvent
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What keeps a phone install from going quiet: picking its own claims back up, the
 * heartbeat's throttle, stopping work that is no longer this phone's, and resuming a
 * download with `Range` instead of starting it again.
 */
class PhoneInstallRecoveryTest {

    // ---------------------------------------------------------------- resume selection

    @Test
    fun `catch-up takes pending and this phone's own downloading, oldest first`() {
        val installs = listOf(
            record("newest", PhoneInstallState.PENDING, createdAt = 30),
            record("mine", PhoneInstallState.DOWNLOADING, createdAt = 25),
            record("theirs", PhoneInstallState.DOWNLOADING, createdAt = 20),
            record("mine-installing", PhoneInstallState.INSTALLING, createdAt = 15),
            record("oldest", PhoneInstallState.PENDING, createdAt = 10),
            record("done", PhoneInstallState.INSTALLED, createdAt = 5),
        )
        val picked = PhoneInstallRules.toPickUp(
            installs,
            ownClaims = setOf("mine", "mine-installing"),
            inHand = emptySet(),
        )
        // `installing` is reconcile's: the result is worked out, not downloaded again.
        assertEquals(listOf("oldest", "mine", "newest"), picked.map { it.id })
    }

    @Test
    fun `what is already in hand is not picked up twice`() {
        val installs = listOf(
            record("mine", PhoneInstallState.DOWNLOADING, createdAt = 2),
            record("pending", PhoneInstallState.PENDING, createdAt = 1),
        )
        val picked = PhoneInstallRules.toPickUp(installs, ownClaims = setOf("mine"), inHand = setOf("mine", "pending"))
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a claim this phone lost is resumed, not left claimed for good`() {
        // The answer to the claim never came back, but the claim was kept first.
        val lost = record("lost", PhoneInstallState.DOWNLOADING, createdAt = 1)
        assertEquals(listOf("lost"), PhoneInstallRules.toPickUp(listOf(lost), setOf("lost"), emptySet()).map { it.id })
        // Without the persisted claim, it would never be looked at again.
        assertTrue(PhoneInstallRules.toPickUp(listOf(lost), emptySet(), emptySet()).isEmpty())
    }

    // ---------------------------------------------------------------- progress throttle

    @Test
    fun `the first progress always goes`() {
        assertTrue(ProgressThrottle().shouldSend(0, 1000, now = 0))
    }

    @Test
    fun `small steps inside two seconds are dropped`() {
        val throttle = ProgressThrottle()
        throttle.shouldSend(0, 1000, now = 0)
        assertFalse(throttle.shouldSend(10, 1000, now = 500))
        assertFalse(throttle.shouldSend(49, 1000, now = 1999))
    }

    @Test
    fun `five percent goes at once`() {
        val throttle = ProgressThrottle()
        throttle.shouldSend(0, 1000, now = 0)
        assertTrue(throttle.shouldSend(50, 1000, now = 100))
        assertFalse(throttle.shouldSend(60, 1000, now = 200))
        assertTrue(throttle.shouldSend(100, 1000, now = 300))
    }

    @Test
    fun `two seconds goes however little moved`() {
        val throttle = ProgressThrottle()
        throttle.shouldSend(0, 1_000_000, now = 0)
        assertTrue(throttle.shouldSend(1, 1_000_000, now = 2_000))
        assertFalse(throttle.shouldSend(2, 1_000_000, now = 3_000))
        assertTrue(throttle.shouldSend(3, 1_000_000, now = 4_000))
    }

    @Test
    fun `nothing goes while the count stands still, so a stall stays visible`() {
        val throttle = ProgressThrottle()
        throttle.shouldSend(100, 1000, now = 0)
        assertFalse(throttle.shouldSend(100, 1000, now = 10_000))
        assertFalse(throttle.shouldSend(100, 1000, now = 60_000))
    }

    @Test
    fun `the last byte always goes`() {
        val throttle = ProgressThrottle()
        throttle.shouldSend(0, 1000, now = 0)
        throttle.shouldSend(980, 1000, now = 10)
        assertTrue(throttle.shouldSend(1000, 1000, now = 20))
    }

    @Test
    fun `an unknown length still beats every two seconds`() {
        val throttle = ProgressThrottle()
        throttle.shouldSend(0, 0, now = 0)
        assertFalse(throttle.shouldSend(500_000, 0, now = 1_000))
        assertTrue(throttle.shouldSend(600_000, 0, now = 2_000))
    }

    // ---------------------------------------------------------------- abort when not ours

    @Test
    fun `cancelled at the desk stops the work, claimed or not`() {
        val cancelled = record("a", PhoneInstallState.CANCELLED, updatedAt = 5)
        assertTrue(PhoneInstallRules.noLongerOurs(cancelled, claimed = true, since = 10))
        assertTrue(PhoneInstallRules.noLongerOurs(cancelled, claimed = false, since = 10))
    }

    @Test
    fun `a release back to pending stops the work`() {
        val released = record("a", PhoneInstallState.PENDING, updatedAt = 200)
        assertTrue(PhoneInstallRules.noLongerOurs(released, claimed = true, since = 100))
    }

    @Test
    fun `the bridge failing it stops the work`() {
        val failed = record("a", PhoneInstallState.FAILED, updatedAt = 200)
        assertTrue(PhoneInstallRules.noLongerOurs(failed, claimed = true, since = 100))
    }

    @Test
    fun `the pending that started the work, arriving late, is not a release`() {
        val stale = record("a", PhoneInstallState.PENDING, updatedAt = 100)
        assertFalse(PhoneInstallRules.noLongerOurs(stale, claimed = true, since = 100))
        assertFalse(PhoneInstallRules.noLongerOurs(stale.copy(updatedAt = 50), claimed = true, since = 100))
    }

    @Test
    fun `before the claim lands only a cancel counts`() {
        val pending = record("a", PhoneInstallState.PENDING, updatedAt = 200)
        assertFalse(PhoneInstallRules.noLongerOurs(pending, claimed = false, since = 100))
        val failed = record("a", PhoneInstallState.FAILED, updatedAt = 200)
        assertFalse(PhoneInstallRules.noLongerOurs(failed, claimed = false, since = 100))
    }

    @Test
    fun `a pending that still carries progress is not a release`() {
        val pending = record("a", PhoneInstallState.PENDING, updatedAt = 200)
            .copy(progress = PhoneInstallProgress(10, 100))
        assertFalse(PhoneInstallRules.noLongerOurs(pending, claimed = true, since = 100))
    }

    @Test
    fun `our own heartbeats and reports coming back are not news`() {
        for (state in listOf(PhoneInstallState.DOWNLOADING, PhoneInstallState.INSTALLING, PhoneInstallState.NEEDS_ACTION)) {
            assertFalse(state, PhoneInstallRules.noLongerOurs(record("a", state, updatedAt = 500), claimed = true, since = 100))
        }
    }

    // ---------------------------------------------------------------- Range resume decisions

    @Test
    fun `a partial file resumes from its length`() {
        assertEquals(1000L, PhoneInstallRules.resumeFrom(partial = 1000, size = 4000))
    }

    @Test
    fun `nothing on disk starts at zero`() {
        assertEquals(0L, PhoneInstallRules.resumeFrom(partial = 0, size = 4000))
    }

    @Test
    fun `a file already as long as the APK, or longer, starts again`() {
        assertEquals(0L, PhoneInstallRules.resumeFrom(partial = 4000, size = 4000))
        assertEquals(0L, PhoneInstallRules.resumeFrom(partial = 5000, size = 4000))
    }

    @Test
    fun `an unknown size still resumes`() {
        assertEquals(1000L, PhoneInstallRules.resumeFrom(partial = 1000, size = 0))
    }

    @Test
    fun `a 206 from where we asked appends`() {
        assertEquals(RangeAnswer.APPEND, PhoneInstallRules.rangeAnswer(206, "bytes 1000-3999/4000", from = 1000))
    }

    @Test
    fun `a 200 means the whole file, so it starts again`() {
        assertEquals(RangeAnswer.RESTART, PhoneInstallRules.rangeAnswer(200, null, from = 1000))
        assertEquals(RangeAnswer.RESTART, PhoneInstallRules.rangeAnswer(200, null, from = 0))
    }

    @Test
    fun `a 206 for some other range is not trusted`() {
        assertEquals(RangeAnswer.MISMATCH, PhoneInstallRules.rangeAnswer(206, "bytes 0-3999/4000", from = 1000))
        assertEquals(RangeAnswer.MISMATCH, PhoneInstallRules.rangeAnswer(206, null, from = 1000))
        assertEquals(RangeAnswer.MISMATCH, PhoneInstallRules.rangeAnswer(206, "bytes 0-99/4000", from = 0))
    }

    @Test
    fun `the total comes from the content range`() {
        assertEquals(4000L, PhoneInstallRules.rangeTotal("bytes 1000-3999/4000"))
        assertNull(PhoneInstallRules.rangeTotal("bytes 1000-3999/*"))
        assertNull(PhoneInstallRules.rangeTotal(null))
    }

    // ---------------------------------------------------------------- retry and wire

    @Test
    fun `retry is offered for what stopped or stuck, not for what is moving`() {
        assertTrue(PhoneInstallRules.canRetry(record("a", PhoneInstallState.FAILED)))
        assertTrue(PhoneInstallRules.canRetry(record("a", PhoneInstallState.CANCELLED)))
        assertTrue(PhoneInstallRules.canRetry(record("a", PhoneInstallState.DOWNLOADING).copy(stalled = true)))
        assertFalse(PhoneInstallRules.canRetry(record("a", PhoneInstallState.DOWNLOADING)))
        assertFalse(PhoneInstallRules.canRetry(record("a", PhoneInstallState.PENDING)))
        assertFalse(PhoneInstallRules.canRetry(record("a", PhoneInstallState.INSTALLED)))
    }

    @Test
    fun `a heartbeat carries its progress`() {
        val json = AppJson.encodeToString(
            PhoneInstallStatus.serializer(),
            PhoneInstallStatus(PhoneInstallState.DOWNLOADING, progress = PhoneInstallProgress(512, 4096)),
        )
        assertEquals("""{"state":"downloading","progress":{"bytes":512,"total":4096}}""", json)
    }

    @Test
    fun `the new record fields decode`() {
        val event = SseCodec.decode(
            "phone_install",
            """{"id":"a1","state":"downloading","progress":{"bytes":10,"total":40},"stalled":true,"attempts":2}""",
        ) as StreamEvent.PhoneInstallChanged
        assertEquals(PhoneInstallProgress(10, 40), event.install.progress)
        assertTrue(event.install.stalled)
        assertEquals(2, event.install.attempts)
        assertEquals(0.25f, event.install.progress!!.fraction!!, 0.001f)
    }

    @Test
    fun `an undelivered report survives in the claim`() {
        val claims = listOf(Claim("a", 1, outbox = PhoneInstallStatus(PhoneInstallState.FAILED, "cut off")))
        assertEquals(claims, Claim.decode(Claim.encode(claims)))
    }

    // ---------------------------------------------------------------- the download itself

    private val server = MockWebServer()

    @Volatile private lateinit var config: BridgeConfig

    private val clients = BridgeClients(tokenProvider = { config.token })
    private val api = BridgeApi(clients) { config }

    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val apkSha = sha256(apk)

    @Before
    fun setUp() {
        server.start()
        config = BridgeConfig(server.url("/"), "t".repeat(43))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a 206 is added to the partial file and the hash covers all of it`() = runBlocking {
        val dest = tempFile().apply { writeBytes(apk.copyOfRange(0, 50_000)) }
        server.enqueue(
            MockResponse.Builder().code(206)
                .setHeader("Content-Range", "bytes 50000-199999/200000")
                .setHeader("X-SHA256", apkSha)
                .body(okio.Buffer().write(apk.copyOfRange(50_000, apk.size)))
                .build(),
        )
        val seen = mutableListOf<Pair<Long, Long>>()
        val result = api.downloadPhoneInstall("a1", dest, resumeFrom = 50_000, sha256 = apkSha) { b, t -> seen += b to t }
        val got = (result as ApiResult.Ok).value
        assertEquals(apk.size.toLong(), got.size)
        assertEquals(apkSha, got.sha256)
        assertEquals(50_000L, got.resumedFrom)
        assertTrue(apk.contentEquals(dest.readBytes()))
        // Progress counts what was already on disk, against the whole length.
        assertEquals(50_000L to 200_000L, seen.first())
        assertEquals(200_000L to 200_000L, seen.last())

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("bytes=50000-", recorded.headers["Range"])
        assertEquals("\"$apkSha\"", recorded.headers["If-Range"])
    }

    @Test
    fun `a 200 to a resume starts the file again`() = runBlocking {
        val dest = tempFile().apply { writeBytes(ByteArray(50_000) { 7 }) }
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(apk)).build())
        val got = (api.downloadPhoneInstall("a1", dest, resumeFrom = 50_000, sha256 = apkSha) as ApiResult.Ok).value
        assertEquals(apk.size.toLong(), got.size)
        assertEquals(apkSha, got.sha256)
        assertEquals(0L, got.resumedFrom)
        assertTrue(apk.contentEquals(dest.readBytes()))
    }

    @Test
    fun `a fresh download sends no Range`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(apk)).build())
        api.downloadPhoneInstall("a1", tempFile(), resumeFrom = 0, sha256 = apkSha)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(recorded.headers["Range"])
        assertNull(recorded.headers["If-Range"])
    }

    @Test
    fun `a transfer cut off keeps what arrived for the next attempt`() = runBlocking {
        val dest = tempFile()
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(okio.Buffer().write(apk))
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        val result = api.downloadPhoneInstall("a1", dest, sha256 = apkSha)
        assertTrue(result.toString(), result is ApiResult.Offline)
        assertTrue(dest.exists())
        assertTrue(dest.length() < apk.size)
    }

    @Test
    fun `a stalled transfer fails the attempt instead of waiting on the socket`() = runBlocking {
        val dest = tempFile()
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(okio.Buffer().write(apk))
                .onResponseBody(SocketEffect.Stall)
                .build(),
        )
        val started = System.currentTimeMillis()
        val result = api.downloadPhoneInstall("a1", dest, sha256 = apkSha, stallMillis = 1_500)
        val took = System.currentTimeMillis() - started
        assertTrue(result.toString(), result is ApiResult.Offline)
        assertTrue((result as ApiResult.Offline).cause.message.orEmpty().contains("stalled"))
        // The read timeout is a minute; this is the watchdog.
        assertTrue("took ${took}ms", took < 10_000)
    }

    @Test
    fun `an APK gone from the desk drops the partial file`() = runBlocking {
        val dest = tempFile().apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse.Builder().code(410).body("""{"error":"gone"}""").build())
        val result = api.downloadPhoneInstall("a1", dest, resumeFrom = 10, sha256 = apkSha)
        assertEquals(410, (result as ApiResult.Http).status)
        assertFalse(dest.exists())
    }

    @Test
    fun `retry posts to its route`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(410).body("""{"error":"the APK is gone"}""").build())
        val result = api.retryPhoneInstall("a1")
        assertEquals(410, (result as ApiResult.Http).status)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/phone-installs/a1/retry", recorded.url.encodedPath)
    }

    // ---------------------------------------------------------------- fixture

    private fun record(
        id: String,
        state: String = PhoneInstallState.PENDING,
        createdAt: Long = 0,
        updatedAt: Long = 0,
    ) = PhoneInstall(id = id, state = state, createdAt = createdAt, updatedAt = updatedAt)

    private fun tempFile(): File = File.createTempFile("install", ".apk").apply {
        delete()
        deleteOnExit()
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
