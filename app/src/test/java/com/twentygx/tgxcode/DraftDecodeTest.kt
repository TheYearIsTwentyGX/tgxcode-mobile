package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.DraftsResponse
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.ui.NewSessionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drafts, and the one thing about them that is easy to get wrong.
 *
 * `PATCH /api/drafts/:id` is a **genuine partial**: a field left out is left alone.
 * This screen has no model picker and no title field, so [DraftBody] carries neither —
 * which means an edit made on the phone preserves whatever was set at the desk. Send
 * them as nulls instead and every phone edit silently clears both, which is the sort of
 * data loss nobody notices until the session starts on the wrong model.
 */
class DraftDecodeTest {

    private val payload = """
        {"at":1787328400656,
         "drafts":[
           {"id":"9640eae3-2c96-4a21-aa94-b7d262950ec0",
            "cwd":"/home/dylan_hays/Other/claude-sessions",
            "projectName":"claude-sessions",
            "prompt":"Add a CSV export to the reports page",
            "title":null,"model":"opus","permissionMode":"plan","test":true,
            "createdAt":1787328400891,"updatedAt":1787328401276},
           {"id":"b1","cwd":"/home/dylan_hays/Other/tgxcode-mobile",
            "projectName":"tgxcode-mobile",
            "prompt":"Chase the flaky test\nit fails one run in five",
            "title":"Flaky test","model":null,"permissionMode":"acceptEdits","test":false,
            "createdAt":1787328000000,"updatedAt":1787328100000}
         ],
         "counts":{"total":2}}
    """.trimIndent()

    @Test
    fun `the drafts payload decodes`() {
        val response = AppJson.decodeFromString(DraftsResponse.serializer(), payload)

        assertEquals(2, response.drafts.size)
        assertEquals(2, response.counts?.total)
        assertEquals("claude-sessions", response.drafts[0].projectName)
        assertEquals("plan", response.drafts[0].permissionMode)
        assertTrue(response.drafts[0].test)
    }

    /**
     * `null` means *derive it*, and it is not the string `"null"` — a client that
     * renders it raw shows nothing where the name should be.
     */
    @Test
    fun `a null title falls back to the first line of the prompt`() {
        val drafts = AppJson.decodeFromString(DraftsResponse.serializer(), payload).drafts

        assertNull(drafts[0].title)
        assertEquals("Add a CSV export to the reports page", drafts[0].displayTitle)
        assertEquals("Flaky test", drafts[1].displayTitle)
    }

    /** `null` is `inherit`, not `""`, and must survive as null. */
    @Test
    fun `a null model stays null`() {
        val drafts = AppJson.decodeFromString(DraftsResponse.serializer(), payload).drafts

        assertEquals("opus", drafts[0].model)
        assertNull(drafts[1].model)
    }

    /**
     * The event carries the whole `GET /api/drafts` payload, so there is nothing to
     * refetch when it arrives — which only holds if it decodes to the same rows.
     */
    @Test
    fun `drafts-changed decodes as the whole payload`() {
        val event = AppJson.decodeFromString(StreamEvent.DraftsChanged.serializer(), payload)

        assertEquals(2, event.drafts.size)
        assertEquals(2, event.counts?.total)
        assertEquals(1787328400656L, event.at)
    }

    /**
     * The test this file exists for: **no `model` key and no `title` key**, so a PATCH
     * from a screen that cannot edit either cannot clear either.
     */
    @Test
    fun `a draft body sends only what this screen can edit`() {
        val body = AppJson.encodeToString(
            DraftBody.serializer(),
            DraftBody(
                cwd = "/home/dylan_hays/Other/claude-sessions",
                prompt = "Add a CSV export",
                permissionMode = "plan",
                test = false,
            ),
        )

        assertFalse("a PATCH must not clear the model", body.contains("model"))
        assertFalse("a PATCH must not clear the title", body.contains("title"))
        assertTrue(body.contains("\"permissionMode\":\"plan\""))
        assertTrue(body.contains("\"test\":false"))
    }

    /**
     * An absent `permissionMode` does not mean "decide later" — it means the draft is
     * saved as `auto`. So it is always sent, even when it is the default.
     */
    @Test
    fun `the permission mode is always sent`() {
        val body = AppJson.encodeToString(
            DraftBody.serializer(),
            NewSessionUiState(cwd = "/tmp/x", prompt = "hi", mode = "auto").toDraftBody(),
        )

        assertTrue(body.contains("\"permissionMode\":\"auto\""))
    }

    /** Start reads the *stored* draft, so a dirty form has to be saved first. */
    @Test
    fun `a form that has moved on is dirty`() {
        val draft = AppJson.decodeFromString(DraftsResponse.serializer(), payload).drafts[1]
        val clean = NewSessionUiState(
            cwd = draft.cwd,
            prompt = draft.prompt,
            mode = draft.permissionMode,
            test = draft.test,
            editingDraftId = draft.id,
        )

        assertFalse(clean.differsFrom(draft))
        assertTrue(clean.copy(prompt = "something else").differsFrom(draft))
        assertTrue(clean.copy(mode = "plan").differsFrom(draft))
        assertTrue(clean.copy(test = true).differsFrom(draft))
        // Trailing whitespace is trimmed before it is sent, so it is not a change.
        assertFalse(clean.copy(prompt = draft.prompt + "  ").differsFrom(draft))
    }

    @Test
    fun `both start and save agree about what is missing`() {
        assertEquals(
            "Pick a directory first.",
            NewSessionUiState(cwd = null, prompt = "hi").validate(),
        )
        assertEquals(
            "A session needs a first message.",
            NewSessionUiState(cwd = "/tmp/x", prompt = "   ").validate(),
        )
        assertNull(NewSessionUiState(cwd = "/tmp/x", prompt = "hi").validate())
    }

    /** A draft with nothing in it at all still has to name itself. */
    @Test
    fun `an empty draft still has a display title`() {
        assertEquals("Untitled draft", Draft(id = "d1").displayTitle)
    }
}
