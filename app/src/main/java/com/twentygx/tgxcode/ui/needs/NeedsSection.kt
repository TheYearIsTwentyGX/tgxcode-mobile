package com.twentygx.tgxcode.ui.needs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.BoardCard
import com.twentygx.tgxcode.data.model.BoardReason
import com.twentygx.tgxcode.data.model.Decision
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * What is blocked on you, as a section at the top of the Sessions list.
 *
 * This used to be a screen and a tab of its own. It reads better here: "what needs me"
 * and "what else is there" are the same question asked at two zoom levels, and on a
 * phone a tab you have to leave to see the second half is a tab you check twice.
 *
 * A `LazyListScope` extension rather than a composable, because it has to share one
 * scroll with the session rows below it. Two nested `LazyColumn`s would each want to
 * be as tall as their content, and the inner one would win.
 *
 * The board arrives **already ordered needs-you-first** (`overview.js` ranks
 * `ask > error > here > elsewhere > pinned`), and this deliberately does not re-sort.
 * Re-sorting here would be a second opinion about a question the bridge has already
 * answered, and the two would drift.
 */
fun LazyListScope.needsSection(
    board: Overview?,
    answering: Set<String>,
    onOpen: (String) -> Unit,
    onAnswer: (Ask, String) -> Unit,
) {
    val cards = board?.sessions.orEmpty()
    if (cards.isEmpty()) return

    item(key = "needs-header") { SectionHeader("Needs you") }
    items(cards, key = { "needs-${it.sessionId}" }) { card ->
        BoardCardItem(
            card = card,
            busy = card.ask?.requestId in answering,
            onOpen = { onOpen(card.sessionId) },
            onAnswer = onAnswer,
            modifier = Modifier.padding(
                horizontal = Metrics.ScreenPadding,
                vertical = 5.dp,
            ),
        )
    }
}

/** The one section heading, shared by every list that has more than one part. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = Metrics.ScreenPadding,
                end = Metrics.ScreenPadding,
                top = 14.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tgx.textMuted,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(it, style = TgxType.mono12, color = tgx.textFaint)
        }
    }
}

@Composable
fun BoardCardItem(
    card: BoardCard,
    busy: Boolean,
    onOpen: () -> Unit,
    onAnswer: (Ask, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tgx
    val accent = when (card.boardReason) {
        BoardReason.Ask -> colors.yellow
        BoardReason.Error -> colors.red
        BoardReason.Here -> colors.green
        else -> Color.Transparent
    }
    // An ask also lifts the surface a step, so the card that needs you reads as
    // nearer than the ones that do not.
    val surface = if (card.boardReason == BoardReason.Ask) colors.s3 else colors.s2

    Column(
        modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(Radius.Lg))
            .drawBehind {
                drawRect(accent, Offset.Zero, Size(Metrics.ReasonBorder.toPx(), size.height))
            }
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                card.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            com.twentygx.tgxcode.ui.theme.projectAccent(card.cwd)?.let { c ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(start = 8.dp, bottom = 5.dp)
                        .size(8.dp)
                        .background(c, androidx.compose.foundation.shape.CircleShape),
                )
            }
            if (card.place.isNotBlank()) {
                Text(
                    card.place,
                    style = TgxType.mono12,
                    color = colors.textFaint,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Text(card.why, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)

        // The bridge's judgement, not the CLI's: busy, silent past the threshold, no
        // tool call open and nothing waiting on you. An ask outranks it — waiting on a
        // person is not silence.
        if (card.ask == null && card.runner?.stalled == true) {
            Text(
                "Stalled — nothing from it for a while",
                style = MaterialTheme.typography.labelMedium,
                color = colors.yellow,
            )
        }

        // Only when there is no ask: if something is blocked, the block is the news.
        if (card.ask == null) {
            card.headline?.let {
                Text(
                    it,
                    style = TgxType.mono125,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        card.taskLine?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        }

        val ask = card.ask
        if (ask != null && ask.askKind == AskKind.Tool) {
            // A tool ask is answerable from the card: `{requestId, decision}` is the
            // complete answer and there is nothing to read first.
            val stamped = ask.copy(sessionId = ask.sessionId ?: card.sessionId)
            AskButton("Allow", { onAnswer(stamped, Decision.ALLOW) },
                kind = AskButtonKind.Affirmative, enabled = !busy)
            AskButton("Allow ${ask.toolLabel} all session",
                { onAnswer(stamped, Decision.ALLOW_ALWAYS) }, enabled = !busy)
            AskButton("Deny", { onAnswer(stamped, Decision.DENY) },
                kind = AskButtonKind.Destructive, enabled = !busy)
            AskButton("Open the session", onOpen)
        } else {
            // A plan or a question deliberately gets one button that goes and shows
            // it. Two buttons against a plan nobody has read is worse than one
            // button that opens it.
            AskButton(
                if (ask != null) "Read it →" else "Open",
                onOpen,
                kind = if (ask != null) AskButtonKind.Affirmative else AskButtonKind.Neutral,
            )
        }
    }
}
