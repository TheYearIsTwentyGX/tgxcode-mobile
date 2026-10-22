package com.twentygx.tgxcode.ui.orchestrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.BriefDecision
import com.twentygx.tgxcode.data.model.Decision
import com.twentygx.tgxcode.data.model.InboxItem
import com.twentygx.tgxcode.data.model.OpenTask
import com.twentygx.tgxcode.data.model.OrchestratorPayload
import com.twentygx.tgxcode.data.model.OrchestratorWorker
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.QuotaWindow
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.common.prLookOf
import com.twentygx.tgxcode.ui.common.prTint
import com.twentygx.tgxcode.ui.conversation.modeLabel
import com.twentygx.tgxcode.ui.markdown.MarkdownText
import com.twentygx.tgxcode.ui.needs.SectionHeader
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** Everything the overview can ask for. One object, so the call site stays readable. */
data class OrchestratorActions(
    val onOpenSession: (String) -> Unit,
    val onShowConversation: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onResume: () -> Unit,
    val onAnswerDecision: (BriefDecision, String) -> Unit,
    val onDismiss: (InboxItem) -> Unit,
    val onWithdraw: (OpenTask) -> Unit,
    /** A worker's ask, answered on the worker as any ask is; the orchestrator is told. */
    val onAnswerWorker: (workerId: String, PermissionRequestBody) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

/**
 * The orchestrator's page: what it is for, what it needs from you, and how its workers
 * are getting on — `web/orchestrator-page.js` in one column.
 *
 * The desktop draws it beside the conversation; there is no beside on a phone, so the
 * two are tabs of one screen and this is the one an orchestrator opens on. The order is
 * the desktop's — head, Needs you, Tasks from you, Brief, Workers, Merge order,
 * Activity — because it is the order of how soon each is worth reading.
 *
 * A worker opens its own conversation rather than the desktop's drawer. The drawer's
 * "So far" digest is `POST …/orchestrator/read`, which the bridge refuses to a remote
 * caller, and the rest of it is the worker's own page.
 *
 * @param orchState the orchestrator's runner state, for the stuck rule.
 * @param orchAsk an ask the orchestrator itself is waiting on.
 * @param sessions the session list, for each worker's runner activity and mode.
 */
@Composable
fun OrchestratorScreen(
    payload: OrchestratorPayload?,
    orchState: String?,
    orchAsk: Ask?,
    sessions: List<SessionSummary>,
    prs: PrsPayload?,
    quota: List<QuotaWindow>,
    busy: Set<String>,
    nowMillis: Long,
    actions: OrchestratorActions,
    modifier: Modifier = Modifier,
) {
    if (payload == null) {
        EmptyState("Loading the orchestrator…", modifier = modifier)
        return
    }
    if (!payload.enabled) {
        EmptyState(
            "Not an orchestrator",
            detail = "This session is not orchestrating anything any more.",
            modifier = modifier,
        )
        return
    }

    val byId = remember(sessions) { sessions.associateBy { it.sessionId } }
    val needs = remember(payload, orchState, orchAsk, nowMillis) { needsYou(payload, orchState, orchAsk, nowMillis) }
    val order = remember(payload) { mergeOrder(payload) }
    var finishedOpen by rememberSaveable(payload.orchestratorId) { mutableStateOf(false) }
    var activityOpen by rememberSaveable(payload.orchestratorId) { mutableStateOf(false) }
    var briefOpen by rememberSaveable(payload.orchestratorId) { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize()) {
        item(key = "head") {
            Head(payload, quota, actions, nowMillis)
        }

        if (needs.isNotEmpty()) {
            item(key = "needs-h") { SectionHeader("Needs you", trailing = needsCount(needs).toString()) }
            items(needs, key = { "need-${it.key}" }) { need ->
                NeedCard(need, payload, busy, nowMillis, actions, Modifier.cardPadding())
            }
        }

        if (payload.openTasks.isNotEmpty()) {
            item(key = "tasks-h") { SectionHeader("Tasks from you", trailing = "${payload.openTasks.size} not started") }
            items(payload.openTasks, key = { "task-${it.id}" }) { task ->
                TaskCard(task, task.id in busy, nowMillis, actions, Modifier.cardPadding())
            }
        }

        payload.summary?.text?.takeIf { it.isNotBlank() }?.let { text ->
            item(key = "brief-h") {
                SectionHeader("Brief", trailing = "updated ${agoWords(payload.summary.at, nowMillis)}")
            }
            item(key = "brief") {
                Column(Modifier.cardPadding().fillMaxWidth().background(tgx.s2, RoundedCornerShape(Radius.Lg)).padding(Metrics.CardPadding)) {
                    val long = text.length > 280
                    MarkdownText(if (long && !briefOpen) clip(text, 280) else text, Modifier.fillMaxWidth())
                    if (long) {
                        TextButton(onClick = { briefOpen = !briefOpen }) { Text(if (briefOpen) "Show less" else "Show all") }
                    }
                }
            }
        }

        workers(payload, byId, prs, nowMillis, finishedOpen, { finishedOpen = !finishedOpen }, actions)

        if (order.rows.isNotEmpty()) {
            item(key = "merge-h") {
                SectionHeader(
                    "Merge order",
                    trailing = if (order.fromBrief) "from the brief" else "in the order they started",
                )
            }
            items(order.rows, key = { "merge-${it.repo}-${it.number}" }) { row ->
                MergeRowItem(row, actions)
            }
            if (order.rest.isNotEmpty()) {
                item(key = "merge-rest") {
                    val names = order.rest.map { it.title ?: "untitled worker" }
                    Text(
                        if (names.size == 1) "Then ${names[0]}, once it opens a PR."
                        else "Then ${names.size} more as they open PRs: ${names.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tgx.textMuted,
                        modifier = Modifier.padding(horizontal = Metrics.ScreenPadding, vertical = 4.dp),
                    )
                }
            }
        }

        item(key = "activity-h") {
            Fold(
                label = "Activity",
                count = if (payload.unread > 0) "${payload.unread} unread by the orchestrator" else "${payload.inbox.size} items",
                open = activityOpen,
                onToggle = { activityOpen = !activityOpen },
            )
        }
        if (activityOpen) {
            if (payload.inbox.isEmpty()) {
                item(key = "inbox-empty") { Quiet("Nothing has arrived yet.") }
            }
            items(payload.inbox.take(40), key = { "inbox-${it.id}" }) { item ->
                InboxRow(item, payload, item.id in busy, nowMillis, actions)
            }
        }

        item(key = "foot") {
            OutlinedButton(
                onClick = actions.onShowConversation,
                shape = Pill,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Metrics.ScreenPadding)
                    .heightIn(min = Metrics.TouchTarget),
            ) { Text("Open the conversation") }
        }
    }
}

