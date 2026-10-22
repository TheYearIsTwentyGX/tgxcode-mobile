package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.QueuedMessage
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The messages waiting behind the running turn — the desktop's composer chips, as a
 * sheet.
 *
 * Drawn straight from `runner.queue`, which every queue write answers with afresh, so
 * there is nothing of its own to fetch or to go stale.
 *
 * A `handed` message has already been given to the CLI to fold into the running turn
 * at its next tool step. It keeps its place at the front — `reorder` ignores its id —
 * so it gets no arrows, and dropping it can lose the race to the turn reading it.
 */
@Composable
fun QueueSheet(
    queue: List<QueuedMessage>,
    onDrop: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val movable = queue.filterNot { it.handed }.mapNotNull { it.id }

    TgxSheet(
        title = "Queued messages",
        subtitle = if (queue.isEmpty()) "Nothing waiting" else "Sent in this order when the turn ends",
        onDismiss = onDismiss,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            queue.forEach { message ->
                val id = message.id ?: return@forEach
                val at = movable.indexOf(id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Metrics.TouchTarget)
                        .padding(start = Metrics.ScreenPadding, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            message.text.orEmpty().ifBlank { "(attachments only)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (message.handed) {
                            Text(
                                "Handed to the running turn",
                                style = TgxType.mono12,
                                color = tgx.textFaint,
                            )
                        }
                    }
                    if (!message.handed) {
                        TextButton(onClick = { onMove(id, -1) }, enabled = at > 0) { Text("↑") }
                        TextButton(onClick = { onMove(id, 1) }, enabled = at in 0 until movable.lastIndex) {
                            Text("↓")
                        }
                    }
                    TextButton(onClick = { onDrop(id) }) { Text("Drop", color = tgx.red) }
                }
                HorizontalDivider(color = tgx.outlineSoft)
            }
        }
    }
}
