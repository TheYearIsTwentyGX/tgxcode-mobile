package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.AgentDoneEvent
import com.twentygx.tgxcode.data.model.AssistantEvent
import com.twentygx.tgxcode.data.model.CompactEvent
import com.twentygx.tgxcode.data.model.SuggestionDecision
import com.twentygx.tgxcode.data.model.SuggestionEvent
import com.twentygx.tgxcode.data.model.SystemEvent
import com.twentygx.tgxcode.data.model.ThinkingEvent
import com.twentygx.tgxcode.data.model.ToolEvent
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.model.UserEvent
import com.twentygx.tgxcode.ui.markdown.MarkdownText
import com.twentygx.tgxcode.ui.tasks.TaskActions
import com.twentygx.tgxcode.ui.tasks.TaskCard
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.UserBubbleShape
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Everything an inline suggestion needs that the event itself cannot supply.
 *
 * [sessionId] most of all: a `suggestion` event knows only the tail it arrived on, so
 * the id every decision route takes has to come from the screen. This is
 * `taskSessionId` in `web/app.js:2039`, made explicit.
 */
data class SuggestionSlot(
    val sessionId: String,
    val decisions: Map<String, SuggestionDecision>,
    val acting: Set<String>,
    val actions: TaskActions,
)

/**
 * Doing one of your own earlier turns over, from its long-press menu.
 *
 * **Edit and resend** puts the text back to send as the next turn; nothing is undone.
 * **Edit and branch** sends the edit to a *copy* cut back to just before that turn.
 */
data class UserTurnActions(
    val onEditResend: (UserEvent) -> Unit,
    val onEditBranch: (UserEvent) -> Unit,
)

