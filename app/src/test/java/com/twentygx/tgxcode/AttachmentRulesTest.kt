package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.data.attach.StageState
import com.twentygx.tgxcode.data.attach.StagedFile
import com.twentygx.tgxcode.data.attach.canSendWith
import com.twentygx.tgxcode.data.attach.dataUriBytes
import com.twentygx.tgxcode.data.attach.filesBesideImages
import com.twentygx.tgxcode.data.attach.formatBytes
import com.twentygx.tgxcode.data.attach.jpegName
import com.twentygx.tgxcode.data.attach.needsReencode
import com.twentygx.tgxcode.data.attach.readyRefs
import com.twentygx.tgxcode.data.attach.safeName
import com.twentygx.tgxcode.data.attach.sampleSizeFor
import com.twentygx.tgxcode.data.attach.scaledSize
import com.twentygx.tgxcode.data.attach.uploadRefusal
import com.twentygx.tgxcode.data.model.AttachmentRef
import com.twentygx.tgxcode.data.model.FileRef
import com.twentygx.tgxcode.data.model.ImageRef
import com.twentygx.tgxcode.ui.NewSessionUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRulesTest {

    // ------------------------------------------------------------------ names

    @Test
    fun `a name the bridge would refuse is cleaned rather than sent`() {
        assertEquals("a_b_c.png", safeName("a/b\\c.png"))
        assertEquals("tab_here.txt", safeName("tab\there.txt"))
        assertEquals("attachment", safeName(""))
        assertEquals("attachment", safeName(".."))
        assertEquals(".env", safeName(".env"))
    }

    @Test
    fun `a long name is cut to 200 bytes and keeps its extension`() {
        val long = "é".repeat(150) + ".pdf"           // 300 bytes of stem
        val safe = safeName(long)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(safe.endsWith(".pdf"))
        assertTrue("never half a character", safe.removeSuffix(".pdf").all { it == 'é' })
    }

    @Test
    fun `a long name of emoji is never cut through a surrogate pair`() {
        val safe = safeName("😀".repeat(80) + ".png")
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 200)
        assertFalse(safe.removeSuffix(".png").last().isHighSurrogate())
    }

    @Test
    fun `a re-encoded photo is renamed jpg`() {
        assertEquals("IMG_0042.jpg", jpegName("IMG_0042.HEIC"))
        assertEquals("photo.jpg", jpegName("photo"))
        assertEquals(".hidden.jpg", jpegName(".hidden"))
    }

    // ------------------------------------------------------------------ images

    @Test
    fun `a screenshot goes as it is and a camera photo is scaled`() {
        assertFalse(needsReencode("image/png", 900_000, 1170, 2532 / 2))
        assertTrue("4000px is past the edge", needsReencode("image/jpeg", 3_000_000, 4000, 3000))
        assertTrue("HEIC is not something the bridge can show the model", needsReencode("image/heic", 1_000, 100, 100))
        assertFalse("small and unmeasured is given the benefit of the doubt", needsReencode("image/png", 50_000, null, null))
        assertTrue(needsReencode("image/png", null, null, null))
    }

    @Test
    fun `scaling keeps the aspect and never scales up`() {
        assertEquals(2048 to 1536, scaledSize(4000, 3000, 2048))
        assertEquals(1536 to 2048, scaledSize(3000, 4000, 2048))
        assertEquals(800 to 600, scaledSize(800, 600, 2048))
    }

    @Test
    fun `a data uri decodes and anything else does not`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), dataUriBytes("data:image/png;base64,AQID"))
        assertNull(dataUriBytes("data:text/plain,hello"))
        assertNull(dataUriBytes("https://example.com/x.png"))
    }

    @Test
    fun `sampling steps down by powers of two to the target`() {
        assertEquals(1, sampleSizeFor(400, 300, 440))
        assertEquals("4000 / 8 = 500, still at least 440", 8, sampleSizeFor(4000, 3000, 440))
    }

    @Test
    fun `a photo listed as both an image and a file is shown once`() {
        val files = listOf(
            FileRef("attached_assets/shot.png", "shot.png", "412 KB"),
            FileRef("attached_assets/notes.pdf", "notes.pdf", "1.2 MB"),
        )
        assertEquals(listOf("notes.pdf"), filesBesideImages(files, listOf(ImageRef("image/png", "data:"))).map { it.name })
        assertEquals("no images, every card", 2, filesBesideImages(files, emptyList()).size)
    }

    @Test
    fun `sizes read the way the bridge writes them`() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("412 KB", formatBytes(412 * 1024))
    }

    // ------------------------------------------------------------------ sending

    private fun file(id: Long, state: StageState) = StagedFile(id, "content://media/picker/0/$id", "f$id", 10, "image/png", state)
    private val ready = StageState.Ready(AttachmentRef("/p/attached_assets/a.png"))

    @Test
    fun `files alone are enough to send`() {
        assertTrue(canSendWith("", listOf(file(1, ready)), sending = false))
        assertFalse(canSendWith("", emptyList(), sending = false))
        assertTrue(canSendWith("hi", emptyList(), sending = false))
    }

    @Test
    fun `nothing goes while a file is still uploading or has failed`() {
        assertFalse(canSendWith("hi", listOf(file(1, ready), file(2, StageState.Uploading)), sending = false))
        assertFalse(canSendWith("hi", listOf(file(1, StageState.Failed("no"))), sending = false))
        assertFalse(canSendWith("hi", listOf(file(1, ready)), sending = true))
    }

    @Test
    fun `only uploaded files are named on the send`() {
        val refs = listOf(file(1, ready), file(2, StageState.Held)).readyRefs()
        assertEquals(listOf("a.png"), refs.map { it.name })
    }

    @Test
    fun `a new session can start on a file alone`() {
        val state = NewSessionUiState(cwd = "~/proj", attachments = listOf(file(1, StageState.Held)))
        assertNull(state.validate())
        assertEquals("A session needs a first message.", NewSessionUiState(cwd = "~/proj").validate())
    }

    @Test
    fun `the refusals that need their own sentence get one`() {
        assertEquals("Over the 10MB a phone can attach", uploadRefusal(ApiResult.Http(413, "too big")))
        assertTrue(
            uploadRefusal(ApiResult.Http(403, "files can only be attached on the machine they are saved to"))
                .contains("needs updating"),
        )
        assertEquals("that file is empty", uploadRefusal(ApiResult.Http(400, "that file is empty")))
    }
}
