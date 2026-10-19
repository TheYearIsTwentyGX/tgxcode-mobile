package com.twentygx.tgxcode.ui.orchestrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.OrchestratorRow
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Pick the orchestrator a task goes to — `web/orch-picker.js`.
 *
 * In the bridge's order, untouched: live ones first, then the most recently active.
 * Picking files the task and nothing else; the orchestrator is woken and decides how
 * to start it.
 *
 * @param rows null while the list is still being read.
 */
@Composable
fun OrchestratorPickerSheet(
    subject: String?,
    rows: List<OrchestratorRow>?,
    onPick: (OrchestratorRow) -> Unit,
    onDismiss: () -> Unit,
) {
    TgxSheet(
        title = "Send to an orchestrator",
        subtitle = (subject?.let { "“${clip(it, 60)}” becomes" } ?: "This becomes") +
            " a task in its inbox. It decides how to start it.",
        onDismiss = onDismiss,
    ) {
        when {
            rows == null -> Text(
                "Looking for orchestrators…",
                style = MaterialTheme.typography.bodyMedium,
                color = tgx.textMuted,
                modifier = Modifier.padding(Metrics.ScreenPadding),
            )
            rows.isEmpty() -> Text(
                "No orchestrators yet. Tick “Start as an orchestrator” when you start a " +
                    "session, or turn one on from a session's menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = tgx.textMuted,
                modifier = Modifier.padding(Metrics.ScreenPadding),
            )
            else -> LazyColumn {
                items(rows, key = { it.orchestratorId }) { row ->
                    PickerRow(row, onClick = { onPick(row) })
                    HorizontalDivider(color = tgx.outlineSoft)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(row: OrchestratorRow, onClick: () -> Unit) {
    val colors = tgx
    val dot = when {
        row.paused != null -> colors.yellow
        row.live -> colors.green
        else -> colors.textFaint
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Metrics.SessionRowHeight)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.test) Pillish("test", colors.textMuted)
                if (row.paused != null) Pillish("paused", colors.yellow)
            }
            Text(
                listOfNotNull(
                    row.projectName?.takeIf { it.isNotBlank() },
                    "${row.running} of ${row.maxRunning} running",
                    "${row.queued} queued".takeIf { row.queued > 0 },
                    "${row.openTasks} task${if (row.openTasks == 1) "" else "s"} waiting".takeIf { row.openTasks > 0 },
                ).joinToString(" · "),
                style = TgxType.mono12,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Pillish(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = TgxType.badge,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(1.dp, color.copy(alpha = 0.6f), Pill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
