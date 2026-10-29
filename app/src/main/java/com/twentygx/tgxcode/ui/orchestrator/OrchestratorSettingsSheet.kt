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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.OrchestratorSettings
import com.twentygx.tgxcode.data.model.SettingsPatch
import com.twentygx.tgxcode.data.model.UsageStop
import com.twentygx.tgxcode.data.model.UsageStopPatch
import com.twentygx.tgxcode.data.repo.OWN_AFTER_TURN
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.common.modelsCatalog
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
    /** As [onSave], answering the reply's `ownModelApplied` — or null when it failed. */
    onSaveModel: (SettingsPatch, (String?) -> Unit) -> Unit,
    onResume: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stop = settings.usageStop
    var confirmOff by remember { mutableStateOf(false) }
    // The orchestrator was mid-turn when its own model was changed. Held for as long
    // as the sheet is open: that is as long as anybody is looking for it.
    var afterTurn by remember { mutableStateOf(false) }

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

            ModelSettings(settings, afterTurn) { patch, own ->
                onSaveModel(patch) { applied ->
                    if (own && applied != null) afterTurn = applied == OWN_AFTER_TURN
                }
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

/**
 * Which models it and its workers run with — the desktop popover's `modelSettings`.
 * Its own: the composer's pick, or one fixed here. Its workers': its own choice, with
 * a default for when it does not make one, or one model for all of them.
 *
 * [onSave]'s second argument is whether the patch is the orchestrator's own model, the
 * only change that can be held until a turn ends.
 */
@Composable
private fun ModelSettings(settings: OrchestratorSettings, afterTurn: Boolean, onSave: (SettingsPatch, Boolean) -> Unit) {
    val catalog = modelsCatalog()
    val models = catalog.models.map { it.id to it.label }
    val efforts = catalog.efforts.map { it to it }
    val chooseModel = settings.workerModel == OrchestratorSettings.CHOOSE
    val chooseEffort = settings.workerEffort == OrchestratorSettings.CHOOSE
    val value = SettingsPatch::modelValue

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Orchestrator model", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePicker(
                settings.orchestratorModel.orEmpty(), listOf("" to "Composer decides") + models,
                { onSave(SettingsPatch(orchestratorModel = value(it)), true) }, Modifier.weight(1.4f),
            )
            ChoicePicker(
                settings.orchestratorEffort.orEmpty(), listOf("" to "Composer's effort") + efforts,
                { onSave(SettingsPatch(orchestratorEffort = value(it)), true) }, Modifier.weight(1f),
            )
        }
        if (afterTurn) {
            Text(
                "Saved. It switches when its current turn ends.",
                style = MaterialTheme.typography.bodySmall,
                color = tgx.yellow,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Worker model", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePicker(
                settings.workerModel, listOf(OrchestratorSettings.CHOOSE to "Let the orchestrator choose") + models,
                { onSave(SettingsPatch(workerModel = JsonPrimitive(it)), false) }, Modifier.weight(1.4f),
            )
            ChoicePicker(
                settings.workerEffort, listOf(OrchestratorSettings.CHOOSE to "Its choice") + efforts,
                { onSave(SettingsPatch(workerEffort = JsonPrimitive(it)), false) }, Modifier.weight(1f),
            )
        }
    }

    if (chooseModel || chooseEffort) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Default when it doesn't choose", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chooseModel) {
                    ChoicePicker(
                        settings.workerDefaultModel.orEmpty(), listOf("" to "Inherit") + models,
                        { onSave(SettingsPatch(workerDefaultModel = value(it)), false) }, Modifier.weight(1.4f),
                    )
                }
                if (chooseEffort) {
                    ChoicePicker(
                        settings.workerDefaultEffort.orEmpty(), listOf("" to "Inherit") + efforts,
                        { onSave(SettingsPatch(workerDefaultEffort = value(it)), false) }, Modifier.weight(1f),
                    )
                }
            }
        }
    }

    Text(
        workerModelWords(settings, catalog::labelOf),
        style = MaterialTheme.typography.bodySmall,
        color = tgx.textMuted,
    )
}

/** A dropdown over `value to label` pairs. A value not among them shows as itself. */
@Composable
private fun ChoicePicker(value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight)) {
            Text(
                options.firstOrNull { it.first == value }?.second ?: value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; if (v != value) onPick(v) },
                )
            }
        }
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
