package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.model.SlashCommandInfo
import com.twentygx.tgxcode.data.model.Snippet
import com.twentygx.tgxcode.data.model.SnippetGroup
import com.twentygx.tgxcode.data.repo.fillSnippet
import com.twentygx.tgxcode.data.repo.insertSnippet
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Permission modes, as names rather than explanations.
 *
 * The web client appends a gloss ("acceptEdits — edits go through"), which is useful
 * the first time and noise every time after. These are the same six modes a user
 * picks between constantly; once you know them, the sentence is just something to
 * read past.
 */
private val MODE_LABELS = mapOf(
    "auto" to "Auto",
    "acceptEdits" to "Accept Edits",
    "plan" to "Plan",
    "manual" to "Manual",
    "dontAsk" to "Don't Ask",
    "bypassPermissions" to "Bypass Permissions",
)

/**
 * A mode this build has not seen still has to read as a name, because
 * `health.permissionModes` is read from the bridge rather than hardcoded — a mode
 * added upstream should not surface as raw camelCase.
 */
fun modeLabel(mode: String): String = MODE_LABELS[mode] ?: humanize(mode)

private fun humanize(raw: String): String =
    raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/**
 * A compact dropdown: the current value as a label, the options in a menu.
 *
 * Three of these sit side by side on a 390dp phone, so each is sized by its label
 * and nothing else — `Accept Edits · opus · high` fits, with Stop beside it.
 */
