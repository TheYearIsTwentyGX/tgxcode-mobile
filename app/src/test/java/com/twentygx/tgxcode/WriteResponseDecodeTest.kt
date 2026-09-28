package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.CreateSessionResponse
import com.twentygx.tgxcode.data.model.SendResponse
import com.twentygx.tgxcode.data.model.StopResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write responses, in the shapes the bridge actually sends.
 *
 * These exist because of a bug that was invisible from `docs/api.md`: it writes the
 * send response as `{ok, id, cwd, fork, status, queued}`, which reads as though
 * `status` were a string. It is the whole runner status object. Modelling it as a
 * String let the POST succeed and the *response decode* fail — so a message that had
 * genuinely been sent reported "Did not send" and was restored into the composer,
 * which is an invitation to send it twice.
 *
 * A response shape that only ever fails after a successful write is exactly the kind
 * of thing a fixture catches and a code review does not.
 */
class WriteResponseDecodeTest {

    @Test
    fun `send response carries a runner status object, not a string`() {
        val json = """
            {"ok":true,
             "id":"m-1",
             "cwd":"/home/dylan_hays/Other/tgxcode-mobile",
             "fork":false,
             "status":{"sessionId":"s1","state":"busy","activity":"Thinking…",
                       "model":null,"permissionMode":"auto","queued":0,"queue":[],
                       "pendingPermission":null,"canPrompt":true,"busySince":1786905237949},
             "queued":false}
        """.trimIndent()

        val response = AppJson.decodeFromString(SendResponse.serializer(), json)

        assertTrue(response.ok)
        assertEquals("m-1", response.id)
        assertNotNull("status must decode as an object", response.status)
        assertEquals("busy", response.status?.state)
        assertTrue("the runner should read as working", response.status!!.isWorking)
    }

    @Test
    fun `send response queued is a boolean here, unlike the integer on a runner status`() {
        val json = """{"ok":true,"id":"m-2","queued":true,
                       "status":{"state":"busy","queued":3,"queue":[]}}"""
        val response = AppJson.decodeFromString(SendResponse.serializer(), json)
        assertTrue("the message itself is queued", response.queued)
        assertEquals("the runner's backlog is a count", 3, response.status?.queued)
    }

    @Test
    fun `create session response also carries a runner status object`() {
        val json = """
            {"sessionId":"abc-123",
             "status":{"sessionId":"abc-123","state":"starting","queued":0,"queue":[]},
             "test":true}
        """.trimIndent()

        val response = AppJson.decodeFromString(CreateSessionResponse.serializer(), json)

        assertEquals("abc-123", response.sessionId)
        assertTrue(response.test)
        assertEquals("starting", response.status?.state)
    }

    @Test
    fun `stop response hands unsent text back rather than binning it`() {
        val json = """{"ok":true,"how":"soft","dropped":["first queued","second queued"]}"""
        val response = AppJson.decodeFromString(StopResponse.serializer(), json)
        assertEquals("soft", response.how)
        assertEquals(listOf("first queued", "second queued"), response.dropped)
    }
}
