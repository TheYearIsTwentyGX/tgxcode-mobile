package com.twentygx.tgxcode.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * One PR vocabulary, learned once.
 *
 * Three surfaces say what has become of a pull request — the row glyph, the
 * conversation chips and the sheet — and between them they get one colour table and
 * one glyph set, here, rather than three. `web/styles.css:606` is the same decision
 * on the desktop, and the comment there records that it used to be two verbatim
 * copies plus a third scheme that coloured by `draft` alone, so a merged PR, one
 * conflicting with its base and one with a failing build were all the same blue.
 */

/**
 * What a status *looks* like. Colour and glyph, and nothing else.
 *
 * Deliberately not the wire type: [PrsPayload] and
 * [com.twentygx.tgxcode.data.model.PrDetail] keep `status` as a raw `String`, and this
 * is derived at the edge — the same shape as `RunnerStatus.runnerState`.
 *
 * Two reasons it is not decoded straight into an enum. A status added to the bridge
 * must degrade rather than break, which an enum with no fallback cannot do. And more
 * subtly: with a fallback, a status this build has never heard of would collapse into
 * the same value as the wire's own `"unknown"` — and those two need different words.
 * `"unknown"` means *gh could not be reached, or nobody has looked yet*; an
 * unrecognised status means *the bridge knows exactly what this is and we do not*.
 * Keeping the string lets [prBadgeWords] tell them apart.
 */
enum class PrLook { Open, Draft, Approved, Changes, ChecksFailed, ChecksPending, Conflicting, Merged, Closed, Unknown }

fun prLookOf(status: String?): PrLook = when (status) {
    "open" -> PrLook.Open
    "draft" -> PrLook.Draft
    "approved" -> PrLook.Approved
    "changes" -> PrLook.Changes
    "checks-failed" -> PrLook.ChecksFailed
    "checks-pending" -> PrLook.ChecksPending
    "conflicting" -> PrLook.Conflicting
    "merged" -> PrLook.Merged
    "closed" -> PrLook.Closed
    // Covers the wire's own "unknown" and anything a later bridge invents.
    else -> PrLook.Unknown
}

/**
 * A status in the few words a breakdown line wants — `resolveStatus`'s own labels,
 * shortened where a count reads badly in front of them ("1 changes requested").
 *
 * Anything missing falls through to the status with its hyphens opened out, which is
 * already a readable phrase for every status there is; the map exists for the handful
 * that are not. The *ranking* is deliberately absent — that stays on the bridge.
 */
private val PR_WORDS = mapOf(
    "changes" to "awaiting changes",
    "checks-failed" to "failing",
    "checks-pending" to "still checking",
    "unknown" to "unreachable",
)

fun prWords(status: String): String = PR_WORDS[status] ?: status.replace('-', ' ')

/**
 * What one row has to say about its pull requests, or null for "say nothing".
 *
 * [asking] is the state with no equivalent on the desktop's data model but the same
 * behaviour: the row knows how many PRs there are — the summary told it — and nothing
 * about them. Distinct from a status of `unknown`, which is the bridge having looked
 * and come back empty-handed.
 */
data class PrBadge(
    val status: String,
    val asking: Boolean,
    val label: String?,
    val total: Int,
) {
    val look: PrLook get() = prLookOf(status)
}

/**
 * The three render cases, in one place, pure.
 *
 * The middle one is the whole reason this is not a one-liner at the call site: a
 * session absent from [PrsPayload.sessions] has pull requests nobody has resolved
 * yet, and drawing nothing for it would say "no pull requests" — which is a different
 * and wrong claim.
 */
fun prBadgeOf(session: SessionSummary, prs: PrsPayload?): PrBadge? {
    if (!session.hasPrs) return null
    val aggregate = prs?.sessions?.get(session.sessionId)
        ?: return PrBadge(
            status = "unknown",
            asking = true,
            label = null,
            // Refs, not resolved PRs — the honest count before anything is known.
            total = session.prs.size,
        )
    return PrBadge(aggregate.status, asking = false, aggregate.label, aggregate.total)
}

/**
 * The glyph is recognisable without being read; these are the words.
 *
 * `prBadgeTip`'s headline, and the one place the app is allowed to be careful about
 * blame. Claiming GitHub is unreachable when it is merely early "is the kind of wrong
 * that sends somebody to check their token" — so [ghError] decides, and the default
 * says only that nothing has looked yet.
 */
fun prBadgeWords(badge: PrBadge, ghError: String?): String = when {
    badge.asking -> "Asking GitHub…"
    badge.status == "unknown" -> ghError ?: "not looked up yet"
    else -> badge.label ?: prWords(badge.status)
}

/**
 * The hue, from `web/styles.css:627-643`.
 *
 * Colour and glyph say the same thing twice on purpose: two of these states are red
 * and two are yellow, so hue alone would not separate them.
 */
@Composable
fun prTint(look: PrLook): Color = when (look) {
    PrLook.Open -> tgx.blue
    PrLook.Approved -> tgx.green
    PrLook.Changes, PrLook.ChecksFailed -> tgx.red
    PrLook.Conflicting, PrLook.ChecksPending -> tgx.yellow
    PrLook.Draft -> tgx.textMuted
    PrLook.Merged -> tgx.purple
    PrLook.Closed, PrLook.Unknown -> tgx.textFaint
}

/**
 * `--pr-fade`: the settled states are dimmed.
 *
 * They are kept because "this landed" is worth a glance, but they must not compete
 * with the rows that still want something. Note `Unknown` is **not** faded even
 * though it is the same grey as `Closed` — it is not settled, it is unanswered.
 */
fun prFade(look: PrLook): Float = if (look == PrLook.Merged || look == PrLook.Closed) 0.62f else 1f
