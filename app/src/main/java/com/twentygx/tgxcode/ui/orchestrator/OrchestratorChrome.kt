package com.twentygx.tgxcode.ui.orchestrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.TaskOrigin
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Overview · Conversation — the desktop's segmented switch, over an orchestrator's
 * screen. The badge counts what the overview says needs you, so the conversation tab
 * cannot hide a question waiting on the other one.
 */
@Composable
fun OrchestratorTabs(overview: Boolean, needs: Int, onOverview: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .background(tgx.s1)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 6.dp),
    ) {
        SegmentedButton(
            selected = overview,
            onClick = { onOverview(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {},
        ) {
            BadgedBox(badge = { if (needs > 0) Badge { Text("$needs") } }) {
                Text("Overview", modifier = Modifier.padding(end = if (needs > 0) 10.dp else 0.dp))
            }
        }
        SegmentedButton(
            selected = !overview,
            onClick = { onOverview(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
        ) { Text("Conversation") }
    }
}

/** On a worker's conversation: whose it is, where it came from, and the way back. */
@Composable
fun WorkerBanner(
    orchestratorTitle: String?,
    origin: TaskOrigin?,
    closed: Boolean,
    onOpenOrchestrator: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(tgx.s1)
            .padding(start = Metrics.ScreenPadding, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Worker of ${orchestratorTitle ?: "an orchestrator"}",
                style = MaterialTheme.typography.labelMedium,
                color = tgx.peach,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val from = when (origin?.from) {
                "draft" -> "From your draft" + (origin.title?.let { " “${clip(it, 40)}”" } ?: "")
                "dialog" -> "From the Start form" + (origin.title?.let { " (“${clip(it, 40)}”)" } ?: "")
                else -> null
            }
            listOfNotNull(from, "closed".takeIf { closed }).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tgx.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onOpenOrchestrator) { Text("Open the orchestrator") }
    }
}
