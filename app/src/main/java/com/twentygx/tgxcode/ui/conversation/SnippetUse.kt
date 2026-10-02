package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.Snippet
import com.twentygx.tgxcode.data.model.SnippetGroup
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.parseHex
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * `#rgb` / `#rrggbb` into a colour, or null — the same strict gate the bridge applies,
 * so a stored accent that is anything else simply draws no accent.
 */
internal fun hexColor(hex: String?): Color? = hex?.let(::parseHex)

/**
 * The pinned snippets as chips above the box, then a chip for the rest — the desktop's
 * pinned buttons and its snippets icon, in a row a thumb can scroll.
 */
@Composable
fun SnippetStrip(
    snippets: List<Snippet>,
    groups: List<SnippetGroup>,
    onUse: (Snippet) -> Unit,
    onMore: () -> Unit,
) {
    if (snippets.isEmpty()) return
    val accents = remember(groups) { groups.associate { it.id to hexColor(it.accent) } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snippets.filter { it.pinned }.forEach { s ->
            AssistChip(
                onClick = { onUse(s) },
                label = { Text(s.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                leadingIcon = s.groupId?.let { accents[it] }?.let { c ->
                    { Box(Modifier.size(8.dp).background(c, CircleShape)) }
                },
            )
        }
        AssistChip(onClick = onMore, label = { Text("Snippets…", style = MaterialTheme.typography.labelMedium) })
    }
}

/** Every snippet that applies here, grouped as the desktop's popover groups them. */
@Composable
fun SnippetPickerSheet(
    snippets: List<Snippet>,
    groups: List<SnippetGroup>,
    onUse: (Snippet) -> Unit,
    onDismiss: () -> Unit,
) {
    // A groupId naming a group that is not in the list draws ungrouped, and the field
    // is left alone: another bridge may be about to write that group.
    val known = groups.sortedWith(compareBy(nullsLast()) { it.order }).map { it.id }.toSet()
    val sections = groups.sortedWith(compareBy(nullsLast()) { it.order })
        .map { g -> g to snippets.filter { it.groupId == g.id } }
        .filter { it.second.isNotEmpty() } +
        listOf(null to snippets.filter { it.groupId == null || it.groupId !in known }).filter { it.second.isNotEmpty() }

    TgxSheet(title = "Snippets", onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            sections.forEach { (group, rows) ->
                Row(
                    Modifier.padding(start = Metrics.ScreenPadding, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    hexColor(group?.accent)?.let {
                        Box(Modifier.padding(end = 6.dp).size(8.dp).background(it, CircleShape))
                    }
                    Text(group?.name ?: "Ungrouped", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                }
                rows.forEach { s ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onUse(s) }
                            .heightIn(min = Metrics.TouchTarget)
                            .padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp),
                    ) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                "sends itself".takeIf { s.autoSubmit },
                                s.insert.takeIf { it != "overwrite" },
                                s.permissionMode?.let { modeLabel(it) },
                            ).joinToString(" · ").ifBlank { s.hint ?: s.body.lineSequence().firstOrNull().orEmpty() },
                            style = TgxType.mono12,
                            color = tgx.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The questions a snippet's `{{params}}` ask before it is used.
 *
 * Pre-filled from each default, which only pre-fills: a required param with a default
 * can be cleared, and must then be refilled before Use is allowed.
 *
 * @param onDone the answers, or null for cancelled.
 */
@Composable
fun SnippetFillDialog(snippet: Snippet, onDone: (Map<String, String>?) -> Unit) {
    val answers = remember(snippet.id) {
        mutableStateMapOf<String, String>().apply { snippet.params.forEach { put(it.name, it.default.orEmpty()) } }
    }
    val missing = snippet.params.any { it.required && answers[it.name].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(snippet.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                snippet.params.forEach { p ->
                    OutlinedTextField(
                        value = answers[p.name].orEmpty(),
                        onValueChange = { answers[p.name] = it },
                        label = { Text(p.displayLabel + if (p.required) " *" else "") },
                        placeholder = {
                            Text(
                                when (p.type) {
                                    "date" -> "YYYY-MM-DD"
                                    "time" -> "HH:MM"
                                    "datetime" -> "YYYY-MM-DD HH:MM"
                                    else -> ""
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (p.type) {
                                "integer" -> KeyboardType.Number
                                "decimal" -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(answers.toMap()) }, enabled = !missing) {
                Text(if (snippet.autoSubmit) "Send" else "Use")
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
