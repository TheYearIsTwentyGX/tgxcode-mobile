package com.twentygx.tgxcode.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.Quota
import com.twentygx.tgxcode.data.model.QuotaWindow
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import kotlin.math.roundToInt

/** `3m`, `2h`, `4d` — for ages and countdowns alike. */
internal fun span(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 172_800 -> "${seconds / 3600}h"
    else -> "${seconds / 86_400}d"
}

/** `42%`, or `—` when nobody has said — which is not zero, and must not read as it. */
internal fun percentText(window: QuotaWindow): String =
    window.usedPercent?.let { "${it.roundToInt()}%" } ?: "—"

@Composable
private fun windowColor(window: QuotaWindow, now: Long?): Color = when {
    window.isRejected -> tgx.red
    window.isWarning -> tgx.yellow
    window.isStale(now) -> tgx.textFaint
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * The top bar's quota reading: the first window only, which is the 5-hour one when
 * there is one. The order is the bridge's and is meaningful.
 *
 * Greyed when the reading is over half an hour old. Presenting an old percentage as
 * current is the one failure this whole shape exists to prevent.
 */
@Composable
fun QuotaPill(quota: Quota?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val window = quota?.windows?.firstOrNull() ?: return
    Box(
        modifier
            .clip(Pill)
            .clickable(onClick = onClick)
            .heightIn(min = Metrics.TouchTarget)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${window.name} ${percentText(window)}",
            style = TgxType.mono12,
            color = windowColor(window, quota.now),
        )
    }
}

/** Every window, each with its age and its reset, and a way to ask for a fresh reading. */
@Composable
fun QuotaSheet(
    quota: Quota?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = quota?.now
    TgxSheet(title = "Quota", subtitle = "Account-wide, from the desktop's last reading", onDismiss = onDismiss) {
        Column(
            Modifier.padding(Metrics.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val windows = quota?.windows.orEmpty()
            if (windows.isEmpty()) {
                Text("No reading yet.", style = MaterialTheme.typography.bodyMedium, color = tgx.textMuted)
            }
            windows.forEach { window -> WindowRow(window, now) }

            quota?.beacon?.let { beacon ->
                val line = when {
                    beacon.running -> "Taking a reading now…"
                    beacon.ok == false -> beacon.reason?.let { "Last automatic reading failed: $it" }
                    !beacon.enabled -> "Readings refresh only while a terminal is open on the desktop."
                    else -> null
                }
                line?.let { Text(it, style = TgxType.mono12, color = tgx.textMuted) }
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !refreshing,
                shape = Pill,
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.TouchTarget),
            ) { Text(if (refreshing) "Reading…" else "Take a reading now") }
        }
    }
}

@Composable
private fun WindowRow(window: QuotaWindow, now: Long?) {
    val color = windowColor(window, now)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                window.label ?: window.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(percentText(window), style = MaterialTheme.typography.bodyMedium, color = color)
        }
        val used = (window.usedPercent ?: 0.0).coerceIn(0.0, 100.0).toFloat() / 100f
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(tgx.s3, RoundedCornerShape(3.dp)),
        ) {
            if (window.usedPercent != null) {
                Box(
                    Modifier
                        .fillMaxWidth(used)
                        .height(6.dp)
                        .background(color, RoundedCornerShape(3.dp)),
                )
            }
        }
        val facts = listOfNotNull(
            when (window.status) {
                "rejected" -> if (window.isUsingOverage) "over the limit — on overage" else "over the limit"
                "allowed_warning" -> "near the limit"
                else -> null
            },
            window.ageSeconds(now)?.let { "read ${span(it)} ago" } ?: "never read".takeIf { window.usedPercent == null },
            window.resetsAt?.let { at -> now?.let { "resets in ${span((at - it).coerceAtLeast(0))}" } },
        )
        Text(facts.joinToString(" · "), style = TgxType.mono12, color = tgx.textFaint)
    }
}