private fun Modifier.cardPadding(): Modifier = padding(horizontal = Metrics.ScreenPadding, vertical = 4.dp)

@Composable
private fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = tgx.textMuted,
        modifier = modifier.padding(horizontal = Metrics.ScreenPadding, vertical = 6.dp),
    )
}

// -------------------------------------------------------------------- head

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Head(payload: OrchestratorPayload, quota: List<QuotaWindow>, actions: OrchestratorActions, now: Long) {
    val p = remember(payload) { progress(payload) }
    Column(
        Modifier.fillMaxWidth().padding(start = Metrics.ScreenPadding, end = 4.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            val goal = payload.summary?.goal?.takeIf { it.isNotBlank() }
            Text(
                goal ?: "No goal yet — the orchestrator sets one when it writes its brief.",
                style = if (goal != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = if (goal != null) MaterialTheme.colorScheme.onSurface else tgx.textMuted,
                modifier = Modifier.weight(1f).padding(top = 10.dp),
            )
            IconButton(onClick = actions.onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "This orchestrator's settings", tint = tgx.textMuted)
            }
        }

        if (p.segments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(end = Metrics.ScreenPadding).height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val colors = tgx
                p.segments.forEach { (_, seg) ->
                    val color = when (seg) {
                        Segment.Merged -> colors.purple
                        Segment.Pr -> colors.purple.copy(alpha = 0.55f)
                        Segment.Done -> colors.textMuted
                        Segment.Run -> colors.green
                        Segment.Wait -> colors.yellow
                        Segment.Fail -> colors.red
                        Segment.Idle -> colors.s5
                        Segment.Queued -> Color.Transparent
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(color, RoundedCornerShape(3.dp))
                            .then(if (seg == Segment.Queued) Modifier.border(1.dp, colors.textFaint, RoundedCornerShape(3.dp)) else Modifier),
                    )
                }
            }
        }

        Text(
            if (p.total > 0) {
                "${p.finished} of ${p.total} finished · ${p.running} of ${p.max} running" +
                    (if (p.queued > 0) " · ${p.queued} queued" else "")
            } else {
                "No tasks yet · up to ${p.max} at once"
            },
            style = TgxType.mono12,
            color = tgx.textMuted,
        )

        val meters = usageMeters(
            payload.settings.usageStop,
            quota.mapNotNull { w -> w.usedPercent?.let { w.type to it } }.toMap(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(end = Metrics.ScreenPadding),
        ) {
            meters.forEach { m -> MeterChip(m) }
        }
    }
}

