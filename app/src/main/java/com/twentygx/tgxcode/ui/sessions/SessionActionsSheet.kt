package com.twentygx.tgxcode.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.tgx

/** What a long press on a session row can do. */
data class SessionSheetActions(
    val onPin: (SessionSummary, Boolean) -> Unit,
    val onArchive: (SessionSummary, Boolean) -> Unit,
    val onRename: (SessionSummary, String) -> Unit,
    val onDelete: (SessionSummary) -> Unit,
    val onPrs: (SessionSummary) -> Unit,
)

/**
 * The desktop rail's card menu, for a thumb: pin, archive, rename, delete — and the
 * pull requests, which used to be the whole of the long press.
 *
 * Every item closes the sheet before acting, so a write that answers a moment later
 * never lands under a sheet that is still showing the old state.
 *
 * Delete asks first and the rest do not. A transcript cannot be put back; a pin can be
 * taken off again.
 */
@Composable
fun SessionActionsSheet(
    session: SessionSummary,
    actions: SessionSheetActions,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (renaming) {
        RenameDialog(
            current = if (session.titleSource == "user") session.title.orEmpty() else "",
            placeholder = session.displayTitle,
            onDone = { title ->
                renaming = false
                if (title != null) actions.onRename(session, title)
                onDismiss()
            },
        )
        return
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this session?") },
            text = {
                Text("“${session.displayTitle}” and its transcript are deleted for good. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    actions.onDelete(session)
                    onDismiss()
                }) { Text("Delete", color = tgx.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
            },
        )
        return
    }

    TgxSheet(title = session.displayTitle, subtitle = session.place, onDismiss = onDismiss) {
        SheetItem(if (session.pinned) "Unpin" else "Pin to the top") {
            onDismiss()
            actions.onPin(session, !session.pinned)
        }
        SheetItem(if (session.archived) "Unarchive" else "Archive") {
            onDismiss()
            actions.onArchive(session, !session.archived)
        }
        SheetItem("Rename…") { renaming = true }
        if (session.hasPrs) {
            SheetItem("Pull requests") {
                onDismiss()
                actions.onPrs(session)
            }
        }
        SheetItem("Delete…", color = tgx.red) { confirmingDelete = true }
    }
}

@Composable
private fun SheetItem(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.TouchTarget + 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 14.dp),
    )
}

/**
 * @param current the name given here, if any — not the transcript's title, which is not
 *   a name the user chose and should not be handed back to edit as though it were.
 * @param onDone null for cancelled. A blank name is a real answer: it clears the name,
 *   and the session shows its transcript's title again.
 */
@Composable
private fun RenameDialog(current: String, placeholder: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(200) },
                singleLine = true,
                placeholder = { Text(placeholder) },
                supportingText = { Text("Leave it empty to go back to the transcript's title.") },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
