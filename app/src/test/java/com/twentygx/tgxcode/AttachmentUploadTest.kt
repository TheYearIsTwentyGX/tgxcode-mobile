package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.data.net.BridgeClients
import com.twentygx.tgxcode.data.net.BridgeConfig
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The upload is the one request in the app that is not JSON, so its shape is pinned here. */
class AttachmentUploadTest {

    private val server = MockWebServer()
    private lateinit var api: BridgeApi

    @Before
    fun setUp() {
        server.start()
        val config = BridgeConfig(server.url("/"), "t".repeat(43))
        api = BridgeApi(BridgeClients({ config.token })) { config }
    }

    @After
    fun tearDown() = server.close()

    private fun answer(code: Int, json: String) = server.enqueue(
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(json).build(),
    )

    private val uploaded = """{"ok":true,"name":"my shot.png","renamed":false,"path":"/p/attached_assets/my shot.png",""" +
        """"relPath":"attached_assets/my shot.png","dir":"/p/attached_assets","bytes":3,"mediaType":"image/png"}"""

    @Test
    fun `a session upload is raw bytes with the name in the query`() = runBlocking {
        answer(200, uploaded)
        val bytes = byteArrayOf(1, 2, 3)

        val result = api.uploadAttachment("s1", "my shot.png", bytes, "image/png")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions/s1/attachments", request.url.encodedPath)
        assertEquals("my shot.png", request.url.queryParameter("name"))
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("image/png"))
        assertEquals("1", request.headers["X-TGXCode-Client"])
        assertArrayEquals(bytes, request.body?.toByteArray())
        assertEquals("my shot.png", (result as ApiResult.Ok).value.ref().name)
    }

    @Test
    fun `an upload before the session names the folder`() = runBlocking {
        answer(200, uploaded)

        api.uploadAttachmentTo("~/proj", "a.pdf", byteArrayOf(9), "application/pdf")

        val request = server.takeRequest()
        assertEquals("/api/attachments", request.url.encodedPath)
        assertEquals("~/proj", request.url.queryParameter("cwd"))
        assertEquals("a.pdf", request.url.queryParameter("name"))
    }

    @Test
    fun `an over-cap upload comes back as a 413`() = runBlocking {
        answer(413, """{"error":"that file is over the 10MB limit"}""")

        val result = api.uploadAttachment("s1", "big.bin", byteArrayOf(0), "application/octet-stream")

        assertEquals(413, (result as ApiResult.Http).status)
    }
}
