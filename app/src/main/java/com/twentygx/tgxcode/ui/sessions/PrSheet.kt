package com.twentygx.tgxcode.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.model.GhReach
import com.twentygx.tgxcode.data.model.PrAggregate
import com.twentygx.tgxcode.data.model.PrDetail
import com.twentygx.tgxcode.data.model.PrRef
import com.twentygx.tgxcode.ui.common.PrGlyph
import com.twentygx.tgxcode.ui.common.PrLook
import com.twentygx.tgxcode.ui.common.prLookOf
import com.twentygx.tgxcode.ui.common.prWords
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * What a long press on a session row has to show.
 *
 * [refs] is what the summary already knew — numbers and links, no status — and is what
 * makes the sheet open instantly. [prs] is the resolved detail, null until it lands or
 * if the read failed. [aggregate] is the row's own glyph, carried rather than
 * recomputed.
 */
data class PrSheetState(
    val sessionId: String,
    val title: String,
    val refs: List<PrRef>,
    val aggregate: PrAggregate? = null,
    val loading: Boolean = true,
    val prs: List<PrDetail>? = null,
    val gh: GhReach? = null,
)

/**
 * The desktop's hover tooltip, as something a thumb can reach.
 *
 * The rail says what a session's pull requests came to in one glyph and puts the words
 * behind a hover. A phone has no hover, so the words live here — and there is room for
 * more of them than a tooltip had: each PR's title, branch, and the detail lines the
 * one status had to leave out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrSheet(
    state: PrSheetState,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Column(
                Modifier.padding(
                    start = Metrics.ScreenPadding,
                    end = Metrics.ScreenPadding,
                    bottom = 10.dp,
                ),
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                headline(state)?.let {
                    Text(
                        it,
                        style = TgxType.mono12,
                        color = tgx.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // The one place in the app allowed to say GitHub could not be reached
                // — and only because `gh.ok` actually said so. A status of `unknown`
                // is not evidence of it: on a bridge that has just started cold, that
                // is every merged PR on the machine for one pass.
                if (state.gh?.ok == false) {
                    Text(
                        "GitHub could not be reached — ${state.gh.error ?: "no reason given"}",
                        style = TgxType.mono12,
                        color = tgx.red,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            HorizontalDivider(color = tgx.outlineSoft)

            // Resolved detail when it has landed; otherwise the refs, which already
            // carry the number and the link. Either way every row opens — the URLs are
            // true whatever GitHub had to say, and hiding them is what makes an expired
            // token look like a broken app.
            val resolved = state.prs
            if (resolved != null) {
                // Response order is raised order, and is not re-sorted: the sheet
                // reads as a history of what this session did.
                resolved.forEach { pr ->
                    PrRow(
                        look = prLookOf(pr.status),
                        number = pr.number,
                        title = pr.title,
                        // `label` is `resolveStatus`'s own wording; `prWords` only
                        // covers for a status it did not name.
                        status = pr.label ?: reasonFor(pr.status, state.gh),
                        lines = pr.detail,
                        // Branch first, repo last. The line is one row and truncates,
                        // and on a phone the repo is long enough to eat the age — but
                        // it is also the part that distinguishes least, since every PR
                        // in a session is usually in the same repository. So the
                        // ellipsis is aimed at it deliberately.
                        foot = listOfNotNull(
                            pr.branch,
                            ago(pr.updatedAt).takeIf { it.isNotBlank() }?.let { "updated $it ago" },
                            pr.repo,
                        ).joinToString(" · "),
                        onClick = { onOpenUrl(pr.url) },
                    )
                    HorizontalDivider(color = tgx.outlineSoft)
                }
            } else {
                state.refs.forEach { ref ->
                    PrRow(
                        look = PrLook.Unknown,
                        number = ref.number,
                        title = null,
                        status = if (state.loading) "Asking GitHub…" else reasonFor("unknown", state.gh),
                        lines = emptyList(),
                        foot = ref.repo.orEmpty(),
                        onClick = { onOpenUrl(ref.url) },
                    )
                    HorizontalDivider(color = tgx.outlineSoft)
                }
            }
        }
    }
}

/**
 * "3 pull requests — 2 merged · 1 draft".
 *
 * Null for a single-PR session, where it would only say the headline twice — which is
 * why the rail's tooltip skips it there too.
 *
 * Built from [PrAggregate.counts], **not** by counting the resolved list: the counts
 * are the bridge's answer and recounting them here is a second copy of a decision that
 * only one place should be making.
 */
private fun headline(state: PrSheetState): String? {
    val aggregate = state.aggregate ?: return null
    if (aggregate.total <= 1) return null
    val breakdown = aggregate.counts.entries
        .sortedByDescending { it.value }
        .joinToString(" · ") { "${it.value} ${prWords(it.key)}" }
    return "${aggregate.total} pull requests — $breakdown"
}

/**
 * Why a PR has no status, which is two different things and only one of them is a
 * problem.
 *
 * `unknown` covers gh being unreachable *and* a settled PR the bridge has simply not
 * looked up yet. So `gh.error` decides the wording, and the default blames nothing.
 */
private fun reasonFor(status: String, gh: GhReach?): String =
    if (status == "unknown") gh?.error ?: "not looked up yet" else prWords(status)

/** One pull request. The whole row is the link, so there is no separate affordance. */
@Composable
private fun PrRow(
    look: PrLook,
    number: Int,
    title: String?,
    status: String,
    lines: List<String>,
    foot: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
    ) {
        PrGlyph(
            look = look,
            contentDescription = null,
            size = 18.dp,
            modifier = Modifier.padding(top = 2.dp, end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$number", style = TgxType.mono12)
                Text(
                    status,
                    style = TgxType.mono12,
                    color = tgx.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // What the one status had to leave out: "2 of 5 checks failed",
            // "review required".
            lines.forEach {
                Text(
                    it,
                    style = TgxType.mono12,
                    color = tgx.textMuted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (foot.isNotBlank()) {
                Text(
                    foot,
                    style = TgxType.mono12,
                    color = tgx.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
