package com.twentygx.tgxcode.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.background.BackgroundRules
import com.twentygx.tgxcode.data.background.BackgroundStatus
import com.twentygx.tgxcode.ui.theme.tgx
import java.time.Instant

/**
 * "Background installs": each thing an install with the app closed depends on, whether
 * it holds, and a button for the ones that do not. There because "installs only happen
 * when I open the app" is otherwise a guess between half a dozen causes, most of them a
 * switch somewhere in the system settings.
 */
@Composable
fun BackgroundCard(
    status: BackgroundStatus,
    now: Long,
    onFix: (BackgroundRules.Fix) -> Unit,
    onAppDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val at = Instant.ofEpochMilli(now)
    val rows = BackgroundRules.rows(status, now) { ago(it, at) }
    Column(
        modifier
            .fillMaxWidth()
            .background(tgx.s2, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Background installs", style = MaterialTheme.typography.bodyLarge)
        Text(
            "What has to hold for an app sent from the desktop to install with TGXCode closed.",
            style = MaterialTheme.typography.bodySmall,
            color = tgx.textMuted,
        )
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (row.ok) tgx.green else tgx.yellow, CircleShape),
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(row.label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        row.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.ok) tgx.textMuted else tgx.yellow,
                    )
                }
                val fix = row.fix
                // "Run now" is offered even when the check is fine: it is also how to
                // ask "is anything waiting?" without opening the queue.
                if (fix != null && (!row.ok || fix == BackgroundRules.Fix.CheckNow)) {
                    TextButton(onClick = { onFix(fix) }) { Text(row.fixLabel ?: "Fix") }
                }
            }
        }
        Text(
            "Motorola adds a switch of its own on top of Android's battery optimisation: " +
                "App info → App battery usage → Allow background usage (or Unrestricted). " +
                "With it restricted, the background service is not allowed to start at all.",
            style = MaterialTheme.typography.bodySmall,
            color = tgx.textMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onAppDetails) { Text("Open App info") }
    }
}