/** Dispatch. An unknown kind renders nothing, matching mobile.js. */
@Composable
fun EventItem(
    event: TranscriptEvent,
    expanded: Boolean,
    onToggleTool: (String) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: SuggestionSlot? = null,
    userActions: UserTurnActions? = null,
) {
    val padded = modifier
        .fillMaxWidth()
        .padding(horizontal = Metrics.ScreenPadding)
        .padding(bottom = 14.dp)

    when (event) {
        is UserEvent -> UserBubble(event, padded, userActions)
        is AssistantEvent -> {
            val text = event.text.orEmpty()
            MessageActions(
                markdown = text,
                modifier = padded,
                selectable = { MarkdownText(text) },
            ) {
                MarkdownText(text, Modifier.fillMaxWidth())
            }
        }
        is ThinkingEvent -> ThinkingBlock(event, padded)
        is ToolEvent -> ToolRow(event, expanded, { onToggleTool(event.id) }, padded)
        is SystemEvent -> SystemChip(event, padded)
        is AgentDoneEvent -> CentredNote(
            "Subagent ${event.status ?: "finished"}" +
                (event.summary?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
            padded,
        )
        // The event carries its own text, but the web client discards it and says
        // this instead — what matters is that it happened, not what was dropped.
        is CompactEvent -> CentredNote("Conversation compacted", padded)

        // The desktop lifts these out of the stream and draws them in a panel beside
        // the transcript; there is no beside on a phone, so they stay where they
        // happened. The Tasks tab is the collected view.
        //
        // Reuses the tool-expansion set: both are "a row the reader chose to open",
        // and one set means an offer cannot be open on one screen and shut on another.
        is SuggestionEvent -> suggestions?.let { slot ->
            TaskCard(
                task = event.toTask(slot.sessionId, slot.decisions[event.id]),
                expanded = expanded,
                busy = event.id in slot.acting,
                onToggle = { onToggleTool(event.id) },
                actions = slot.actions,
                modifier = padded,
            )
        } ?: Unit

        else -> Unit
    }
}

/**
 * A blue bubble, indented from the left, with one flat corner pointing at the
 * sender. Text is pre-wrapped; images are counted, never shown.
 */
@Composable
private fun UserBubble(
    event: UserEvent,
    modifier: Modifier = Modifier,
    actions: UserTurnActions? = null,
) {
    // An origin that is not `human` means an agent sent this, which the prefix says
    // — otherwise the transcript reads as if the user typed it.
    val text = event.displayText()
    // Only your own turns can be done over: a peer's message or an agent's prompt is
    // not a turn `fromUuid` will cut at, and resending it would put words in your mouth.
    val extra = if (actions != null && event.fromHuman) {
        listOf(
            MessageAction("Edit and resend") { actions.onEditResend(event) },
            MessageAction("Edit and branch") { actions.onEditBranch(event) },
        )
    } else {
        emptyList()
    }

    Row(modifier, horizontalArrangement = Arrangement_End) {
        MessageActions(
            markdown = text,
            modifier = Modifier.padding(start = 28.dp),
            extra = extra,
            selectable = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        ) {
            Column(
                Modifier
                    .background(tgx.blueCont, UserBubbleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (event.images.isNotEmpty()) {
                    Text(
                        "${event.images.size} image${if (event.images.size == 1) "" else "s"}",
                        style = TgxType.mono12,
                        color = tgx.textFaint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private val Arrangement_End = androidx.compose.foundation.layout.Arrangement.End

/** Italic, faint, behind a left rule. Deliberately not markdown. */
@Composable
private fun ThinkingBlock(event: ThinkingEvent, modifier: Modifier = Modifier) {
    val rule = tgx.outlineSoft
    Box(
        modifier
            .drawBehind {
                drawRect(rule, Offset.Zero, Size(2.dp.toPx(), size.height))
            }
            .padding(start = 10.dp),
    ) {
        Text(event.text.orEmpty(), style = TgxType.thinking, color = tgx.textFaint)
    }
}

/**
 * A system line. Red when it is an error.
 *
 * Rendered as plain text rather than through the markdown block renderer: the web
 * client uses its *inline* pass here, so a line beginning with `#` is a line
 * beginning with `#`, not an H1 in the middle of a conversation.
 */
@Composable
private fun SystemChip(event: SystemEvent, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(tgx.s1, RoundedCornerShape(Radius.Sm))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            event.text.orEmpty(),
            style = TgxType.mono125,
            color = if (event.isError) tgx.red else tgx.textMuted,
        )
    }
}

@Composable
private fun CentredNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tgx.textFaint,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/**
 * The single biggest departure from the desktop: a tool call is **one tappable
 * line**, not an inline block.
 *
 * The reason is concrete — on a 390px screen an expanded Bash result buries the
 * sentence you opened the phone to read.
 *
 * The row's three parts translate mobile.css's flexbox rules, and getting them
 * backwards is what causes the horizontal-scroll bug its comments describe:
 * the name is `flex: 0 1 auto` (sized by content, shrinks only when it must) and the
 * description is `flex: 1 1 0` (takes the remainder rather than competing for it).
 *
 * Expansion state lives *outside* the event list, in the ViewModel. That is a
 * deliberate improvement on the web client: `patchTool` there replaces the DOM node
 * wholesale, so an expanded tool collapses the instant its result arrives — exactly
 * the moment you wanted to read it. Here the result fills in underneath your finger.
 */
@Composable
private fun ToolRow(
    event: ToolEvent,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tgx
    val status = toolStatusChip(event)
    val description = remember(event.id, event.input) { toolDescription(event.input) }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.s1, RoundedCornerShape(Radius.Sm))
                .clickable(onClick = onToggle)
                .heightIn(min = Metrics.ToolRowHeight)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                toolLabel(event.name),
                style = TgxType.mono125,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = TgxType.mono125,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (event.isError) colors.red else colors.textFaint,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (expanded) {
            // Built only when open: a 1,800-event transcript must never format
            // 1,200 tool bodies nobody looks at.
            val body = remember(event.id, event.status, event.result) { toolBody(event) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(Radius.Sm))
                    .heightIn(max = 320.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                SelectionContainer {
                    Text(body, style = TgxType.mono12, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
