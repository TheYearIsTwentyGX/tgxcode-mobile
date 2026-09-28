package com.twentygx.tgxcode.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.model.TaskStatus
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.markdown.MarkdownText
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** What the buttons on a task card can do. */
data class TaskActions(
    val onStart: (SuggestedTask) -> Unit,
    val onDismiss: (SuggestedTask) -> Unit,
    val onUndo: (SuggestedTask) -> Unit,
    /** Open a session — the one that raised the offer, or the one a start created. */
    val onOpenSession: (String) -> Unit,
)

/**
 * One suggested follow-up, drawn the same way wherever it is met.
 *
 * `docs/api.md:824` promises that a `/api/suggestions` row and a `suggestion`
 * transcript event carry identical fields down to `cwd` — *"so a client can draw a row
 * and an event with one code path"*. This is that code path, and
 * [com.twentygx.tgxcode.data.model.SuggestionEvent.toTask] is how the event reaches it.
 *
 * Collapsed by default. The `why` line is one sentence and is what decides whether you
 * care; the prompt is a paragraph written for an agent, and is worth reading only once
 * you have. Starting is deliberately not offered until it is expanded — nobody should
 * run a prompt they have not read.
 */
@Composable
fun TaskCard(
    task: SuggestedTask,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    actions: TaskActions,
    modifier: Modifier = Modifier,
) {
    val colors = tgx
    val meta = listOfNotNull(
        task.place?.takeIf { it.isNotBlank() },
        ago(task.ts).takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.s2, RoundedCornerShape(Radius.Lg))
            .clickable(onClick = onToggle)
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                task.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                // An archived session's task is dimmed rather than hidden: dismissing
                // is already the gesture for "not this", and an outstanding task is the
                // loose end you most want to still find after filing a conversation.
                color = if (task.archived) colors.textMuted else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = TgxType.mono12,
                    color = colors.textFaint,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        task.why?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        StatusLine(task)

        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "First message",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                )
                MarkdownText(task.prompt, Modifier.fillMaxWidth())

                task.cwd?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = TgxType.mono12, color = colors.textFaint, maxLines = 1)
                }

                Buttons(task, busy, actions)
            }
        }

        if (!expanded) {
            Text(
                "Tap to read it",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textFaint,
            )
        }
    }
}

@Composable
private fun StatusLine(task: SuggestedTask) {
    val text = when (task.status) {
        TaskStatus.STARTED -> "Started"
        TaskStatus.DISMISSED -> "Dismissed"
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (task.status == TaskStatus.STARTED) tgx.green else tgx.textFaint,
    )
}

@Composable
private fun Buttons(task: SuggestedTask, busy: Boolean, actions: TaskActions) {
    when (task.status) {
        // `plan` is what a start uses, and the reason is worth saying on the button
        // itself: nobody has read this prompt as an instruction yet.
        TaskStatus.OPEN -> {
            AskButton(
                if (busy) "Starting…" else "Start it — in plan mode",
                { actions.onStart(task) },
                kind = AskButtonKind.Affirmative,
                enabled = !busy && task.prompt.isNotBlank(),
            )
            AskButton(
                "Dismiss",
                { actions.onDismiss(task) },
                kind = AskButtonKind.Destructive,
                enabled = !busy,
            )
        }

        TaskStatus.STARTED -> {
            task.startedId?.let { started ->
                AskButton("Open what it started", { actions.onOpenSession(started) })
            }
            AskButton("Offer it again", { actions.onUndo(task) }, enabled = !busy)
        }

        TaskStatus.DISMISSED ->
            AskButton("Undo", { actions.onUndo(task) }, enabled = !busy)
    }

    task.sessionId?.let { origin ->
        TextButton(onClick = { actions.onOpenSession(origin) }) {
            Text(
                "From “${task.session?.title ?: "the session that raised it"}”",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
