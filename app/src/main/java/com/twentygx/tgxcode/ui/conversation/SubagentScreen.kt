package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.repo.AgentView
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * One subagent's transcript, read-only — the desktop's subagent pane.
 *
 * Read-only because there is nothing to say to a subagent: it answers to the session
 * that spawned it, not to you. It follows live while it works (`agent-tail`), and a
 * subagent that spawned its own renders those as links too, which open here in turn.
 *
 * Follows the end while new lines arrive, the plain way: a subagent transcript is short
 * and read top-down after the fact far more often than watched, so it does not need the
 * conversation's pin-and-release machinery.
 */
@Composable
fun SubagentScreen(
    view: AgentView?,
    expandedTools: Set<String>,
    /** The "Expand thinking" setting: how a thinking block opens until it is tapped. */
    thinkingExpanded: Boolean = true,
    onToggleTool: (String) -> Unit,
    onOpenAgent: (toolUseId: String, title: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (view == null || (view.loading && view.log.events.isEmpty())) {
        EmptyState(if (view == null) "Nothing open" else "Loading the subagent…", modifier = modifier)
        return
    }
    val events = view.log.events
    val listState = rememberLazyListState()
    LaunchedEffect(view.toolUseId) {
        if (events.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier.fillMaxSize()) {
        view.agentType?.let {
            Text(
                it,
                style = TgxType.mono12,
                color = tgx.textMuted,
                modifier = Modifier.padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp),
            )
            HorizontalDivider(color = tgx.outlineSoft)
        }
        if (events.isEmpty()) {
            EmptyState(
                "Nothing written yet",
                detail = "A subagent that has just started has no transcript for a moment.",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
        ) {
            items(
                count = events.size,
                key = { events[it].id },
                contentType = { events[it]::class.simpleName },
            ) { index ->
                val event = events[index]
                EventItem(
                    event = event,
                    expanded = event.id in expandedTools,
                    thinkingExpanded = thinkingExpanded,
                    onToggleTool = onToggleTool,
                    modifier = if (index == 0) Modifier.padding(top = 12.dp) else Modifier,
                    onOpenAgent = onOpenAgent,
                )
            }
        }
    }
}
