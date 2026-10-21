package com.twentygx.tgxcode.ui.installs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.install.PhoneInstallRules
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import java.time.Instant

/** What each state reads as. */
internal fun installStateWord(state: String): String = when (state) {
    PhoneInstallState.PENDING -> "Waiting for a phone"
    PhoneInstallState.DOWNLOADING -> "Downloading"
    PhoneInstallState.INSTALLING -> "Installing"
    PhoneInstallState.NEEDS_ACTION -> "Needs a tap"
    PhoneInstallState.INSTALLED -> "Installed"
    PhoneInstallState.FAILED -> "Failed"
    PhoneInstallState.CANCELLED -> "Cancelled"
    else -> state
}

/**
 * The phone-installs queue, as the bridge has it: what the desktop sent, how far each
 * one got, and Retry for the ones that stopped.
 *
 * There because an install that went quiet used to be invisible from the phone — the
 * record said `downloading` on the desk and nothing here said anything at all. Live:
 * every `phone_install` event replaces its row, and the download's own heartbeats are
 * what move the bar.
 *
 * @param records null until the first read.
 * @param retrying ids with a Retry in flight, so the button cannot be pressed twice.
 */
@Composable
fun InstallsScreen(
    records: List<PhoneInstall>?,
    now: Long,
    retrying: Set<String>,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (records.isNullOrEmpty()) {
        EmptyState(
            headline = if (records == null) "Loading…" else "Nothing sent yet",
            detail = if (records == null) {
                null
            } else {
                "APKs an agent sends to this phone show up here, with how each install went."
            },
            modifier = modifier,
        )
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(records, key = { it.id }) { record ->
            InstallRow(
                record = record,
                now = now,
                retrying = record.id in retrying,
                onRetry = { onRetry(record.id) },
            )
            HorizontalDivider(color = tgx.outlineSoft)
        }
    }
}

@Composable
private fun InstallRow(record: PhoneInstall, now: Long, retrying: Boolean, onRetry: () -> Unit) {
    val title = record.label.ifBlank { record.fileName.ifBlank { record.id } }
    val stateColor = when (record.state) {
        PhoneInstallState.INSTALLED -> tgx.green
        PhoneInstallState.FAILED -> tgx.red
        PhoneInstallState.NEEDS_ACTION -> tgx.yellow
        PhoneInstallState.DOWNLOADING, PhoneInstallState.INSTALLING -> tgx.blue
        else -> tgx.textMuted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                installStateWord(record.state),
                style = MaterialTheme.typography.labelMedium,
                color = stateColor,
            )
            if (record.stalled) Badge("stalled", tgx.yellow)
            if (record.attempts > 1) {
                Text("attempt ${record.attempts}", style = TgxType.mono12, color = tgx.textFaint)
            }
            Text(
                ago(record.updatedAt.takeIf { it > 0 } ?: record.createdAt, Instant.ofEpochMilli(now))
                    .takeIf { it.isNotEmpty() }?.let { "$it ago" }.orEmpty(),
                style = TgxType.mono12,
                color = tgx.textFaint,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val identity = listOfNotNull(record.packageName, record.versionName).joinToString(" · ")
        if (identity.isNotEmpty()) {
            Text(identity, style = TgxType.mono12, color = tgx.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        val fraction = record.progress?.fraction
        when {
            record.state == PhoneInstallState.DOWNLOADING && fraction != null -> {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    color = if (record.stalled) tgx.yellow else MaterialTheme.colorScheme.primary,
                )
                record.progress?.let {
                    Text(
                        "%.1f of %.1f MB".format(it.bytes / 1_048_576.0, it.total / 1_048_576.0),
                        style = TgxType.mono12,
                        color = tgx.textFaint,
                    )
                }
            }
            record.state in PhoneInstallState.WORKING && !record.stalled ->
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
            else -> Unit
        }

        record.message?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (record.state == PhoneInstallState.FAILED) tgx.red else tgx.textMuted,
            )
        }
        if (PhoneInstallRules.canRetry(record)) {
            TextButton(onClick = onRetry, enabled = !retrying, contentPadding = PaddingValues(0.dp)) {
                Text(if (retrying) "Retrying…" else "Retry")
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = TgxType.mono12,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), Pill)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}
