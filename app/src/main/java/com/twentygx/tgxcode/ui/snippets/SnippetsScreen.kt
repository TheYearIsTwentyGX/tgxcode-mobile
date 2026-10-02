package com.twentygx.tgxcode.ui.snippets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.Snippet
import com.twentygx.tgxcode.data.model.SnippetGroup
import com.twentygx.tgxcode.data.model.SnippetParam
import com.twentygx.tgxcode.data.model.SnippetsPayload
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.conversation.PickerChip
import com.twentygx.tgxcode.ui.conversation.hexColor
import com.twentygx.tgxcode.ui.conversation.modeLabel
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The editable part of a snippet, as plain values. */
data class SnippetForm(
    val title: String = "",
    val body: String = "",
    val hint: String = "",
    val groupId: String? = null,
    val params: List<SnippetParam> = emptyList(),
    val insert: String = "overwrite",
    val autoSubmit: Boolean = false,
    /** Null is inherit. */
    val permissionMode: String? = null,
    val pinned: Boolean = false,
) {
    companion object {
        fun of(s: Snippet) = SnippetForm(
            s.title, s.body, s.hint.orEmpty(), s.groupId, s.params, s.insert, s.autoSubmit, s.permissionMode, s.pinned,
        )
    }
}

/**
 * Everything for a new snippet; only what changed for an edit. `params` goes whole
 * when it goes at all — it replaces rather than merges on the bridge — and a mode is
 * sent only when it moved, because a remote caller restating `dontAsk` is refused.
 */
internal fun SnippetForm.toBody(was: SnippetForm?): JsonObject = buildJsonObject {
    fun changed(pick: (SnippetForm) -> Any?) = was == null || pick(was) != pick(this@toBody)
    if (changed { it.title }) put("title", title.trim())
    // Not trimmed: with append or cursor, the whitespace is part of what it means.
    if (changed { it.body }) put("body", body)
    if (changed { it.hint }) put("hint", JsonPrimitive(hint.trim().ifBlank { null }))
    if (changed { it.groupId }) put("groupId", JsonPrimitive(groupId))
    if (changed { it.params }) put("params", AppJson.encodeToJsonElement(ListSerializer(SnippetParam.serializer()), params))
    if (changed { it.insert }) put("insert", insert)
    if (changed { it.autoSubmit }) put("autoSubmit", autoSubmit)
    if (changed { it.permissionMode }) put("permissionMode", JsonPrimitive(permissionMode))
    if (changed { it.pinned }) put("pinned", pinned)
}

private val PARAM_NAME = Regex("^[A-Za-z_]\\w*$")
private val PARAM_TYPES = listOf("text", "integer", "decimal", "date", "time", "datetime")

/**
 * Canned messages and their groups — the desktop's snippets settings.
 *
 * Global, not per project: `projects` only hides a snippet where it does not apply,
 * which is set at the desk and kept as it is here.
 */
