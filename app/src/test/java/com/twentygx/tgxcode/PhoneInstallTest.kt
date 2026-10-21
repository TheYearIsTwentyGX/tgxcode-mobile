package com.twentygx.tgxcode

import android.content.pm.PackageInstaller
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.isConflict
import com.twentygx.tgxcode.data.install.Claim
import com.twentygx.tgxcode.data.install.PhoneInstallRules
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import com.twentygx.tgxcode.data.model.PhoneInstallsResponse
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.SseCodec
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The phone-installs queue: the record on the wire, which records get picked up, the
 * download check, what a PackageInstaller result means, and whether a self-update
 * landed after it killed the process that committed it.
 */
class PhoneInstallTest {

    // ---------------------------------------------------------------- wire

    @Test
    fun `a phone_install event decodes to the whole record`() {
        val event = SseCodec.decode(
            "phone_install",
            """{"id":"a1","fileName":"app-debug.apk","size":1234,"sha256":"ab","label":"Demo build",
               "sessionId":null,"createdAt":1787328400891,"state":"pending","message":null,
               "packageName":null,"versionName":null,"updatedAt":1787328400891}""",
        ) as StreamEvent.PhoneInstallChanged
        assertEquals("a1", event.install.id)
        assertEquals(1234L, event.install.size)
        assertEquals(PhoneInstallState.PENDING, event.install.state)
        assertNull(event.install.sessionId)
    }

    @Test
    fun `the list decodes`() {
        val list = AppJson.decodeFromString(
            PhoneInstallsResponse.serializer(),
            """{"installs":[{"id":"b","state":"installed","packageName":"x.y","versionName":"1.2","createdAt":2},
                            {"id":"a","state":"pending","createdAt":1}]}""",
        )
        assertEquals(listOf("b", "a"), list.installs.map { it.id })
        assertEquals("x.y", list.installs.first().packageName)
    }

    @Test
    fun `a status body omits what it does not say`() {
        val json = AppJson.encodeToString(PhoneInstallStatus.serializer(), PhoneInstallStatus("downloading"))
        assertEquals("""{"state":"downloading"}""", json)
    }

    // ---------------------------------------------------------------- catch-up

    @Test
    fun `catch-up claims pending records oldest first and skips what is in hand or claimed elsewhere`() {
        val installs = listOf(
            record("newest", PhoneInstallState.PENDING, createdAt = 30),
            record("done", PhoneInstallState.INSTALLED, createdAt = 25),
            record("busy", PhoneInstallState.PENDING, createdAt = 20),
            record("claimed", PhoneInstallState.DOWNLOADING, createdAt = 15),
            record("oldest", PhoneInstallState.PENDING, createdAt = 10),
            record("failed", PhoneInstallState.FAILED, createdAt = 5),
        )
        val picked = PhoneInstallRules.toPickUp(installs, ownClaims = emptySet(), inHand = setOf("busy"))
        assertEquals(listOf("oldest", "newest"), picked.map { it.id })
    }

    @Test
    fun `catch-up on an empty queue picks nothing`() {
        assertTrue(PhoneInstallRules.toPickUp(emptyList(), emptySet(), emptySet()).isEmpty())
    }

    // ---------------------------------------------------------------- verification

    private val bytes = "not really an apk".toByteArray()
    private val sha = sha256(bytes)

    @Test
    fun `a download that matches passes`() {
        val r = record("a", size = bytes.size.toLong(), sha256 = sha)
        assertNull(PhoneInstallRules.verify(r, bytes.size.toLong(), sha, headerSha256 = sha))
        // Case and surrounding space are not a mismatch.
        assertNull(PhoneInstallRules.verify(r.copy(sha256 = " ${sha.uppercase()} "), bytes.size.toLong(), sha))
    }

    @Test
    fun `a short download fails on size before hashing matters`() {
        val r = record("a", size = bytes.size.toLong() + 10, sha256 = sha)
        val message = PhoneInstallRules.verify(r, bytes.size.toLong(), sha)
        assertNotNull(message)
        assertTrue(message!!, message.contains("expected ${bytes.size + 10}"))
    }

    @Test
    fun `a different file fails on sha256`() {
        val r = record("a", size = bytes.size.toLong(), sha256 = sha)
        val message = PhoneInstallRules.verify(r, bytes.size.toLong(), sha256("other".toByteArray()))
        assertTrue(message!!, message.startsWith("Download did not match"))
    }

    @Test
    fun `a record with no sha256 is never trusted`() {
        val r = record("a", size = bytes.size.toLong(), sha256 = "")
        assertNotNull(PhoneInstallRules.verify(r, bytes.size.toLong(), sha))
    }

    @Test
    fun `the served header disagreeing with the record fails`() {
        val r = record("a", size = bytes.size.toLong(), sha256 = sha)
        assertNotNull(PhoneInstallRules.verify(r, bytes.size.toLong(), sha, headerSha256 = "f".repeat(64)))
    }

    // ---------------------------------------------------------------- outcomes

    @Test
    fun `success and a pending confirmation map to their states`() {
        val ok = PhoneInstallRules.outcomeOf(PackageInstaller.STATUS_SUCCESS, "INSTALL_SUCCEEDED", "Demo")
        assertEquals(PhoneInstallState.INSTALLED, ok.state)
        assertNull(ok.message)

        val tap = PhoneInstallRules.outcomeOf(PackageInstaller.STATUS_PENDING_USER_ACTION, null, "Demo")
        assertEquals(PhoneInstallState.NEEDS_ACTION, tap.state)
        assertTrue(tap.message!!, tap.message.contains("Demo"))
    }

