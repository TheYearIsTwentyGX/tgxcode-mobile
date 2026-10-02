package com.twentygx.tgxcode.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.model.AskOutcome
import com.twentygx.tgxcode.data.model.NotificationRow
import com.twentygx.tgxcode.data.repo.Inbox
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** What each `type` reads as, in a word or two. Unknown types fall back to the raw id. */
internal fun notificationKind(type: String?): String = when (type) {
    "permission" -> "Wants permission"
    "plan" -> "Plan to approve"
    "question" -> "Question"
    "finished" -> "Finished"
    "failed" -> "Failed"
    "stalled" -> "Stalled"
    "agent-done" -> "Subagent done"
    "peer-message" -> "Message from a session"
    "handoff" -> "Handed work"
    "schedule-findings" -> "Schedule findings"
    "schedule-failed" -> "Schedule failed"
    "schedule-missed" -> "Schedule missed"
    "later-failed" -> "Scheduled message failed"
    "later-missed" -> "Scheduled message missed"
    else -> type ?: "Notification"
}

/**
 * The desktop's History: everything that reached out, after the fact.
 *
 * There for the case the bridge's own push cannot cover — something pinged while the
 * phone was in a pocket with the app closed, and there is no other record of what.
 *
 * Opening a row marks *that conversation* read, which is what the desktop does on every
 * navigation; Mark all read is the other gesture. A row with no session (a schedule that
 * never produced one) opens nothing.
 */
@Composable
fun NotificationsScreen(
    inbox: Inbox,
    onOpenSession: (String) -> Unit,
    onMarkSessionRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (inbox.unread > 0) "${inbox.unread} unread" else "All read",
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textMuted,
                modifier = Modifier.weight(1f).padding(start = Metrics.ScreenPadding / 2),
            )
            TextButton(onClick = onMarkAllRead, enabled = inbox.unread > 0) { Text("Mark all read") }
            TextButton(onClick = { confirmClear = true }, enabled = inbox.rows.isNotEmpty()) {
                Text("Clear", color = if (inbox.rows.isNotEmpty()) tgx.red else tgx.textFaint)
            }
        }
        HorizontalDivider(color = tgx.outlineSoft)

        if (inbox.rows.isEmpty()) {
            EmptyState(
                headline = if (inbox.loaded) "Nothing yet" else "Loading…",
                detail = if (inbox.loaded) "Asks, finished turns and failures land here." else null,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(inbox.rows, key = { it.id }) { row ->
                    NotificationItem(
                        row = row,
                        onClick = row.sessionId?.let { id ->
                            {
                                onMarkSessionRead(id)
                                onOpenSession(id)
                            }
                        },
                    )
                    HorizontalDivider(color = tgx.outlineSoft)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the history?") },
            text = { Text("This empties the log on the desktop too. Nothing about the sessions changes.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear", color = tgx.red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun NotificationItem(row: NotificationRow, onClick: (() -> Unit)?) {
    val unread = row.loud && !row.read
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.SessionRowHeight)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
    ) {
        Box(Modifier.padding(top = 7.dp, end = 10.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (unread) MaterialTheme.colorScheme.primary else tgx.s3, CircleShape),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notificationKind(row.type),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (row.type) {
                        "failed", "schedule-failed", "later-failed" -> tgx.red
                        "stalled", "permission", "plan", "question" -> tgx.yellow
                        else -> tgx.textMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(ago(row.at), style = TgxType.mono12, color = tgx.textFaint)
            }
            Text(
                row.title ?: "Untitled session",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = TgxType.mono12,
                    color = tgx.textFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.outcome?.let {
                Text(AskOutcome.word(it), style = TgxType.mono12, color = tgx.textMuted)
            }
        }
    }
}