@Composable
fun SnippetsScreen(
    payload: SnippetsPayload,
    modes: List<String>,
    onSave: (id: String?, body: JsonObject, onSaved: () -> Unit) -> Unit,
    onDelete: (Snippet) -> Unit,
    onMove: (Snippet, Int) -> Unit,
    onSaveGroup: (id: String?, name: String, accent: String?) -> Unit,
    onDeleteGroup: (SnippetGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<SnippetGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }

    val groups = payload.groups.sortedWith(compareBy(nullsLast()) { it.order })
    val known = groups.map { it.id }.toSet()
    val sections = groups.map { it to payload.snippets.filter { s -> s.groupId == it.id } } +
        listOf(null to payload.snippets.filter { it.groupId == null || it.groupId !in known })

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding / 2), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${payload.snippets.size} snippets",
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textMuted,
                modifier = Modifier.weight(1f).padding(start = Metrics.ScreenPadding / 2),
            )
            TextButton(onClick = { creatingGroup = true }) { Text("New group") }
            TextButton(onClick = { creating = true }) { Text("New snippet") }
        }
        HorizontalDivider(color = tgx.outlineSoft)
        if (payload.snippets.isEmpty() && groups.isEmpty()) {
            EmptyState("No snippets", "Canned messages you send often.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                sections.forEach { (group, rows) ->
                    if (group == null && rows.isEmpty()) return@forEach
                    item(key = "g:" + (group?.id ?: "none")) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = Metrics.ScreenPadding, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            hexColor(group?.accent)?.let { Box(Modifier.padding(end = 6.dp).size(8.dp).background(it, CircleShape)) }
                            Text(
                                group?.name ?: "Ungrouped",
                                style = MaterialTheme.typography.labelMedium,
                                color = tgx.textMuted,
                                modifier = Modifier.weight(1f),
                            )
                            if (group != null) TextButton(onClick = { editingGroup = group }) { Text("Edit group") }
                        }
                    }
                    items(rows, key = { "s:" + it.id }) { s ->
                        val at = rows.indexOf(s)
                        Row(
                            Modifier.fillMaxWidth().padding(start = Metrics.ScreenPadding, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(
                                        "pinned".takeIf { s.pinned },
                                        "sends itself".takeIf { s.autoSubmit },
                                        s.insert.takeIf { it != "overwrite" },
                                        s.permissionMode?.let { modeLabel(it) },
                                        "${s.params.size} question${if (s.params.size == 1) "" else "s"}".takeIf { s.params.isNotEmpty() },
                                        "only in ${s.projects.size} place${if (s.projects.size == 1) "" else "s"}".takeIf { s.projects.isNotEmpty() },
                                    ).joinToString(" · ").ifBlank { s.body.lineSequence().first() },
                                    style = TgxType.mono12,
                                    color = tgx.textFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onMove(s, -1) }, enabled = at > 0) { Text("↑") }
                            TextButton(onClick = { onMove(s, 1) }, enabled = at < rows.lastIndex) { Text("↓") }
                            TextButton(onClick = { editing = s }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val original = editing
        SnippetEditor(
            original = original,
            groups = groups,
            modes = modes,
            onSave = { body -> onSave(original?.id, body) { creating = false; editing = null } },
            onDelete = original?.let { s -> { onDelete(s); editing = null } },
            onDismiss = { creating = false; editing = null },
        )
    }
    if (creatingGroup || editingGroup != null) {
        GroupDialog(
            group = editingGroup,
            onSave = { name, accent ->
                onSaveGroup(editingGroup?.id, name, accent)
                creatingGroup = false
                editingGroup = null
            },
            onDelete = editingGroup?.let { g -> { onDeleteGroup(g); editingGroup = null } },
            onDismiss = { creatingGroup = false; editingGroup = null },
        )
    }
}

