package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.FlagsRequest
import com.twentygx.tgxcode.data.model.SendRequest
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.repo.sendBody
import com.twentygx.tgxcode.ui.BranchFrom
import com.twentygx.tgxcode.ui.NewSessionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlTest {

    private fun encode(body: SendRequest) = AppJson.encodeToString(SendRequest.serializer(), body)

    @Test
    fun `inherit effort is sent, inherit model is not`() {
        val json = encode(sendBody("hi", "acceptEdits", model = "", effort = ""))
        assertTrue("effort inherit must reach the bridge", json.contains("\"effort\":\"\""))
        assertFalse("model inherit is as-you-were, so absent", json.contains("model"))
        assertTrue(json.contains("\"permissionMode\":\"acceptEdits\""))
    }

    @Test
    fun `a chosen model and effort are both sent`() {
        val json = encode(sendBody("hi", "auto", model = "opus", effort = "high"))
        assertTrue(json.contains("\"model\":\"opus\""))
        assertTrue(json.contains("\"effort\":\"high\""))
    }

    @Test
    fun `a branch carries the turn id and brings fork with it`() {
        val json = encode(sendBody("edited", "auto", fromUuid = "6f1c2e7a-0000-4000-8000-000000000001"))
        assertTrue(json.contains("\"fork\":true"))
        assertTrue(json.contains("\"fromUuid\":\"6f1c2e7a-0000-4000-8000-000000000001\""))
    }

    @Test
    fun `an ordinary send says nothing about forking`() {
        val json = encode(sendBody("hi", "auto"))
        assertFalse(json.contains("fork"))
        assertFalse(json.contains("fromUuid"))
    }

    @Test
    fun `clearing a name sends an empty title, a pin sends only the pin`() {
        val clear = AppJson.encodeToString(FlagsRequest.serializer(), FlagsRequest(title = ""))
        assertEquals("{\"title\":\"\"}", clear)
        val pin = AppJson.encodeToString(FlagsRequest.serializer(), FlagsRequest(pinned = true))
        assertEquals("{\"pinned\":true}", pin)
    }

    @Test
    fun `a full model id the picker does not offer reads as inherit`() {
        assertEquals("", SessionChoices.knownModel("claude-opus-4-1-20250805"))
        assertEquals("opus", SessionChoices.knownModel("opus"))
        assertEquals("", SessionChoices.knownEffort("ludicrous"))
        assertEquals("inherit", SessionChoices.label(""))
    }

    @Test
    fun `the branch banner says what the copy will leave out`() {
        assertTrue(BranchFrom("s", "u", first = true).banner.contains("new session"))
        assertTrue(BranchFrom("s", "u", turn = 4, total = 4).banner.contains("not include turn 4"))
        assertTrue(BranchFrom("s", "u", turn = 2, total = 5).banner.contains("turns 2–5"))
        assertTrue(BranchFrom("s", "u").banner.contains("an earlier turn"))
    }

    @Test
    fun `a draft whose model changed is dirty`() {
        val draft = Draft(id = "d1", cwd = "/x", prompt = "hi", model = "opus", effort = null)
        val clean = NewSessionUiState(cwd = "/x", prompt = "hi", mode = "auto", model = "opus", editingDraftId = "d1")
        assertFalse(clean.differsFrom(draft))
        assertTrue(clean.copy(model = "").differsFrom(draft))
        assertTrue(clean.copy(effort = "max").differsFrom(draft))
    }
}
