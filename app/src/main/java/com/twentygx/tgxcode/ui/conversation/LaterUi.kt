package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.LaterMessage
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** `Tue 02:00`, or `02:00` for today — when a scheduled message is due. */
internal fun dueText(at: Long, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = then.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (then.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "tomorrow $time"
        else -> then.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
    }
}

/**
 * The quick answers to "when", worked out from now: the ones that are actually asked
 * for, so a thumb rarely needs the calendar.
 */
internal fun laterPresets(now: LocalDateTime): List<Pair<String, LocalDateTime>> {
    val tonight = now.toLocalDate().plusDays(if (now.toLocalTime() < LocalTime.of(2, 0)) 0 else 1).atTime(2, 0)
    val morning = now.toLocalDate().plusDays(if (now.toLocalTime() < LocalTime.of(9, 0)) 0 else 1).atTime(9, 0)
    return listOf(
        "In 30 minutes" to now.plusMinutes(30).withSecond(0).withNano(0),
        "In 1 hour" to now.plusHours(1).withSecond(0).withNano(0),
        "Overnight, 02:00" to tonight,
        "Morning, 09:00" to morning,
    )
}

/**
 * When to send it, and in what mode.
 *
 * The mode is the question that matters: the bridge stores it per message and never
 * defaults it, because a message that wakes a session at 02:00 in a mode that asks
 * for permission is answered only if something is connected to say yes. This phone
 * counts while it stays connected — but that is worth saying, not assuming.
 *
 * @param onDone epoch millis and the mode, or null for cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSendDialog(
    mode: String,
    modes: List<String>,
    onDone: (Pair<Long, String>?) -> Unit,
) {
    var pickedMode by remember { mutableStateOf(mode) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<LocalDate?>(null) }
    val zone = ZoneId.systemDefault()
    fun done(at: LocalDateTime) = onDone(at.atZone(zone).toInstant().toEpochMilli() to pickedMode)

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    // The picker answers in UTC midnight, whatever the zone.
                    val day = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                    pickingTime = day
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
        return
    }
    pickingTime?.let { day ->
        val state = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text("At what time?") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { done(day.atTime(state.hour, state.minute)) }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text("Back") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Send later") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                laterPresets(LocalDateTime.now()).forEach { (label, at) ->
                    Text(
                        "$label · ${dueText(at.atZone(zone).toInstant().toEpochMilli())}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { done(at) }
                            .heightIn(min = Metrics.TouchTarget)
                            .padding(vertical = 12.dp),
                    )
                }
                Text(
                    "Pick a day and time…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickingDate = true }
                        .heightIn(min = Metrics.TouchTarget)
                        .padding(vertical = 12.dp),
                )
                HorizontalDivider(color = tgx.outlineSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("In mode", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                    PickerChip(
                        value = pickedMode,
                        options = modes,
                        label = ::modeLabel,
                        onPick = { pickedMode = it },
                        description = "Permission mode",
                    )
                }
                Text(
                    "A mode that asks for permission needs something connected to answer when it " +
                        "arrives — this phone, if it stays connected.",
                    style = TgxType.mono12,
                    color = tgx.textFaint,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

/** One line above the composer when messages are waiting to be delivered here. */
@Composable
fun LaterStrip(messages: List<LaterMessage>, onOpen: () -> Unit) {
    val pending = messages.filter { it.isPending }
    val failed = messages.filter { it.state == "failed" || it.state == "missed" }
    if (pending.isEmpty() && failed.isEmpty()) return
    val next = pending.minByOrNull { it.at }
    Text(
        listOfNotNull(
            next?.let { "${pending.size} scheduled · next ${dueText(it.at)}" + if (it.late) " (late)" else "" },
            "${failed.size} did not send".takeIf { failed.isNotEmpty() },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = if (failed.isNotEmpty()) tgx.red else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp),
    )
}

/** This session's scheduled messages: send now, move, cancel. */
@Composable
fun LaterSheet(
    messages: List<LaterMessage>,
    onSendNow: (LaterMessage) -> Unit,
    onReschedule: (LaterMessage) -> Unit,
    onCancel: (LaterMessage) -> Unit,
    onDismiss: () -> Unit,
) {
    TgxSheet(title = "Scheduled messages", subtitle = "Delivered to this session at their time", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            if (messages.isEmpty()) {
                Text(
                    "Nothing scheduled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tgx.textMuted,
                    modifier = Modifier.padding(Metrics.ScreenPadding),
                )
            }
            messages.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp)) {
                    Text(
                        when (m.state) {
                            "pending" -> dueText(m.at) + if (m.late) " · late, will not be delivered" else ""
                            "delivering" -> "Delivering…"
                            "sent" -> "Sent ${m.sentAt?.let { dueText(it) } ?: ""}"
                            "missed" -> "Missed"
                            "failed" -> "Failed"
                            else -> m.state
                        } + " · ${modeLabel(m.permissionMode)}",
                        style = TgxType.mono12,
                        color = when (m.state) {
                            "failed", "missed" -> tgx.red
                            "sent" -> tgx.green
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    if (m.text.isNotBlank() || m.attachments.isEmpty()) {
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AttachmentNames(m.attachments)
                    m.error?.let { Text(it, style = TgxType.mono12, color = tgx.red) }
                    if (m.isPending) {
                        Row {
                            TextButton(onClick = { onSendNow(m) }) { Text("Send now") }
                            TextButton(onClick = { onReschedule(m) }) { Text("Move…") }
                            TextButton(onClick = { onCancel(m) }) { Text("Cancel", color = tgx.red) }
                        }
                    } else {
                        TextButton(onClick = { onCancel(m) }) { Text("Clear") }
                    }
                }
                HorizontalDivider(color = tgx.outlineSoft)
            }
        }
    }
}
