package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.ImageAnchor
import com.twentygx.tgxcode.data.model.ImageOccurrence
import com.twentygx.tgxcode.data.model.SessionImages
import com.twentygx.tgxcode.data.model.TurnMark
import com.twentygx.tgxcode.data.model.describe
import com.twentygx.tgxcode.data.model.earlierStartFor
import com.twentygx.tgxcode.data.model.imageSizeText
import com.twentygx.tgxcode.data.model.isImageKey
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `GET /api/sessions/:id/images` and `…/images/:key`, as `docs/api.md` describes them. */
class ChatImagesTest {

    private val key = "a".repeat(64)

    private val sample = """
        {"sessionId":"s1","images":[{
          "key":"$key","source":"tool","toolUseId":"toolu_1","n":0,"uuid":"u2",
          "at":"2026-10-20T10:00:00Z","mediaType":"image/png","bytes":348160,
          "width":1280,"height":800,"label":"shot.png",
          "url":"/api/sessions/s1/images/$key","count":3,
          "occurrences":[
            {"uuid":"u2","at":"2026-10-20T10:00:00Z","source":"tool","toolUseId":"toolu_1","n":0,
             "label":"shot.png","agent":null,"anchor":{"kind":"tool","id":"toolu_1","offset":5120}},
            {"uuid":"u5","at":"2026-10-20T10:05:00Z","source":"user","toolUseId":null,"n":null,
             "label":"Your message","agent":null,"anchor":{"kind":"user","id":"u5","offset":9000}},
            {"uuid":"u9","at":"2026-10-20T10:09:00Z","source":"tool","toolUseId":"toolu_9","n":0,
             "label":"Playwright: Browser Take Screenshot","agent":"toolu_agent","anchor":null}
          ]}]}
    """.trimIndent()

    @Test
    fun `the list decodes`() {
        val list = AppJson.decodeFromString(SessionImages.serializer(), sample)
        val image = list.images.single()
        assertEquals(3, image.count)
        assertEquals(1280, image.width)
        assertEquals(ImageAnchor(kind = "tool", id = "toolu_1", offset = 5120), image.occurrences[0].anchor)
        assertNull(image.occurrences[2].anchor)
        assertEquals("shot.png · 1280×800 · 340 KB · appears 3 times", image.describe())
    }

    @Test
    fun `an occurrence says where it came from`() {
        val list = AppJson.decodeFromString(SessionImages.serializer(), sample)
        val (tool, user, agent) = list.images.single().occurrences
        assertEquals("shot.png · from a tool", tool.title)
        assertEquals("Your message", user.title)
        assertEquals("Playwright: Browser Take Screenshot · in a subagent", agent.title)
        assertEquals("Image", ImageOccurrence(source = "user").title)
    }

    @Test
    fun `sizes read like the desktop's`() {
        assertEquals("1 KB", imageSizeText(10))
        assertEquals("340 KB", imageSizeText(348_160))
        assertEquals("1.5 MB", imageSizeText(1_572_864))
    }

    @Test
    fun `a jump above the window loads from the turn at or before it`() {
        val turns = listOf(
            TurnMark(kind = "turn", id = "t0", offset = 0),
            TurnMark(kind = "turn", id = "t1", offset = 4_000),
            TurnMark(kind = "plan", id = "p1", offset = 5_000),
            TurnMark(kind = "turn", id = "t2", offset = 8_000),
            TurnMark(kind = "turn", id = "t3", offset = 12_000),
        )
        // In turn t1 — the plan mark is not a turn, and `/range` would refuse it.
        assertEquals(4_000L, earlierStartFor(ImageAnchor(id = "x", offset = 5_120), turns, windowStart = 12_000))
        // Exactly on a turn's line.
        assertEquals(8_000L, earlierStartFor(ImageAnchor(id = "x", offset = 8_000), turns, windowStart = 12_000))
        // Already inside the window: nothing to load.
        assertNull(earlierStartFor(ImageAnchor(id = "x", offset = 12_500), turns, windowStart = 12_000))
        // No offset, or no turn index to range over.
        assertNull(earlierStartFor(ImageAnchor(id = "x"), turns, windowStart = 12_000))
        assertNull(earlierStartFor(ImageAnchor(id = "x", offset = 10), emptyList(), windowStart = 12_000))
        // Above every turn: from the start.
        assertEquals(0L, earlierStartFor(ImageAnchor(id = "x", offset = 10), turns.drop(1), windowStart = 12_000))
    }

    @Test
    fun `only a sha256 is a key`() {
        assertTrue(isImageKey(key))
        assertFalse(isImageKey("../" + key.drop(3)))
        assertFalse(isImageKey(key.uppercase()))
        assertFalse(isImageKey(key.drop(1)))
    }

    // ------------------------------------------------------------ the bytes route

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
    fun `image bytes come back raw, with the token and the Access headers sent`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        server.enqueue(
            MockResponse.Builder().code(200)
                .setHeader("Content-Type", "image/png")
                .body(Buffer().write(png))
                .build(),
        )
        val result = api.imageBytes("s1", key)
        assertArrayEquals(png, (result as ApiResult.Ok).value)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/sessions/s1/images/$key", recorded.url.encodedPath)
        assertEquals("Bearer ${"t".repeat(43)}", recorded.headers["Authorization"])
        assertEquals("id.access", recorded.headers["CF-Access-Client-Id"])
        assertEquals("s3cret", recorded.headers["CF-Access-Client-Secret"])
    }

    @Test
    fun `a key the transcript no longer has is a 404, not bytes`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(404)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":"image not available"}""")
                .build(),
        )
        val result = api.imageBytes("s1", key)
        assertEquals(404, (result as ApiResult.Http).status)
    }
}
