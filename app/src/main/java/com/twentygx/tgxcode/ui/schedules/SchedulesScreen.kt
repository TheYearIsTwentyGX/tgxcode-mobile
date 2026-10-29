package com.twentygx.tgxcode.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.data.model.Schedule
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.ui.common.effortOptions
import com.twentygx.tgxcode.ui.common.modelOptions
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.conversation.PickerChip
import com.twentygx.tgxcode.ui.conversation.dueText
import com.twentygx.tgxcode.ui.conversation.modeLabel
import com.twentygx.tgxcode.ui.newsession.RecentDir
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The form, as plain values. [toBody] turns it into exactly what changed. */
data class ScheduleForm(
    val title: String = "",
    val cwd: String = "",
    val prompt: String = "",
    val cron: String = "0 2 * * *",
    val enabled: Boolean = true,
    /** `none`, `git-commits` or `open-prs`. */
    val gate: String = "none",
    val ref: String = "origin/main",
    val mode: String = "plan",
    val model: String = "",
    val effort: String = "",
) {
    companion object {
        fun of(s: Schedule) = ScheduleForm(
            title = s.title.orEmpty(),
            cwd = s.cwd,
            prompt = s.prompt,
            cron = s.cron,
            enabled = s.enabled,
            gate = s.gate?.kind ?: "none",
            ref = s.gate?.ref ?: "origin/main",
            mode = s.permissionMode,
            model = SessionChoices.knownModel(s.model),
            effort = SessionChoices.knownEffort(s.effort),
        )
    }
}

/**
 * The body to send: for a new schedule everything, for an edit **only what changed**.
 *
 * The partial is load-bearing, not tidiness. Most schedules here run in `dontAsk`, which a
 * remote caller may not set — and the bridge checks every mode it is *sent*, so a body
 * that restated an unchanged mode would turn "pause it" into a 403.
 */
internal fun ScheduleForm.toBody(was: ScheduleForm?): JsonObject = buildJsonObject {
    fun changed(pick: (ScheduleForm) -> Any?) = was == null || pick(was) != pick(this@toBody)
    if (changed { it.title }) put("title", orNull(title.trim()))
    if (changed { it.cwd }) put("cwd", cwd)
    if (changed { it.prompt }) put("prompt", prompt.trim())
    if (changed { it.cron }) put("cron", cron.trim())
    if (changed { it.enabled }) put("enabled", enabled)
    if (changed { it.mode }) put("permissionMode", mode)
    if (changed { it.model }) put("model", orNull(model))
    if (changed { it.effort }) put("effort", orNull(effort))
    if (changed { it.gate } || (gate == "git-commits" && changed { it.ref })) {
        put(
            "gate",
            when (gate) {
                "git-commits" -> buildJsonObject {
                    put("kind", "git-commits")
                    put("ref", ref.trim())
                }
                "open-prs" -> buildJsonObject { put("kind", "open-prs") }
                else -> orNull("")
            },
        )
    }
}

/** Blank as JSON `null` — a value on a PATCH, where absent would mean "leave it". */
private fun orNull(value: String): JsonPrimitive = JsonPrimitive(value.ifBlank { null })

/** Common shapes, composed rather than parsed — the bridge describes whatever is typed. */
private val PRESETS = listOf(
    "Nightly 02:00" to "0 2 * * *",
    "Weekdays 09:00" to "0 9 * * 1-5",
    "Hourly" to "0 * * * *",
    "Mondays 08:00" to "0 8 * * 1",
)

/**
 * Sessions that start on a clock, held and fired by the desktop.
 *
 * A phone can pause, resume, run, edit and delete them — but not start one in
 * `bypassPermissions` or `dontAsk`, which the bridge refuses a remote caller, and which
 * most of them use. So Run now on one of those says why it cannot, rather than looking
 * broken.
 */
