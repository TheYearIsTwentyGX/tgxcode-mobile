package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.AttachmentRef
import com.twentygx.tgxcode.data.model.CreateSessionRequest
import com.twentygx.tgxcode.data.model.LaterMessage
import com.twentygx.tgxcode.data.model.QueuedMessage
import com.twentygx.tgxcode.data.model.SendRequest
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.model.UploadedAttachment
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.data.repo.sendBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attachment fields, as `docs/api.md` writes them and as the bridge sends them. */
class AttachmentWireTest {

    private val shot = AttachmentRef(
        path = "/home/d/proj/attached_assets/shot.png",
        relPath = "attached_assets/shot.png",
        mediaType = "image/png",
    )

    @Test
    fun `a user turn carries its images and its files`() {
        val json = """
            {"id":"u1","kind":"user","ts":"2026-10-13T09:00:00.000Z","text":"look at this",
             "images":[{"mediaType":"image/png","dataUri":"data:image/png;base64,iVBORw0KGgo="},
                       {"mediaType":"image/jpeg","dataUri":null}],
             "files":[{"relPath":"attached_assets/shot.png","name":"shot.png","size":"412 KB"}],
             "command":null,"origin":"human","parent":"p1"}
        """.trimIndent()
        val event = AppJson.decodeFromString(TranscriptEvent.serializer(), json) as UserEvent

        assertEquals(2, event.images.size)
        assertEquals("image/png", event.images[0].mediaType)
        assertNull("the bridge could not read this one back", event.images[1].dataUri)
        assertEquals("shot.png", event.files.single().name)
        assertEquals("412 KB", event.files.single().size)
        assertEquals("look at this", event.displayText())
    }

    @Test
    fun `a user turn from before attachments still decodes`() {
        val event = AppJson.decodeFromString(
            TranscriptEvent.serializer(),
            """{"id":"u1","kind":"user","text":"hi"}""",
        ) as UserEvent
        assertTrue(event.images.isEmpty())
        assertTrue(event.files.isEmpty())
    }

    @Test
    fun `an upload answer becomes the ref a send names`() {
        val json = """
            {"ok":true,"name":"shot-2.png","renamed":true,
             "path":"/home/d/proj/attached_assets/shot-2.png","relPath":"attached_assets/shot-2.png",
             "dir":"/home/d/proj/attached_assets","bytes":4123,"mediaType":"image/png"}
        """.trimIndent()
        val uploaded = AppJson.decodeFromString(UploadedAttachment.serializer(), json)

        assertTrue(uploaded.renamed)
        val ref = uploaded.ref()
        assertEquals("shot-2.png", ref.name)
        assertEquals("attached_assets/shot-2.png", ref.relPath)
        assertEquals("image/png", ref.mediaType)
    }

    @Test
    fun `a send with files may have no text`() {
        val json = AppJson.encodeToString(SendRequest.serializer(), sendBody("", "auto", attachments = listOf(shot)))
        assertTrue(json.contains("\"text\":\"\""))
        assertTrue(json.contains("\"attachments\":[{\"path\":\"/home/d/proj/attached_assets/shot.png\""))
    }

    @Test
    fun `a send without files says nothing about them`() {
        val json = AppJson.encodeToString(SendRequest.serializer(), sendBody("hi", "auto"))
        assertFalse(json.contains("attachments"))
    }

    @Test
    fun `a create carries its files`() {
        val json = AppJson.encodeToString(
            CreateSessionRequest.serializer(),
            CreateSessionRequest(cwd = "~/proj", prompt = "", attachments = listOf(shot)),
        )
        assertTrue(json.contains("\"attachments\":[{\"path\":"))
    }

    @Test
    fun `queued and scheduled rows keep their files`() {
        val queued = AppJson.decodeFromString(
            QueuedMessage.serializer(),
            """{"id":"q1","text":"","at":1,"attachments":[{"path":"/p/attached_assets/a.pdf","relPath":"attached_assets/a.pdf"}],"handed":false}""",
        )
        assertEquals("a.pdf", queued.attachments.single().name)

        val later = AppJson.decodeFromString(
            LaterMessage.serializer(),
            """{"id":"l1","sessionId":"s1","text":"","permissionMode":"plan","at":2,"state":"pending",
               "attachments":[{"path":"/p/attached_assets/b.png"}]}""",
        )
        assertEquals("b.png", later.attachments.single().name)
    }
}