@Composable
private fun MeterChip(m: Meter) {
    val colors = tgx
    val tint = when (m.tone) {
        "over" -> colors.red
        "near" -> colors.yellow
        else -> colors.blue
    }
    Column(Modifier.width(120.dp)) {
        Text(
            buildString {
                append(m.label).append("  ")
                append(m.used?.let { "${it.toInt()}%" } ?: "—")
                m.cutoff?.let { append(" · stops at $it%") }
            },
            style = TgxType.mono12,
            color = if (m.tone == "ok") colors.textMuted else tint,
            maxLines = 1,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(colors.s4, RoundedCornerShape(2.dp))
                .drawBehind {
                    val used = ((m.used ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
                    drawRect(tint, Offset.Zero, Size(size.width * used, size.height))
                    m.cutoff?.let { c ->
                        val x = size.width * (c / 100f).coerceIn(0f, 1f)
                        drawRect(colors.textFaint.copy(alpha = 1f), Offset(x - 1f, -2f), Size(2f, size.height + 4f))
                    }
                },
        )
    }
}

// --------------------------------------------------------------- needs you

@Composable
private fun NeedCard(
    need: NeedItem,
    payload: OrchestratorPayload,
    busy: Set<String>,
    now: Long,
    actions: OrchestratorActions,
    modifier: Modifier = Modifier,
) {
    val colors = tgx
    val answered = need is NeedItem.Decision && need.decision.answeredAt != null
    val edge = when {
        answered -> colors.textFaint
        need is NeedItem.Failed -> colors.red
        else -> colors.yellow
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(if (answered) colors.s2 else colors.s3, RoundedCornerShape(Radius.Lg))
            .drawBehind { drawRect(edge, Offset.Zero, Size(Metrics.ReasonBorder.toPx(), size.height)) }
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (need) {
            is NeedItem.Paused -> {
                Text("Paused — ${need.reason}.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    need.resumeAt?.let { "Carries on by itself at ${clockAt(it, now)}." }
                        ?: "Nothing runs until you resume it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                AskButton(
                    if ("resume:${payload.orchestratorId}" in busy) "Resuming…" else "Resume",
                    actions.onResume,
                    kind = AskButtonKind.Affirmative,
                    enabled = "resume:${payload.orchestratorId}" !in busy,
                )
            }
            is NeedItem.OrchestratorAsk -> {
                Text(
                    when (need.ask.askKind) {
                        AskKind.Plan -> "The orchestrator has a plan for you to approve."
                        AskKind.Question -> "The orchestrator is asking you something."
                        else -> "The orchestrator wants permission: ${need.ask.toolLabel}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                AskButton("Answer it in the conversation", actions.onShowConversation, kind = AskButtonKind.Affirmative)
            }
            is NeedItem.Decision -> DecisionBody(need.decision, payload, need.decision.id in busy, now, actions)
            is NeedItem.Stuck -> StuckBody(need, now, actions)
            is NeedItem.Failed -> {
                Text("${nameIn(need.worker)} stopped with an error.", style = MaterialTheme.typography.bodyMedium)
                AskButton("Look at it", { actions.onOpenSession(need.worker.id) })
            }
        }
    }
}

private fun nameIn(w: OrchestratorWorker): String = w.title?.takeIf { it.isNotBlank() }?.let { clip(it, 48) } ?: "A worker"

@Composable
private fun DecisionBody(
    d: BriefDecision,
    payload: OrchestratorPayload,
    sending: Boolean,
    now: Long,
    actions: OrchestratorActions,
) {
    val colors = tgx
    Text(d.text, style = MaterialTheme.typography.bodyMedium)
    if (d.answeredAt != null) {
        Text(
            "You answered “${clip(d.answer.orEmpty(), 80)}” ${agoWords(d.answeredAt, now)} — " +
                "waiting for the orchestrator to pick it up.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        return
    }
    val from = d.workerId?.let { id -> payload.workers.firstOrNull { it.id == id } }
    Text(
        (if (from != null) "From ${from.title ?: "a worker"}" else "From the orchestrator") +
            " · asked ${agoWords(d.at, now)}",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textMuted,
    )
    var replying by rememberSaveable(d.id) { mutableStateOf(false) }
    var reply by rememberSaveable(d.id) { mutableStateOf("") }
    d.options.forEach { option ->
        AskButton(option, { actions.onAnswerDecision(d, option) }, enabled = !sending)
    }
    if (!replying) {
        AskButton(
            if (sending) "Sending…" else "Reply…",
            { replying = true },
            kind = if (d.options.isEmpty()) AskButtonKind.Affirmative else AskButtonKind.Neutral,
            enabled = !sending,
        )
    } else {
        OutlinedTextField(
            value = reply,
            onValueChange = { reply = it },
            placeholder = { Text("Your answer — it is sent to the orchestrator") },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            enabled = !sending,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                AskButton("Cancel", { replying = false }, enabled = !sending)
            }
            Box(Modifier.weight(1f)) {
                AskButton(
                    if (sending) "Sending…" else "Send",
                    {
                        actions.onAnswerDecision(d, reply)
                        replying = false
                        reply = ""
                    },
                    kind = AskButtonKind.Affirmative,
                    enabled = !sending && reply.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun StuckBody(need: NeedItem.Stuck, now: Long, actions: OrchestratorActions) {
    val w = need.worker
    val item = need.item
    val what = when (w.pending) {
        "plan" -> "a plan approval"
        "tool" -> "a permission"
        else -> "an answer"
    }
    Text(
        "${nameIn(w)} has waited ${shortAge(now - item.at)} for $what, and the orchestrator is not working on it.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("You can answer first; it will be told.", style = MaterialTheme.typography.bodySmall, color = tgx.textMuted)
    val requestId = item.requestId
    if (requestId != null && w.pending == "tool") {
        // A tool ask is complete as `{requestId, decision}` — there is nothing to read
        // first, which is why it is the one answerable from here.
        item.ask?.let { ask ->
            Text(ask.toolLabel ?: "A tool", style = TgxType.mono12, color = tgx.textMuted)
        }
        AskButton("Allow", { actions.onAnswerWorker(w.id, PermissionRequestBody(requestId, Decision.ALLOW)) }, kind = AskButtonKind.Affirmative)
        AskButton("Always", { actions.onAnswerWorker(w.id, PermissionRequestBody(requestId, Decision.ALLOW_ALWAYS)) })
        AskButton("Deny", { actions.onAnswerWorker(w.id, PermissionRequestBody(requestId, Decision.DENY)) }, kind = AskButtonKind.Destructive)
        AskButton("Open the worker", { actions.onOpenSession(w.id) })
    } else {
        AskButton("Look at it", { actions.onOpenSession(w.id) }, kind = AskButtonKind.Affirmative)
    }
}

// ---------------------------------------------------------------- tasks

@Composable
private fun TaskCard(task: OpenTask, busy: Boolean, now: Long, actions: OrchestratorActions, modifier: Modifier = Modifier) {
    val colors = tgx
    val fresh = now - task.at < TASK_STALE_MS
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.s3, RoundedCornerShape(Radius.Lg))
            .drawBehind {
                drawRect(if (fresh) colors.blue else colors.yellow, Offset.Zero, Size(Metrics.ReasonBorder.toPx(), size.height))
            }
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (task.test) Tag("test", Modifier.padding(start = 6.dp))
        }
        val origin = when (task.origin?.from) {
            "draft" -> "From your draft"
            "dialog" -> "From the Start form"
            else -> "Handed over"
        }
        Text(
            "$origin · ${agoWords(task.at, now)} · " +
                if (task.status == "new") "not read yet" else "read, not started",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Text(
            listOfNotNull(
                task.model?.takeIf { it.isNotBlank() } ?: "inherit",
                task.effort?.takeIf { it.isNotBlank() }?.let { "$it effort" },
                modeLabel(task.permissionMode),
                task.worktree?.name?.let { "worktree $it" },
            ).joinToString(" · "),
            style = TgxType.mono12,
            color = colors.textFaint,
        )
        AskButton(
            if (busy) "Taking back…" else "Take back",
            { actions.onWithdraw(task) },
            enabled = !busy,
        )
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier = Modifier, color: Color = tgx.textMuted) {
    Text(
        text,
        style = TgxType.badge,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

// --------------------------------------------------------------- workers

private fun LazyListScope.workers(
    payload: OrchestratorPayload,
    byId: Map<String, SessionSummary>,
    prs: PrsPayload?,
    now: Long,
    finishedOpen: Boolean,
    onToggleFinished: () -> Unit,
    actions: OrchestratorActions,
) {
    val sorted = payload.workers.sortedBy { it.spawnedAt }
    val open = sorted.filter { it.closedAt == null }
    val closed = sorted.filter { it.closedAt != null }
    val queued = payload.pendingSpawns
    item(key = "workers-h") {
        SectionHeader(
            "Workers",
            trailing = "${open.size} open" +
                (if (queued.isNotEmpty()) " · ${queued.size} queued" else "") +
                (if (closed.isNotEmpty()) " · ${closed.size} finished" else ""),
        )
    }
    if (open.isEmpty() && queued.isEmpty() && closed.isEmpty()) {
        item(key = "workers-empty") {
            Quiet(
                "No workers yet. They appear here as the orchestrator starts them, " +
                    "up to ${payload.settings.maxRunning} at once.",
            )
        }
        return
    }
    items(open, key = { "worker-${it.id}" }) { w -> WorkerRow(w, payload, byId[w.id], prs, now, actions) }
    itemsIndexed(queued, key = { _, s -> "queued-${s.id}" }) { i, s ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).border(1.5.dp, tgx.textFaint, CircleShape))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    originChip(s.origin)?.let { Tag(it, Modifier.padding(end = 6.dp), tgx.blue) }
                    Text(spawnTitle(s), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(queuedWords(i), style = MaterialTheme.typography.bodySmall, color = tgx.textMuted)
            }
        }
    }
    if (closed.isNotEmpty()) {
        item(key = "finished-h") {
            Fold(
                label = "Finished",
                count = "${closed.size}",
                open = finishedOpen,
                onToggle = onToggleFinished,
                detail = if (finishedOpen) null else closed.joinToString(", ") { it.title ?: "untitled" },
            )
        }
        if (finishedOpen) {
            items(closed, key = { "worker-${it.id}" }) { w -> WorkerRow(w, payload, byId[w.id], prs, now, actions) }
        }
    }
}

@Composable
private fun WorkerRow(
    w: OrchestratorWorker,
    payload: OrchestratorPayload,
    session: SessionSummary?,
    prs: PrsPayload?,
    now: Long,
    actions: OrchestratorActions,
) {
    val colors = tgx
    val decision = openDecisionFor(payload, w.id)
    val fresh = prs?.sessions?.get(w.id)?.status?.takeIf { it != "unknown" }
    val stage = workerStage(w, payload.inbox, prStatus = fresh, mode = session?.permissionMode, decision = decision)
    val activity = session?.runner?.let { it.detail ?: it.activity }
    val line = stageLine(w, stage, payload.inbox, decision, activity, now)
    val dot = when (stage.tone) {
        Tone.Wait, Tone.You -> colors.yellow
        Tone.Error, Tone.Fail -> colors.red
        Tone.Run -> colors.green
        else -> colors.textFaint
    }
    val nowPip = when (stage.tone) {
        Tone.Run -> colors.green
        Tone.Wait, Tone.You -> colors.yellow
        Tone.Pr, Tone.Merged -> colors.purple
        Tone.Fail, Tone.Error -> colors.red
        else -> colors.textMuted
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.onOpenSession(w.id) }
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp)
            .alpha(if (w.closed) 0.7f else 1f),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(dot, CircleShape))
        Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                originChip(w.origin)?.let { Tag(it, Modifier.padding(end = 6.dp), colors.blue) }
                Text(
                    w.title?.takeIf { it.isNotBlank() } ?: "untitled worker",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stage.at >= 5 && w.pr != null) {
                    PrChip(w.pr.number, stage.prStatus, w.pr.url, actions.onOpenUrl, Modifier.padding(end = 6.dp))
                }
                val leadColor = when (stage.tone) {
                    Tone.Wait, Tone.You -> colors.yellow
                    Tone.Error -> colors.red
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (line.lead.isNotBlank()) {
                    Text(
                        line.lead,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = leadColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            line.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // The six stages as pips: passed ones filled, the current one large and in
            // the row's tone, the rest empty.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                stage.steps.forEachIndexed { i, step ->
                    val size = if (step == StepState.Now) 9.dp else 6.dp
                    val m = Modifier.size(size)
                    Box(
                        when (step) {
                            StepState.Now -> m.background(nowPip, CircleShape)
                            StepState.Done -> m.background(colors.textMuted, CircleShape)
                            StepState.Unknown -> m.background(colors.s5, CircleShape)
                            StepState.Skipped -> m.border(1.dp, colors.textFaint, CircleShape)
                            StepState.Todo -> m.border(1.dp, colors.s4, CircleShape)
                        },
                    )
                    if (step == StepState.Now) {
                        Text(STAGES[i], style = TgxType.mono12, color = nowPip)
                    }
                }
                w.worktree?.branch?.takeIf { !w.closed }?.let {
                    Text(it, style = TgxType.mono12, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun PrChip(number: Int, status: String?, url: String?, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val tint = prTint(prLookOf(status))
    val word = prWord(status)
    Text(
        "#$number" + (if (word.isNotBlank()) " $word" else ""),
        style = TgxType.mono12,
        color = tint,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, tint.copy(alpha = 0.6f), Pill)
            .then(if (url != null) Modifier.clickable { onOpenUrl(url) } else Modifier)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

// ----------------------------------------------------------- merge order

@Composable
private fun MergeRowItem(row: MergeRow, actions: OrchestratorActions) {
    val merged = row.status == "merged"
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (row.workerId != null) Modifier.clickable { actions.onOpenSession(row.workerId) } else Modifier)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp)
            .alpha(if (merged) 0.62f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${row.n}", style = TgxType.mono12, color = tgx.textFaint, modifier = Modifier.width(22.dp))
        PrChip(row.number, row.status, row.url, actions.onOpenUrl)
        Spacer(Modifier.width(8.dp))
        if (row.title != null) {
            Text(row.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text("not opened by one of these workers", style = MaterialTheme.typography.bodySmall, color = tgx.textMuted)
        }
    }
}

// --------------------------------------------------------------- activity

@Composable
private fun Fold(label: String, count: String, open: Boolean, onToggle: () -> Unit, detail: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = Metrics.TouchTarget)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (open) "▾ " else "▸ ") + label,
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textMuted,
            )
            Text("  $count", style = TgxType.mono12, color = tgx.textFaint, modifier = Modifier.weight(1f))
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tgx.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InboxRow(item: InboxItem, payload: OrchestratorPayload, busy: Boolean, now: Long, actions: OrchestratorActions) {
    val colors = tgx
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val worker = item.workerId?.let { id -> payload.workers.firstOrNull { it.id == id } }
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (item.open) 1f else 0.6f)
            .clickable { open = !open }
            .padding(start = Metrics.ScreenPadding, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(inboxKindLabel(item.kind), color = if (item.status == "new") colors.blue else colors.textMuted)
            if (worker != null) {
                Text(
                    clip(worker.title ?: "a worker", 40),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f, fill = false)
                        .clickable { actions.onOpenSession(worker.id) },
                )
            }
            Text(
                " · ${shortAge(now - item.at)} · ${inboxStatusWord(item)}",
                style = TgxType.mono12,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dismissable(item)) {
                IconButton(onClick = { actions.onDismiss(item) }, enabled = !busy) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.textFaint)
                }
            }
        }
        val text = item.text.ifBlank { "(empty)" }
        if (open) {
            MarkdownText(text, Modifier.fillMaxWidth().padding(top = 4.dp, end = Metrics.ScreenPadding))
        } else {
            Text(clip(text, 160), style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = Metrics.ScreenPadding))
        }
    }
}
