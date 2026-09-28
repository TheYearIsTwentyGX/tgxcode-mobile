package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.SessionDetail
import com.twentygx.tgxcode.data.model.SessionPrsResponse
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.ui.common.PrLook
import com.twentygx.tgxcode.ui.common.prLookOf
import com.twentygx.tgxcode.ui.common.prWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PR payloads, from fixtures captured verbatim off the live bridge.
 *
 * The thing most worth pinning is not that the JSON parses. It is the shape: a **map**
 * keyed by session id where a session with no pull requests is *absent*, and a
 * `status` that is a bare string so a newer bridge cannot break an older build.
 */
class PrDecodeTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "$name missing from test resources"
        }.bufferedReader().readText()

    /** Raised two PRs, both merged — so the sheet's breakdown line has real input. */
    private val twoPrs = "fb4c3016-a7c7-44c0-a911-10be38688260"

    /** Raised none, and is therefore not in the map at all. */
    private val noPrs = "2761278a-651a-4467-be75-14b02d06db03"

    @Test
    fun `the prs payload decodes`() {
        val payload = AppJson.decodeFromString(PrsPayload.serializer(), fixture("prs.json"))

        assertTrue("expected sessions with PRs", payload.sessions.isNotEmpty())
        assertTrue("expected an ISO checkedAt", payload.checkedAt.orEmpty().isNotBlank())
        assertTrue(payload.gh.ok)

        val aggregate = checkNotNull(payload.sessions[twoPrs])
        assertEquals("merged", aggregate.status)
        assertEquals(2, aggregate.total)
        assertEquals(mapOf("merged" to 2), aggregate.counts)
    }

    /**
     * The distinction the whole feature rests on.
     *
     * A session with no pull requests is **absent from the map**, not present with a
     * status of `unknown`. Conflate the two and every session on the machine gets a
     * grey glyph; conflate them the other way and a session whose PRs have not been
     * looked up yet claims to have none.
     */
    @Test
    fun `a session with no pull requests is absent rather than unknown`() {
        val payload = AppJson.decodeFromString(PrsPayload.serializer(), fixture("prs.json"))

        assertFalse(
            "a session with no PRs must not appear in the map",
            payload.sessions.containsKey(noPrs),
        )
        assertNull(payload.sessions[noPrs])
    }

    /** The counts are over the whole set, so they have to add up to `total`. */
    @Test
    fun `counts sum to total`() {
        val payload = AppJson.decodeFromString(PrsPayload.serializer(), fixture("prs.json"))

        payload.sessions.forEach { (id, aggregate) ->
            assertEquals(
                "counts should sum to total for $id",
                aggregate.total,
                aggregate.counts.values.sum(),
            )
        }
    }

    /**
     * `prs-changed` carries the route's body byte for byte, so the codec must turn it
     * into the same thing the route did — that is what makes applying the event
     * without a refetch correct rather than merely convenient.
     */
    @Test
    fun `the prs-changed event decodes to the same payload as the route`() {
        val body = fixture("prs.json")
        val route = AppJson.decodeFromString(PrsPayload.serializer(), body)

        val event = SseCodec.decode("prs-changed", body)

        assertTrue("expected a PrsChanged", event is StreamEvent.PrsChanged)
        assertEquals(route, (event as StreamEvent.PrsChanged).prs)
    }

    /** An event this build has never heard of is dropped, not thrown. */
    @Test
    fun `an unknown event name is still dropped`() {
        assertNull(SseCodec.decode("prs-something-later", fixture("prs.json")))
    }

    @Test
    fun `one session's pull requests decode in the order they were raised`() {
        val response =
            AppJson.decodeFromString(SessionPrsResponse.serializer(), fixture("session_prs.json"))

        assertEquals(listOf(55, 56), response.prs.map { it.number })
        assertEquals("worktree-settings-page", response.prs[0].branch)
        assertEquals("merged", response.prs[0].status)
        assertEquals("merged", response.prs[0].label)
        assertTrue(response.gh.ok)
    }

    /** The summary carries the numbers and links for free, ahead of any status. */
    @Test
    fun `a session summary carries the PRs it raised`() {
        val detail =
            AppJson.decodeFromString(SessionDetail.serializer(), fixture("session_detail.json"))

        assertEquals(1, detail.summary.prs.size)
        assertEquals(2, detail.summary.prs[0].number)
        assertEquals("TheYearIsTwentyGX/TGXCode", detail.summary.prs[0].repo)
        assertTrue(detail.summary.hasPrs)
    }

    /**
     * A bridge older than the `prs` array sends no such key at all. That must decode
     * to "raised none" rather than throwing — and `hasPrs` must then be false, so the
     * row draws nothing instead of a glyph it cannot explain.
     */
    @Test
    fun `a summary with no prs key at all still decodes`() {
        val summary = AppJson.decodeFromString(
            SessionSummary.serializer(),
            """{"sessionId":"s1","title":"No PR key here"}""",
        )

        assertEquals(emptyList<Any>(), summary.prs)
        assertFalse(summary.hasPrs)
    }

    /**
     * The reason `status` is a `String` and not an enum on the wire type.
     *
     * A status the bridge adds later has to degrade to a grey glyph and a readable
     * phrase, not throw and not vanish.
     */
    @Test
    fun `a status from a later bridge degrades rather than breaking`() {
        val payload = AppJson.decodeFromString(
            PrsPayload.serializer(),
            """{"sessions":{"s1":{"status":"awaiting-something-new","total":1,
               "counts":{"awaiting-something-new":1}}},"gh":{"ok":true}}""",
        )

        val aggregate = checkNotNull(payload.sessions["s1"])
        assertEquals("awaiting-something-new", aggregate.status)
        assertEquals(PrLook.Unknown, prLookOf(aggregate.status))
        assertEquals("awaiting something new", prWords(aggregate.status))
    }
}
