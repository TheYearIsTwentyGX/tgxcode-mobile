package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.TurnMark
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.data.repo.Conversation
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Every turn of the conversation, plans and questions among them — the desktop's turn
 * rail, as a list to jump from.
 *
 * `turns[]` is the index as of the open; anything said since arrived on the tail as an
 * ordinary event, so turns of yours loaded after the last mark are added from the
 * transcript itself. Newest first, because the reason to open this on a phone is
 * nearly always "a few turns back", not "the beginning".
 */
internal fun turnRail(conversation: Conversation): List<TurnMark> {
    val marked = conversation.turns.map { it.id }.toSet()
    val since = conversation.events
        .filterIsInstance<UserEvent>()
        .filter { it.fromHuman && it.id !in marked }
        .map { TurnMark(kind = "turn", id = it.id, offset = Long.MAX_VALUE, ts = it.ts, text = it.displayText()) }
    return (conversation.turns + since).asReversed()
}

@Composable
fun TurnSheet(
    conversation: Conversation,
    onPick: (TurnMark) -> Unit,
    onDismiss: () -> Unit,
) {
    val rail = remember(conversation.turns, conversation.events.size) { turnRail(conversation) }
    val turnCount = rail.count { it.isTurn }

    TgxSheet(
        title = "Turns",
        subtitle = "$turnCount turn${if (turnCount == 1) "" else "s"} · newest first",
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        LazyColumn(Modifier.padding(bottom = 8.dp)) {
            items(rail, key = { it.kind + it.id }) { mark ->
                val loaded = mark.id in conversation.indexById
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(mark) }
                        .heightIn(min = Metrics.TouchTarget)
                        .padding(
                            start = if (mark.isTurn) Metrics.ScreenPadding else Metrics.ScreenPadding + 18.dp,
                            end = Metrics.ScreenPadding,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        mark.label(),
                        style = if (mark.isTurn) MaterialTheme.typography.bodyMedium else TgxType.mono12,
                        color = when {
                            !mark.isTurn && mark.status == "pending" -> tgx.yellow
                            !mark.isTurn -> MaterialTheme.colorScheme.primary
                            loaded -> MaterialTheme.colorScheme.onSurface
                            else -> tgx.textMuted
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!loaded && mark.isTurn) {
                        Text("not loaded", style = TgxType.mono12, color = tgx.textFaint, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
