package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.TaskList
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The agent's own task list, as a one-line bar that opens into the list.
 *
 * Shut by default and one line high when shut: it sits above the transcript, and every
 * pixel it takes is one the conversation does not get. Open, it caps its height and
 * scrolls, for the same reason.
 *
 * Absent when the session kept no list. Items are keyed by nothing — a `todo` list has
 * no ids, and an index key would mis-associate rows the moment the agent inserted one.
 */
@Composable
fun ChecklistBar(list: TaskList?, sessionId: String) {
    if (list == null || list.items.isEmpty()) return
    var open by rememberSaveable(sessionId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .heightIn(min = Metrics.TouchTarget)
                .padding(horizontal = Metrics.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${list.done} of ${list.total} done",
                style = TgxType.mono12,
                color = if (list.done == list.total) tgx.green else MaterialTheme.colorScheme.primary,
            )
            Text(
                list.current?.let { " · $it" } ?: if (list.idle) " · stopped with work left" else "",
                style = TgxType.mono12,
                color = if (list.idle) tgx.yellow else tgx.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "▴" else "▾", color = tgx.textFaint)
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.ScreenPadding)
                    .padding(bottom = 8.dp),
            ) {
                list.items.forEach { item ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            when {
                                item.isDone -> "✓"
                                item.isActive -> "▸"
                                else -> "○"
                            },
                            style = TgxType.mono12,
                            color = when {
                                item.isDone -> tgx.green
                                item.isActive -> MaterialTheme.colorScheme.primary
                                else -> tgx.textFaint
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            item.subject,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.isDone) tgx.textFaint else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                        )
                    }
                }
                if (list.truncated > 0) {
                    Text("…and ${list.truncated} more", style = TgxType.mono12, color = tgx.textFaint)
                }
            }
        }
        HorizontalDivider(color = tgx.outlineSoft)
    }
}