    @Test
    fun `a signature mismatch says to uninstall first`() {
        for (status in listOf(PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)) {
            val outcome = PhoneInstallRules.outcomeOf(
                status,
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package x.y signatures do not match newer version",
                "Demo",
            )
            assertEquals(PhoneInstallState.FAILED, outcome.state)
            assertTrue(outcome.message!!, outcome.message.contains("Uninstall it on the phone first"))
            // The platform's own words ride along, for whoever reads the record.
            assertTrue(outcome.message, outcome.message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        }
    }

    @Test
    fun `a conflict without a signature hint does not tell anyone to uninstall`() {
        val outcome = PhoneInstallRules.outcomeOf(PackageInstaller.STATUS_FAILURE_CONFLICT, null, "Demo")
        assertEquals(PhoneInstallState.FAILED, outcome.state)
        assertFalse(outcome.message!!, outcome.message.contains("Uninstall"))
    }

    @Test
    fun `the other failures each get a sentence`() {
        val statuses = listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_TIMEOUT,
            99,
        )
        val messages = statuses.map { PhoneInstallRules.outcomeOf(it, null, "Demo") }
        assertTrue(messages.all { it.state == PhoneInstallState.FAILED && !it.message.isNullOrBlank() })
        assertEquals(statuses.size, messages.map { it.message }.toSet().size)
        assertEquals("Cancelled on the phone.", messages[1].message)
    }

    // ---------------------------------------------------------------- self-update

    @Test
    fun `an update after the claim landed`() {
        val claim = Claim("a", claimedAt = 1_000, versionName = "0.1.0", previousVersionName = "0.1.0")
        assertTrue(PhoneInstallRules.installLanded(claim, "0.1.0", lastUpdateTime = 1_500))
    }

    @Test
    fun `the same version installed before the claim did not land`() {
        // A rebuild that kept its version name, and failed: the name alone would lie.
        val claim = Claim("a", claimedAt = 1_000, versionName = "0.1.0", previousVersionName = "0.1.0")
        assertFalse(PhoneInstallRules.installLanded(claim, "0.1.0", lastUpdateTime = 500))
    }

    @Test
    fun `a new version name is enough when the clock disagrees`() {
        val claim = Claim("a", claimedAt = 1_000, versionName = "0.2.0", previousVersionName = "0.1.0")
        assertTrue(PhoneInstallRules.installLanded(claim, "0.2.0", lastUpdateTime = 900))
        assertFalse(PhoneInstallRules.installLanded(claim, "0.1.0", lastUpdateTime = 900))
    }

    @Test
    fun `claims survive a round trip and garbage reads as none`() {
        val claims = listOf(
            Claim("a", 1, "Demo", "x.y", "1.0", "0.9"),
            Claim("b", 2, waitingForPermission = true),
        )
        assertEquals(claims, Claim.decode(Claim.encode(claims)))
        assertTrue(Claim.decode("not json").isEmpty())
        assertTrue(Claim.decode(null).isEmpty())
    }

    // ---------------------------------------------------------------- routes

    private val server = MockWebServer()

    @Volatile private lateinit var config: BridgeConfig

    private val clients = BridgeClients(tokenProvider = { config.token }, accessProvider = { config.access })
    private val api = BridgeApi(clients) { config }

    @Before
    fun setUp() {
        server.start()
        config = BridgeConfig(server.url("/"), "t".repeat(43), "id.access", "s3cret")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the download streams to disk and measures what arrived`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setHeader("X-SHA256", sha)
                .body(okio.Buffer().write(bytes))
                .build(),
        )
        val dest = File.createTempFile("install", ".apk").apply { deleteOnExit() }
        val result = api.downloadPhoneInstall("a1", dest)
        val got = (result as ApiResult.Ok).value
        assertEquals(bytes.size.toLong(), got.size)
        assertEquals(sha, got.sha256)
        assertEquals(sha, got.headerSha256)
        assertEquals(bytes.toList(), dest.readBytes().toList())

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/phone-installs/a1/apk", recorded.url.encodedPath)
        // The same auth as every other call, Access included.
        assertEquals("Bearer ${"t".repeat(43)}", recorded.headers["Authorization"])
        assertEquals("id.access", recorded.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `a failed download leaves no partial file`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(404)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":"no such install"}""")
                .build(),
        )
        val dest = File.createTempFile("install", ".apk").apply { delete() }
        val result = api.downloadPhoneInstall("gone", dest)
        assertEquals("no such install", (result as ApiResult.Http).error)
        assertFalse(dest.exists())
    }

    @Test
    fun `the claim carries the viewer id and a 409 reads as taken`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(409)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":"another phone is already installing this"}""")
                .build(),
        )
        val result = api.phoneInstallStatus("a", PhoneInstallStatus(PhoneInstallState.DOWNLOADING), "phone-1")
        assertTrue(result.isConflict)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/phone-installs/a/status", recorded.url.encodedPath)
        assertEquals("phone-1", recorded.headers["X-TGXCode-Viewer"])
        assertEquals("1", recorded.headers["X-TGXCode-Client"])
        assertEquals("""{"state":"downloading"}""", recorded.body!!.utf8())
    }

    // ---------------------------------------------------------------- fixture

    private fun record(
        id: String,
        state: String = PhoneInstallState.PENDING,
        createdAt: Long = 0,
        size: Long = 0,
        sha256: String = "",
    ) = PhoneInstall(id = id, state = state, createdAt = createdAt, size = size, sha256 = sha256)

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
