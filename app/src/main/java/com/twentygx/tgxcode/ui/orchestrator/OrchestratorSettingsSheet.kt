package com.twentygx.tgxcode.ui.orchestrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.OrchestratorSettings
import com.twentygx.tgxcode.data.model.SettingsPatch
import com.twentygx.tgxcode.data.model.UsageStop
import com.twentygx.tgxcode.data.model.UsageStopPatch
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One orchestrator's settings — the desktop's popover, as a sheet.
 *
 * Every change saves the moment it is made, as there: a `PUT` of just that field,
 * which the bridge merges and answers with the whole payload. A number saves once it
 * has stopped changing and is in range, so typing "85" does not save an 8 on the way.
 *
 * These are per orchestrator. The defaults a new one starts with are user prefs, which
 * `PUT /api/prefs` holds and a phone may not write — so they are not here.
 */
@Composable
fun OrchestratorSettingsSheet(
    settings: OrchestratorSettings,
    paused: Boolean,
    nowMillis: Long,
    onSave: (SettingsPatch) -> Unit,
    onResume: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stop = settings.usageStop
    var confirmOff by remember { mutableStateOf(false) }

    TgxSheet(title = "This orchestrator's settings", onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Metrics.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("At most", style = MaterialTheme.typography.bodyMedium)
                NumberField(settings.maxRunning, 1..10, { onSave(SettingsPatch(maxRunning = it)) }, Modifier.padding(horizontal = 8.dp))
                Text("workers running at once", style = MaterialTheme.typography.bodyMedium)
            }

            SwitchRow("Start each worker in its own worktree", settings.worktree) {
                onSave(SettingsPatch(worktree = it))
            }

            SwitchRow("Stop everything at a usage limit", stop.enabled) {
                onSave(SettingsPatch(usageStop = UsageStopPatch(enabled = it)))
            }

            if (stop.enabled) {
                WindowPicker(stop.window) { onSave(SettingsPatch(usageStop = UsageStopPatch(window = it))) }
                if (stop.window == "all") {
                    Text(
                        "Stop past any of these limits. Leave one empty to ignore that window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tgx.textMuted,
                    )
                    listOf(
                        "five_hour" to "Five-hour",
                        "seven_day" to "Weekly",
                        "seven_day_opus" to "Weekly, Opus",
                        "seven_day_sonnet" to "Weekly, Sonnet",
                    ).forEach { (window, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            NumberField(
                                stop.limits.of(window),
                                1..100,
                                { v ->
                                    onSave(
                                        SettingsPatch(
                                            usageStop = UsageStopPatch(
                                                // A null here is a value — "leave that window
                                                // out" — so it is sent, not dropped.
                                                limits = JsonObject(mapOf(window to (v?.let { JsonPrimitive(it) } ?: JsonNull))),
                                            ),
                                        ),
                                    )
                                },
                                allowEmpty = true,
                            )
                            Text(" %", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Stop at", style = MaterialTheme.typography.bodyMedium)
                        NumberField(stop.percent, 1..100, { onSave(SettingsPatch(usageStop = UsageStopPatch(percent = it))) }, Modifier.padding(horizontal = 8.dp))
                        Text("% of the ${UsageStop.windowLabel(stop.window)} window", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            SwitchRow("Carry on by itself when the window resets", stop.autoResume, enabled = stop.enabled) {
                onSave(SettingsPatch(usageStop = UsageStopPatch(autoResume = it)))
            }
            stop.autoResumeBy?.takeIf { it.by == "agent" }?.let { by ->
                Text(
                    "Turned ${if (stop.autoResume) "on" else "off"} by the orchestrator " +
                        agoWords(by.at, nowMillis) +
                        (by.reason?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = tgx.textMuted,
                )
            }

            if (paused) {
                OutlinedButton(onClick = onResume, shape = Pill, modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.TouchTarget)) {
                    Text("Resume")
                }
            }

            Text(
                "The defaults for new orchestrators are in the desktop's Settings › Orchestrators.",
                style = MaterialTheme.typography.bodySmall,
                color = tgx.textFaint,
            )

            OutlinedButton(
                onClick = { confirmOff = true },
                shape = Pill,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tgx.red),
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.TouchTarget),
            ) { Text("Stop being an orchestrator") }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Stop this session being an orchestrator?") },
            text = {
                Text(
                    "Its workers carry on as ordinary sessions, and its inbox is discarded. " +
                        "A worker waiting on an approval has it denied, and tasks it has not " +
                        "started go back to Drafts.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmOff = false; onTurnOff() }) { Text("Turn it off", color = tgx.red) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else tgx.textFaint,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun WindowPicker(window: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight)) {
            Text(if (window == "all") "All limits" else "The ${UsageStop.windowLabel(window)} window")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            UsageStop.WINDOWS.forEach { w ->
                DropdownMenuItem(
                    text = { Text(if (w == "all") "All limits" else "The ${UsageStop.windowLabel(w)} window") },
                    onClick = { open = false; onPick(w) },
                )
            }
        }
    }
}

/**
 * A small number box that saves once it settles on a value in [range] that differs
 * from [value]. With [allowEmpty], clearing it saves null.
 */
@Composable
private fun NumberField(
    value: Int?,
    range: IntRange,
    onSave: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(text) {
        val parsed = text.trim().toIntOrNull()
        val valid = (parsed != null && parsed in range) || (allowEmpty && text.isBlank())
        if (!valid || parsed == value) return@LaunchedEffect
        delay(700)
        onSave(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(3) },
        singleLine = true,
        placeholder = { if (allowEmpty) Text("off") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TgxType.mono12.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.width(72.dp),
    )
}
