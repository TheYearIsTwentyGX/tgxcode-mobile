package com.twentygx.tgxcode.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.model.TaskStatus
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.theme.Metrics

/**
 * Suggested follow-ups: work an agent noticed but was not asked to do.
 *
 * The desktop shows these in a panel beside the conversation that raised them, and
 * in a column on its task board. Neither shape fits a phone, so this is the one place
 * they are collected — and the conversation still shows its own inline, which is where
 * you meet an offer while it is being made rather than later.
 *
 * The chips exist for one reason: **undo needs somewhere to happen.** A dismissed task
 * is gone from the default view by definition, so without a way to look at the
 * dismissed ones, "dismiss" would be irreversible in practice however reversible the
 * bridge makes it.
 */
@Composable
fun TasksScreen(
    tasks: List<SuggestedTask>,
    filter: String,
    acting: Set<String>,
    onFilterChanged: (String) -> Unit,
    actions: TaskActions,
    modifier: Modifier = Modifier,
) {
    // Kept here rather than in the ViewModel: which card is open is about this screen
    // being looked at, and should not survive it.
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskStatus.ALL.forEach { status ->
                FilterChip(
                    selected = filter == status,
                    onClick = { onFilterChanged(status) },
                    label = { Text(label(status)) },
                )
            }
        }

        if (tasks.isEmpty()) {
            EmptyState(
                headline = when (filter) {
                    TaskStatus.STARTED -> "Nothing started from here yet."
                    TaskStatus.DISMISSED -> "Nothing dismissed."
                    else -> "No suggested tasks."
                },
                detail = if (filter == TaskStatus.OPEN) {
                    "An agent files one with the suggest_session tool when it " +
                        "notices work it was not asked to do."
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                horizontal = Metrics.ScreenPadding,
                vertical = 6.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    expanded = expanded == task.id,
                    busy = task.id in acting,
                    onToggle = { expanded = if (expanded == task.id) null else task.id },
                    actions = actions,
                )
            }
        }
    }
}

private fun label(status: String): String = when (status) {
    TaskStatus.OPEN -> "Open"
    TaskStatus.STARTED -> "Started"
    TaskStatus.DISMISSED -> "Dismissed"
    else -> status
}