@Composable
fun SchedulesScreen(
    schedules: List<Schedule>?,
    dirs: List<RecentDir>,
    modes: List<String>,
    api: BridgeApi,
    onSetEnabled: (Schedule, Boolean) -> Unit,
    onRun: (Schedule) -> Unit,
    onDelete: (Schedule) -> Unit,
    onSave: (id: String?, body: JsonObject, onSaved: () -> Unit) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Schedule?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding / 2), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${schedules?.count { it.enabled } ?: 0} running of ${schedules?.size ?: 0}",
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textMuted,
                modifier = Modifier.weight(1f).padding(start = Metrics.ScreenPadding / 2),
            )
            TextButton(onClick = { creating = true }) { Text("New schedule") }
        }
        HorizontalDivider(color = tgx.outlineSoft)
        when {
            schedules == null -> EmptyState("Loading…", modifier = Modifier.weight(1f))
            schedules.isEmpty() -> EmptyState("No schedules", "Sessions that start on a clock appear here.", Modifier.weight(1f))
            else -> LazyColumn(Modifier.weight(1f)) {
                items(schedules, key = { it.id }) { s ->
                    ScheduleRow(
                        s,
                        onSetEnabled = { onSetEnabled(s, it) },
                        onRun = { onRun(s) },
                        onEdit = { editing = s },
                        onDelete = { deleting = s },
                        onOpenLast = s.lastSessionId?.let { id -> { onOpenSession(id) } },
                    )
                    HorizontalDivider(color = tgx.outlineSoft)
                }
            }
        }
    }

    if (creating || editing != null) {
        val original = editing
        ScheduleEditor(
            original = original,
            dirs = dirs,
            modes = modes,
            api = api,
            onSave = { body -> onSave(original?.id, body) { creating = false; editing = null } },
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${s.displayTitle}”?") },
            text = { Text("Its run history and its marker go with it, so recreating it starts its range from scratch.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(s) }) { Text("Delete", color = tgx.red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun ScheduleRow(
    s: Schedule,
    onSetEnabled: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenLast: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(s.projectName, s.cronText ?: s.cron, modeLabel(s.permissionMode)).joinToString(" · "),
                    style = TgxType.mono12,
                    color = tgx.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A spent one-time schedule is finished, not paused: its switch would only
            // re-arm it for the same date a year on.
            if (!s.spent) Switch(checked = s.enabled, onCheckedChange = onSetEnabled)
        }
        Text(
            listOfNotNull(
                when {
                    s.spent -> "finished"
                    !s.enabled -> "paused"
                    s.nextRunAt != null -> "next ${dueText(s.nextRunAt)}"
                    else -> "no future run"
                },
                s.lastFiredAt?.let { "ran ${ago(it)} ago" + (s.lastOutcome?.let { o -> " — $o" } ?: "") },
                s.lastSkipReason?.let { "last slot skipped: $it" },
                "${s.reviewsInFlight} reviewing".takeIf { s.reviewsInFlight > 0 },
            ).joinToString(" · "),
            style = TgxType.mono12,
            color = when {
                s.lastOutcome == "BLOCK" || s.lastOutcome == "error" || s.lastSkipReason == "error" -> tgx.red
                s.lastOutcome == "CONCERNS" -> tgx.yellow
                else -> tgx.textMuted
            },
        )
        s.lastError?.let { Text(it, style = TgxType.mono12, color = tgx.red, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row {
            TextButton(onClick = onRun) { Text("Run now") }
            TextButton(onClick = onEdit) { Text("Edit") }
            onOpenLast?.let { TextButton(onClick = it) { Text("Last run") } }
            TextButton(onClick = onDelete) { Text("Delete", color = tgx.red) }
        }
    }
}

@Composable
private fun ScheduleEditor(
    original: Schedule?,
    dirs: List<RecentDir>,
    modes: List<String>,
    api: BridgeApi,
    onSave: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
) {
    val was = remember(original?.id) { original?.let { ScheduleForm.of(it) } }
    var form by remember(original?.id) {
        mutableStateOf(was ?: ScheduleForm(cwd = dirs.firstOrNull()?.path.orEmpty()))
    }
    // The bridge's reading of what is typed, so a phone never ships a second cron parser.
    var described by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(form.cron) {
        delay(400)
        described = api.describeCron(form.cron.trim()).getOrNull()?.let { d ->
            listOfNotNull(d.text, d.next?.let { "next ${dueText(it)}" } ?: "never matches a future date").joinToString(" · ")
        } ?: "Not an expression the bridge accepts"
    }
    // A mode this phone may not set is still shown, so an edit never seems to change it.
    val offered = (modes + form.mode).distinct()

    TgxSheet(title = if (original == null) "New schedule" else "Edit schedule", onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Metrics.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = form.title, onValueChange = { form = form.copy(title = it) },
                label = { Text("Title") }, placeholder = { Text("First line of the prompt") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Where", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
            PickerChip(
                value = form.cwd,
                options = (dirs.map { it.path } + form.cwd).filter { it.isNotBlank() }.distinct(),
                label = { path -> dirs.firstOrNull { it.path == path }?.label ?: path.ifBlank { "Pick a directory" } },
                onPick = { form = form.copy(cwd = it) },
                description = "Directory",
            )
            OutlinedTextField(
                value = form.prompt, onValueChange = { form = form.copy(prompt = it) },
                label = { Text("Prompt") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("{{range}}, {{head}}, {{since}}, {{count}}, {{ref}} and {{date}} are filled when it fires.") },
            )
            OutlinedTextField(
                value = form.cron, onValueChange = { form = form.copy(cron = it) },
                label = { Text("When (cron)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TgxType.mono125,
                supportingText = { Text(described ?: "…") },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.take(2).forEach { (label, cron) -> AssistChip(onClick = { form = form.copy(cron = cron) }, label = { Text(label) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.drop(2).forEach { (label, cron) -> AssistChip(onClick = { form = form.copy(cron = cron) }, label = { Text(label) }) }
            }
            Text("Only when", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("none" to "Always", "git-commits" to "New commits", "open-prs" to "Each open PR").forEach { (kind, label) ->
                    FilterChip(selected = form.gate == kind, onClick = { form = form.copy(gate = kind) }, label = { Text(label) })
                }
            }
            if (form.gate == "git-commits") {
                OutlinedTextField(
                    value = form.ref, onValueChange = { form = form.copy(ref = it) },
                    label = { Text("Ref to watch") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PickerChip(form.mode, offered, ::modeLabel, { form = form.copy(mode = it) }, "Permission mode")
                PickerChip(form.model, modelOptions(), SessionChoices::label, { form = form.copy(model = it) }, "Model",
                    chipLabel = { it.ifBlank { "Model" } })
                PickerChip(form.effort, effortOptions(), SessionChoices::label, { form = form.copy(effort = it) }, "Effort",
                    chipLabel = { it.ifBlank { "Effort" } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = form.enabled, onCheckedChange = { form = form.copy(enabled = it) })
            }
            val body = form.toBody(was)
            Button(
                onClick = { onSave(body) },
                enabled = form.prompt.isNotBlank() && form.cwd.isNotBlank() && form.cron.isNotBlank() && body.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.PrimaryButtonHeight),
            ) { Text(if (original == null) "Create" else if (body.isEmpty()) "No changes" else "Save") }
        }
    }
}
