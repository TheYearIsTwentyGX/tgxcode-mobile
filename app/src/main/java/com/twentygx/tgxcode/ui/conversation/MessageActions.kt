package com.twentygx.tgxcode.ui.conversation

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.ui.markdown.markdownToPlainText
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.tgx
import kotlinx.coroutines.launch

/**
 * A long press on a message opens a menu with two items: **Copy message…** and
 * **Select text**.
 *
 * This takes over the gesture a `SelectionContainer` used to own, and that is on
 * purpose. Selecting a whole reply with the handles was the only way to copy it, and
 * on a long reply that means dragging a handle across several screens. Partial
 * selection still exists: "Select text" opens the message in a sheet where a long
 * press selects as it did before.
 *
 * No `onClick`. A tap on a message does nothing, and it has to keep doing nothing: no
 * ripple over the whole reply, and a link inside it gets the tap.
 *
 * @param markdown what gets copied. When it is blank, as for an image-only turn, there
 *   is nothing to copy and the row has no menu, matching the desktop's `copyButton`.
 * @param selectable what the Select text sheet shows. For a user turn this is the text
 *   alone, without the bubble around it.
 * @param extra items after the two above — Edit and resend, Edit and branch on a turn
 *   of your own.
 */
@Composable
fun MessageActions(
    markdown: String,
    modifier: Modifier = Modifier,
    extra: List<MessageAction> = emptyList(),
    selectable: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (markdown.isBlank()) {
        Box(modifier) { content() }
        return
    }

    val haptics = LocalHapticFeedback.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var askFormat by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }

    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuAt = it
                    },
                )
            }
            .semantics {
                onLongClick(label = "Message options") {
                    menuAt = Offset.Zero
                    true
                }
            },
    ) {
        content()

        // A zero-size anchor at the finger, so the menu opens where the press was
        // rather than under a message that may be taller than the screen.
        val at = menuAt ?: Offset.Zero
        Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }) {
            DropdownMenu(expanded = menuAt != null, onDismissRequest = { menuAt = null }) {
                DropdownMenuItem(
                    text = { Text("Copy message…") },
                    onClick = { menuAt = null; askFormat = true },
                )
                DropdownMenuItem(
                    text = { Text("Select text") },
                    onClick = { menuAt = null; selecting = true },
                )
                extra.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = { menuAt = null; action.onClick() },
                    )
                }
            }
        }
    }

    if (askFormat) CopyFormatDialog(markdown, onDone = { askFormat = false })
    if (selecting) SelectTextSheet(onDismiss = { selecting = false }, content = selectable)
}

/** One more line in a message's long-press menu. */
data class MessageAction(val label: String, val onClick: () -> Unit)

/**
 * With formatting: the markdown exactly as it was written, for anywhere that renders
 * it (another prompt, an issue, a PR). Without formatting: [markdownToPlainText], for
 * anywhere that would show the asterisks as asterisks.
 */
@Composable
private fun CopyFormatDialog(markdown: String, onDone: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Message", text)))
            // From 13 the system shows its own confirmation, and a second one on top of
            // it is noise. Before 13 nothing does.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        onDone()
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Copy message") },
        text = { Text("Keep the Markdown, or copy it as plain text?") },
        confirmButton = {
            TextButton(onClick = { copy(markdown) }) { Text("With formatting") }
        },
        dismissButton = {
            TextButton(onClick = { copy(markdownToPlainText(markdown)) }) { Text("Without formatting") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectTextSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Skip the half-height stop. The point of the sheet is to read and drag across
        // the whole message, and half a screen would clip most replies.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.ScreenPadding)
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Long-press to select",
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textFaint,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            SelectionContainer(Modifier.fillMaxWidth()) { content() }
        }
    }
}
