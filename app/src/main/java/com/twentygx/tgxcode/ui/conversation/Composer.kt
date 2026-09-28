package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.RunnerStatus
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
) {
    var modeMenuOpen by remember { mutableStateOf(false) }

    val note = listOfNotNull(
        runner?.activity?.takeIf { it.isNotBlank() },
        runner?.queued?.takeIf { it > 0 }?.let { "$it queued" },
    ).joinToString(" · ")

    Column(
        modifier
            .fillMaxWidth()
            .background(tgx.s1)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(
                    onClick = { modeMenuOpen = true },
                    modifier = Modifier.heightIn(min = 32.dp),
                ) {
                    Text(
                        modeLabel(mode),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                    modes.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(modeLabel(option)) },
                            onClick = {
                                modeMenuOpen = false
                                onModeChanged(option)
                            },
                        )
                    }
                }
            }

            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )

            // Only while there is something to stop.
            if (runner?.isWorking == true) {
                OutlinedButton(
                    onClick = onStop,
                    shape = Pill,
                    modifier = Modifier.heightIn(min = 32.dp),
                ) { Text("Stop", color = tgx.red, style = MaterialTheme.typography.labelMedium) }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Metrics.TouchTarget, max = 200.dp),
                placeholder = { Text("Message") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.Lg),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !sending,
                modifier = Modifier.padding(start = 8.dp).size(Metrics.TouchTarget),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = tgx.blueDeep,
                    contentColor = Color.White,
                    disabledContainerColor = tgx.s4,
                    disabledContentColor = tgx.textFaint,
                ),
            ) { Text("↑") }
        }
    }
}