@Composable
private fun SnippetEditor(
    original: Snippet?,
    groups: List<SnippetGroup>,
    modes: List<String>,
    onSave: (JsonObject) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val was = remember(original?.id) { original?.let { SnippetForm.of(it) } }
    var form by remember(original?.id) { mutableStateOf(was ?: SnippetForm()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val modeOptions = (listOf("") + modes + listOfNotNull(form.permissionMode)).distinct()
    val badParam = form.params.any { !PARAM_NAME.matches(it.name) } ||
        form.params.map { it.name }.toSet().size != form.params.size

    TgxSheet(title = if (original == null) "New snippet" else "Edit snippet", onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Metrics.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(form.title, { form = form.copy(title = it.take(200)) }, label = { Text("Title") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.body, { form = form.copy(body = it) }, label = { Text("Message") }, minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("{{name}} asks for a value — declare it as a question below.") })
            OutlinedTextField(form.hint, { form = form.copy(hint = it) }, label = { Text("Hint") },
                placeholder = { Text("First line of the message") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Group", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                PickerChip(
                    value = form.groupId.orEmpty(),
                    options = listOf("") + groups.map { it.id } + listOfNotNull(form.groupId),
                    label = { id -> groups.firstOrNull { it.id == id }?.name ?: if (id.isBlank()) "Ungrouped" else "Ungrouped (missing group)" },
                    onPick = { form = form.copy(groupId = it.ifBlank { null }) },
                    description = "Group",
                )
            }
            Text("Where it goes", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("overwrite" to "Replace the box", "append" to "Add to the end", "cursor" to "At the cursor").forEach { (v, l) ->
                    FilterChip(selected = form.insert == v, onClick = { form = form.copy(insert = v) }, label = { Text(l) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send it straight away", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = form.autoSubmit, onCheckedChange = { form = form.copy(autoSubmit = it) })
            }
            if (form.autoSubmit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("In mode", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                    PickerChip(
                        value = form.permissionMode.orEmpty(),
                        options = modeOptions,
                        label = { if (it.isBlank()) "Keep the session's" else modeLabel(it) },
                        onPick = { form = form.copy(permissionMode = it.ifBlank { null }) },
                        description = "Permission mode",
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pin above the composer", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = form.pinned, onCheckedChange = { form = form.copy(pinned = it) })
            }

            Text("Questions", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
            form.params.forEachIndexed { i, p ->
                ParamEditor(
                    param = p,
                    onChange = { next -> form = form.copy(params = form.params.toMutableList().also { it[i] = next }) },
                    onRemove = { form = form.copy(params = form.params.filterIndexed { j, _ -> j != i }) },
                )
            }
            TextButton(onClick = {
                form = form.copy(params = form.params + SnippetParam(name = "value${form.params.size + 1}"))
            }, enabled = form.params.size < 20) { Text("Add a question") }
            original?.undeclared?.takeIf { it.isNotEmpty() }?.let {
                Text("Not asked for: ${it.joinToString { n -> "{{$n}}" }} — these are sent as written.",
                    style = TgxType.mono12, color = tgx.textFaint)
            }

            val body = form.toBody(was)
            Button(
                onClick = { onSave(body) },
                enabled = form.title.isNotBlank() && form.body.isNotBlank() && !badParam && body.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.PrimaryButtonHeight),
            ) { Text(if (original == null) "Create" else if (body.isEmpty()) "No changes" else "Save") }
            if (badParam) Text("Each question needs a unique name of letters, digits and _.", style = TgxType.mono12, color = tgx.red)
            onDelete?.let { TextButton(onClick = { confirmDelete = true }) { Text("Delete snippet", color = tgx.red) } }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${original?.title}”?") },
            text = { Text("A shipped snippet, once deleted, does not come back.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("Delete", color = tgx.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun ParamEditor(param: SnippetParam, onChange: (SnippetParam) -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(tgx.s2, MaterialTheme.shapes.medium).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(param.name, { onChange(param.copy(name = it.trim())) }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.weight(1f), textStyle = TgxType.mono125)
            OutlinedTextField(param.label.orEmpty(), { onChange(param.copy(label = it.ifBlank { null })) }, label = { Text("Label") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PickerChip(param.type, PARAM_TYPES, { it }, { onChange(param.copy(type = it)) }, "Type")
            Checkbox(checked = param.required, onCheckedChange = { onChange(param.copy(required = it)) })
            Text("Required", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove", color = tgx.red) }
        }
        OutlinedTextField(param.default.orEmpty(), { onChange(param.copy(default = it.ifEmpty { null })) },
            label = { Text("Default") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GroupDialog(
    group: SnippetGroup?,
    onSave: (String, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var accent by remember { mutableStateOf(group?.accent.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (group == null) "New group" else "Edit group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(200) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(accent, { accent = it.trim() }, label = { Text("Accent") },
                    placeholder = { Text("#d0bcff") }, singleLine = true,
                    supportingText = { Text(if (accent.isBlank() || hexColor(accent) != null) "Optional" else "Not a #rgb or #rrggbb colour") })
                onDelete?.let {
                    TextButton(onClick = it) { Text("Delete group — its snippets stay, ungrouped", color = tgx.red) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, accent.ifBlank { null }) },
                enabled = name.isNotBlank() && (accent.isBlank() || hexColor(accent) != null)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