@Composable
fun PickerChip(
    value: String,
    options: List<String>,
    label: (String) -> String,
    onPick: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    /**
     * What the chip itself says, when that differs from the option's name. Two chips
     * both reading `inherit` side by side say nothing about which is which, so an
     * inheriting model chip reads `Model` and the menu still offers `inherit`.
     */
    chipLabel: (String) -> String = label,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 32.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) {
            Text(
                chipLabel(value),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = tgx.textFaint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label(option),
                            color = if (option == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/**
 * What the composer needs beyond the text: the three pickers, and the things that can
 * be pending on a send — a held settings change, an armed branch, a queue.
 */
data class ComposerControls(
    val model: String = "",
    val onModelChanged: (String) -> Unit = {},
    val effort: String = "",
    val onEffortChanged: (String) -> Unit = {},
    /** The armed Edit and branch's banner, or null. */
    val branchBanner: String? = null,
    val onCancelBranch: () -> Unit = {},
    val onOpenQueue: () -> Unit = {},
    /** Snippets that apply in this session's directory, in the bridge's order. */
    val snippets: List<Snippet> = emptyList(),
    val snippetGroups: List<SnippetGroup> = emptyList(),
    /**
     * Send text that did not come out of the box — a snippet that sends itself — leaving
     * whatever is half-typed there alone. `mode` null keeps the session's.
     */
    val onSendCanned: (text: String, mode: String?) -> Unit = { _, _ -> },
    /** Completion for a leading `/`; asked for once, on the first `/`. */
    val slashCommands: List<SlashCommandInfo> = emptyList(),
    val onWantSlashCommands: () -> Unit = {},
    /** Long-press Send: deliver at a time instead. Null where there is nothing to schedule into. */
    val onScheduleSend: ((at: Long, mode: String) -> Unit)? = null,
)

/**
 * What the slash menu offers for [text]: only while the box is a bare `/word` with no
 * space yet, filtered by prefix, at most eight — a phone has room for a short list and
 * the rest is one more letter away.
 */
internal fun slashMatches(text: String, commands: List<SlashCommandInfo>): List<SlashCommandInfo> {
    if (!text.startsWith("/") || text.contains(' ') || text.contains('\n')) return emptyList()
    val typed = text.removePrefix("/").lowercase()
    return commands.filter { it.name.lowercase().startsWith(typed) }
        .sortedBy { it.name.length }
        .take(8)
        .takeUnless { it.size == 1 && it[0].name.equals(typed, ignoreCase = true) }
        .orEmpty()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    text: String,
    onTextChanged: (String) -> Unit,
    mode: String,
    modes: List<String>,
    onModeChanged: (String) -> Unit,
    runner: RunnerStatus?,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    controls: ComposerControls = ComposerControls(),
) {
    // The box keeps its own selection, so a `cursor` snippet lands at the caret; the
    // text itself still belongs to the ViewModel and is synced back in when it moves
    // from outside (a restore, an edit-and-resend, a send clearing it).
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (text != field.text) field = TextFieldValue(text, TextRange(text.length))
    }
    var pickingSnippets by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf<Snippet?>(null) }
    var scheduling by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    /**
     * Put a snippet's text where it says, and send it if it says to — `popover.js`'s
     * `applySnippet`. An overwriting snippet that sends itself never touches the box:
     * the text goes straight out, which is why pressing one has never cost anybody a
     * half-typed message.
     */
    fun apply(snippet: Snippet, answers: Map<String, String>) {
        val filled = fillSnippet(snippet.body, snippet.params, answers)
        if (snippet.autoSubmit && snippet.insert == "overwrite") {
            controls.onSendCanned(filled, snippet.permissionMode)
            return
        }
        val sel = field.selection
        val (next, caret) = insertSnippet(field.text, filled, snippet.insert, sel.min until sel.max)
        field = TextFieldValue(next, TextRange(caret))
        onTextChanged(next)
        if (snippet.autoSubmit) {
            snippet.permissionMode?.let(onModeChanged)
            onSend()
        }
    }

    fun use(snippet: Snippet) {
        pickingSnippets = false
        if (snippet.params.isEmpty()) apply(snippet, emptyMap()) else filling = snippet
    }

    if (text.startsWith("/")) LaunchedEffect(Unit) { controls.onWantSlashCommands() }
    val slash = slashMatches(field.text, controls.slashCommands)

    val queued = runner?.queue?.size?.takeIf { it > 0 } ?: runner?.queued?.takeIf { it > 0 }
    val held = runner?.pendingSettings
    val note = listOfNotNull(
        runner?.activity?.takeIf { it.isNotBlank() },
        runner?.stalled?.takeIf { it }?.let { "stalled" },
        held?.let {
            "held until background work ends: " +
                "${SessionChoices.label(it.model.orEmpty())} · ${SessionChoices.label(it.effort.orEmpty())}"
        },
    ).joinToString(" · ")

    Column(
        modifier
            .fillMaxWidth()
            .background(tgx.s1)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        controls.branchBanner?.let { banner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tgx.blueTint, androidx.compose.foundation.shape.RoundedCornerShape(Radius.Sm))
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    banner,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
                TextButton(onClick = controls.onCancelBranch) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            PickerChip(
                value = mode,
                options = modes,
                label = ::modeLabel,
                onPick = onModeChanged,
                description = "Permission mode",
            )
            PickerChip(
                value = controls.model,
                options = SessionChoices.MODELS,
                label = SessionChoices::label,
                onPick = controls.onModelChanged,
                description = "Model",
                chipLabel = { it.ifBlank { "Model" } },
            )
            PickerChip(
                value = controls.effort,
                options = SessionChoices.EFFORTS,
                label = SessionChoices::label,
                onPick = controls.onEffortChanged,
                description = "Effort",
                chipLabel = { it.ifBlank { "Effort" } },
            )

            Box(Modifier.weight(1f))

            if (queued != null) {
                TextButton(
                    onClick = controls.onOpenQueue,
                    modifier = Modifier.heightIn(min = 32.dp),
                ) { Text("$queued queued", style = MaterialTheme.typography.labelMedium) }
            }

            // Only while there is something to stop.
            if (runner?.isWorking == true) {
                OutlinedButton(
                    onClick = onStop,
                    shape = Pill,
                    modifier = Modifier.heightIn(min = 32.dp),
                ) {
                    // A stalled turn is stopped hard (see AppViewModel.stop), but the
                    // word stays the same: what the person wants is the same.
                    Text("Stop", color = tgx.red, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = if (runner?.stalled == true) tgx.yellow else tgx.textFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        SnippetStrip(
            snippets = controls.snippets,
            groups = controls.snippetGroups,
            onUse = ::use,
            onMore = { pickingSnippets = true },
        )

        if (slash.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(tgx.s2, androidx.compose.foundation.shape.RoundedCornerShape(Radius.Sm)),
            ) {
                slash.forEach { cmd ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.Sm))
                            .combinedClickable(onClick = {
                                val next = "/${cmd.name} "
                                field = TextFieldValue(next, TextRange(next.length))
                                onTextChanged(next)
                            })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "/${cmd.name}" + (cmd.argumentHint?.let { " $it" } ?: ""),
                            style = TgxType.mono125,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        cmd.description?.let {
                            Text(it, style = TgxType.mono12, color = tgx.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != text) onTextChanged(it.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Metrics.TouchTarget, max = 200.dp),
                placeholder = { Text(if (controls.branchBanner != null) "Edited message" else "Message") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.Lg),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            // A Box rather than an IconButton, for the long press: Send later lives
            // there, as on the desktop's send menu, out of the way of the plain tap.
            val canSend = text.isNotBlank() && !sending
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(Metrics.TouchTarget)
                    .clip(CircleShape)
                    .background(if (canSend) tgx.blueDeep else tgx.s4)
                    .combinedClickable(
                        enabled = canSend,
                        onClick = onSend,
                        onLongClick = controls.onScheduleSend?.let {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scheduling = true
                            }
                        },
                        onLongClickLabel = "Send later",
                    ),
                contentAlignment = Alignment.Center,
            ) { Text("↑", color = if (canSend) Color.White else tgx.textFaint) }
        }
    }

    if (pickingSnippets) {
        SnippetPickerSheet(
            snippets = controls.snippets,
            groups = controls.snippetGroups,
            onUse = ::use,
            onDismiss = { pickingSnippets = false },
        )
    }
    filling?.let { snippet ->
        SnippetFillDialog(snippet) { answers ->
            filling = null
            if (answers != null) apply(snippet, answers)
        }
    }
    if (scheduling) {
        ScheduleSendDialog(mode = mode, modes = modes) { picked ->
            scheduling = false
            if (picked != null) controls.onScheduleSend?.invoke(picked.first, picked.second)
        }
    }
}
