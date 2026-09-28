package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.model.PrAggregate
import com.twentygx.tgxcode.data.model.PrRef
import com.twentygx.tgxcode.data.model.GhReach
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.ui.common.PrLook
import com.twentygx.tgxcode.ui.common.prBadgeOf
import com.twentygx.tgxcode.ui.common.prBadgeWords
import com.twentygx.tgxcode.ui.common.prFade
import com.twentygx.tgxcode.ui.common.prLookOf
import com.twentygx.tgxcode.ui.common.prWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a row decides to draw, and what it says about it.
 *
 * Pure — no Compose, no Android. The composables over these are deliberately thin so
 * that everything worth getting wrong is testable here.
 */
class PrBadgeTest {

    private fun session(id: String = "s1", prs: List<PrRef> = emptyList()) =
        SessionSummary(sessionId = id, prs = prs)

    private val onePr = listOf(PrRef(number = 7, url = "https://example.invalid/pull/7"))

    // ------------------------------------------------------------ three cases

    @Test
    fun `a session with no pull requests draws nothing`() {
        assertNull(prBadgeOf(session(), PrsPayload(sessions = mapOf("s1" to PrAggregate()))))
    }

    /**
     * Absent from the map, or no payload at all: the row knows how many PRs there are
     * — the summary told it — and nothing about them. That is a glyph, not silence.
     */
    @Test
    fun `PRs the bridge has not answered for yet are asking, not absent`() {
        val fromEmptyMap = prBadgeOf(session(prs = onePr), PrsPayload())
        val fromNoPayload = prBadgeOf(session(prs = onePr), null)

        listOf(fromEmptyMap, fromNoPayload).forEach { badge ->
            val it = checkNotNull(badge)
            assertTrue(it.asking)
            assertEquals(PrLook.Unknown, it.look)
            assertEquals("the honest count is of refs", 1, it.total)
        }
    }

    /** The aggregate's own status, copied verbatim — the ranking stays on the bridge. */
    @Test
    fun `an answered session takes the bridge's status as given`() {
        val badge = checkNotNull(
            prBadgeOf(
                session(prs = onePr),
                PrsPayload(
                    sessions = mapOf(
                        "s1" to PrAggregate(
                            status = "conflicting",
                            label = "conflicts with the base branch",
                            total = 1,
                            counts = mapOf("conflicting" to 1),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(badge.asking)
        assertEquals("conflicting", badge.status)
        assertEquals(PrLook.Conflicting, badge.look)
    }

    // ---------------------------------------------------------------- wording

    /**
     * The one place this app is allowed to blame GitHub, and it may only do so when
     * `gh.error` actually said something. Claiming it otherwise is what sends somebody
     * off to re-issue a token that was fine.
     */
    @Test
    fun `unknown does not blame the network unless gh did`() {
        val badge = checkNotNull(
            prBadgeOf(
                session(prs = onePr),
                PrsPayload(sessions = mapOf("s1" to PrAggregate(status = "unknown", total = 1))),
            ),
        )

        assertEquals("not looked up yet", prBadgeWords(badge, null))
        assertEquals("gh: bad credentials", prBadgeWords(badge, "gh: bad credentials"))
    }

    @Test
    fun `an unanswered row says so, whatever gh reported`() {
        val badge = checkNotNull(prBadgeOf(session(prs = onePr), null))

        assertEquals("Asking GitHub…", prBadgeWords(badge, "gh: bad credentials"))
    }

    @Test
    fun `a real status prefers the bridge's own label`() {
        val labelled = checkNotNull(
            prBadgeOf(
                session(prs = onePr),
                PrsPayload(
                    sessions = mapOf(
                        "s1" to PrAggregate(status = "changes", label = "changes requested", total = 1),
                    ),
                ),
            ),
        )
        val unlabelled = checkNotNull(
            prBadgeOf(
                session(prs = onePr),
                PrsPayload(sessions = mapOf("s1" to PrAggregate(status = "changes", total = 1))),
            ),
        )

        assertEquals("changes requested", prBadgeWords(labelled, null))
        assertEquals("awaiting changes", prBadgeWords(unlabelled, null))
    }

    @Test
    fun `prWords shortens the four that read badly and opens out the rest`() {
        assertEquals("awaiting changes", prWords("changes"))
        assertEquals("failing", prWords("checks-failed"))
        assertEquals("still checking", prWords("checks-pending"))
        assertEquals("unreachable", prWords("unknown"))
        assertEquals("merged", prWords("merged"))
        assertEquals("conflicting", prWords("conflicting"))
    }

    // ------------------------------------------------------------------- look

    @Test
    fun `every wire status has a look, and only open shares one with unknown`() {
        assertEquals(PrLook.Open, prLookOf("open"))
        assertEquals(PrLook.Draft, prLookOf("draft"))
        assertEquals(PrLook.Approved, prLookOf("approved"))
        assertEquals(PrLook.Changes, prLookOf("changes"))
        assertEquals(PrLook.ChecksFailed, prLookOf("checks-failed"))
        assertEquals(PrLook.ChecksPending, prLookOf("checks-pending"))
        assertEquals(PrLook.Conflicting, prLookOf("conflicting"))
        assertEquals(PrLook.Merged, prLookOf("merged"))
        assertEquals(PrLook.Closed, prLookOf("closed"))
        assertEquals(PrLook.Unknown, prLookOf("unknown"))
        assertEquals(PrLook.Unknown, prLookOf(null))
    }

    /**
     * `Unknown` is the same grey as `Closed` but is **not** faded, and that is easy to
     * get wrong by pattern-matching on the colour: `--pr-fade` dims what has *settled*,
     * and an unanswered PR has not settled — nobody has looked at it.
     */
    @Test
    fun `only the settled states are dimmed`() {
        assertEquals(0.62f, prFade(PrLook.Merged), 0f)
        assertEquals(0.62f, prFade(PrLook.Closed), 0f)
        PrLook.entries
            .filter { it != PrLook.Merged && it != PrLook.Closed }
            .forEach { assertEquals("$it should not be dimmed", 1f, prFade(it), 0f) }
    }

    @Test
    fun `gh reachability defaults to reachable, so an old bridge is not slandered`() {
        assertTrue(GhReach().ok)
        assertNull(GhReach().error)
    }
}
