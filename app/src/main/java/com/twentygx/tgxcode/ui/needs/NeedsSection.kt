package com.twentygx.tgxcode.ui.needs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.BoardCard
import com.twentygx.tgxcode.data.model.BoardReason
import com.twentygx.tgxcode.data.model.BoardWorkers
import com.twentygx.tgxcode.data.model.Decision
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.conversation.SudoActions
import com.twentygx.tgxcode.ui.conversation.SudoCard
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * What is blocked on you, as a section at the top of the Sessions list.
 *
 * This used to be a screen and a tab of its own. It reads better here: "what needs me"
 * and "what else is there" are the same question asked at two zoom levels, and on a
 * phone a tab you have to leave to see the second half is a tab you check twice.
 *
 * A `LazyListScope` extension rather than a composable, because it has to share one
 * scroll with the session rows below it. Two nested `LazyColumn`s would each want to
 * be as tall as their content, and the inner one would win.
 *
 * The board arrives **already ordered needs-you-first** (`overview.js` ranks
 * `ask > error > here > elsewhere > pinned`), and this deliberately does not re-sort.
 * Re-sorting here would be a second opinion about a question the bridge has already
 * answered, and the two would drift.
 */
fun LazyListScope.needsSection(
    board: Overview?,
    answering: Set<String>,
    onOpen: (String) -> Unit,
    onAnswer: (Ask, String) -> Unit,
    sudo: SudoActions? = null,
) {
    val cards = board?.sessions.orEmpty()
    if (cards.isEmpty()) return

    // Orchestrators carry their workers: the worker cards stay in `sessions`, each
    // naming its orchestrator, and are lifted out here to be drawn inside that card —
    // the web board's `nestWorkers`. One whose orchestrator is not on the board is an
    // ordinary card.
    val byId = cards.associateBy { it.sessionId }
    val hosts = cards.filter { it.workers != null }.map { it.sessionId }.toSet()
    val top = cards.filterNot { it.orchestratorId != null && it.orchestratorId in hosts }

    item(key = "needs-header") { SectionHeader("Needs you") }
    items(top, key = { "needs-${it.sessionId}" }) { card ->
        val padding = Modifier.padding(horizontal = Metrics.ScreenPadding, vertical = 5.dp)
        val carried = card.workers
        if (carried != null) {
            OrchestratorBoardCard(
                card = card,
                carried = carried,
                workers = carried.ids.mapNotNull { byId[it] },
                answering = answering,
                onOpen = onOpen,
                onAnswer = onAnswer,
                sudo = sudo,
                modifier = padding,
            )
        } else {
            BoardCardItem(
                card = card,
                busy = card.ask?.requestId in answering,
                onOpen = { onOpen(card.sessionId) },
                onAnswer = onAnswer,
                sudo = sudo,
                modifier = padding,
            )
        }
    }
}

/** Five worker rows before "+N more"; one over is drawn rather than folded. */
private const val WORKERS_SHOWN = 5

/**
 * An orchestrator and the workers it carries, as one card: its own line, then a row
 * per active worker (asks that are yours first, as the bridge ordered them), the
 * spawns queued behind `maxRunning`, and a fold for the quiet ones.
 */
@Composable
private fun OrchestratorBoardCard(
    card: BoardCard,
    carried: BoardWorkers,
    workers: List<BoardCard>,
    answering: Set<String>,
    onOpen: (String) -> Unit,
    onAnswer: (Ask, String) -> Unit,
    sudo: SudoActions?,
    modifier: Modifier = Modifier,
) {
    val colors = tgx
    val accent = when (card.boardReason) {
        BoardReason.Ask -> colors.yellow
        BoardReason.Error -> colors.red
        BoardReason.Here -> colors.green
        else -> Color.Transparent
    }
    var more by rememberSaveable(card.sessionId) { mutableStateOf(false) }
    var quietOpen by rememberSaveable(card.sessionId) { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .background(if (card.boardReason == BoardReason.Ask) colors.s3 else colors.s2, RoundedCornerShape(Radius.Lg))
            .drawBehind { drawRect(accent, Offset.Zero, Size(Metrics.ReasonBorder.toPx(), size.height)) }
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                card.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (carried.paused) "orch · paused" else "orch",
                style = TgxType.mono12,
                color = if (carried.paused) colors.yellow else colors.textFaint,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // On the board only because of its workers: say what they are doing rather
        // than borrowing their reason as though it were its own.
        val line = if (!carried.own) {
            val running = workers.count { it.runner?.state == "busy" || it.runner?.state == "starting" }
            val asking = workers.count { it.ask != null }
            val failed = workers.count { it.boardReason == BoardReason.Error }
            (if (carried.paused) "Paused" else "Waiting on workers") + " · " + listOfNotNull(
                "$running running",
                "$asking asking".takeIf { asking > 0 },
                "$failed failed".takeIf { failed > 0 },
                "${carried.queued.size} queued".takeIf { carried.queued.isNotEmpty() },
            ).joinToString(", ")
        } else {
            card.why
        }
        Text(line, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)

        val shown = if (more || workers.size <= WORKERS_SHOWN + 1) workers else workers.take(WORKERS_SHOWN)
        shown.forEach { w ->
            WorkerBoardRow(w, busy = w.ask?.requestId in answering, onOpen = { onOpen(w.sessionId) }, onAnswer = onAnswer, sudo = sudo)
        }
        if (workers.size > WORKERS_SHOWN + 1) {
            Text(
                if (more) "Show fewer" else "+${workers.size - WORKERS_SHOWN} more",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { more = !more }.padding(vertical = 4.dp),
            )
        }
        carried.queued.forEach { q ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).border(1.5.dp, colors.textFaint, CircleShape))
                Text(
                    (q.title ?: "untitled") + " — queued, starts when a slot frees",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
        }
        if (carried.quiet.isNotEmpty() || carried.idle + carried.finished > 0) {
            Text(
                (if (quietOpen) "▾ " else "▸ ") + listOfNotNull(
                    "${carried.idle} idle".takeIf { carried.idle > 0 },
                    "${carried.finished} finished".takeIf { carried.finished > 0 },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { quietOpen = !quietOpen }
                    .padding(vertical = 6.dp),
            )
            if (quietOpen) {
                carried.quiet.forEach { q ->
                    Text(
                        listOfNotNull(
                            q.title ?: "untitled",
                            if (q.state == "finished") "Finished" else "Idle",
                            q.standing?.takeIf { it.isNotBlank() }?.let { com.twentygx.tgxcode.ui.orchestrator.clip(it, 60) },
                            com.twentygx.tgxcode.core.ago(q.at).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(q.id) }
                            .padding(vertical = 6.dp),
                    )
                }
                val older = carried.idle + carried.finished - carried.quiet.size
                if (older > 0) {
                    Text("+$older older", style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
                }
            }
        }

        AskButton(
            "Open the orchestrator",
            { onOpen(card.sessionId) },
            kind = if (card.ask != null && card.workers?.own != false) AskButtonKind.Affirmative else AskButtonKind.Neutral,
        )
    }
}

@Composable
private fun WorkerBoardRow(
    card: BoardCard,
    busy: Boolean,
    onOpen: () -> Unit,
    onAnswer: (Ask, String) -> Unit,
    sudo: SudoActions?,
) {
    val colors = tgx
    val dot = when {
        card.ask != null -> colors.yellow
        card.boardReason == BoardReason.Error -> colors.red
        card.runner?.state == "busy" || card.runner?.state == "starting" -> colors.green
        else -> colors.textFaint
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.s1, RoundedCornerShape(Radius.Md))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Text(
                card.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        Text(card.why, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val ask = card.ask
        if (ask != null) {
            if (card.askForOrchestrator) {
                Text(
                    "The orchestrator has it — you can answer first.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textFaint,
                )
            }
            val sudoAsk = ask.sudo
            if (ask.askKind == AskKind.Sudo && sudoAsk != null && sudo != null) {
                // Never the orchestrator's to answer: it has no password to give.
                SudoCard(sudoAsk.copy(sessionId = sudoAsk.sessionId ?: card.sessionId), sudo, compact = true)
            } else if (ask.askKind == AskKind.Tool) {
                val stamped = ask.copy(sessionId = ask.sessionId ?: card.sessionId)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        AskButton("Allow", { onAnswer(stamped, Decision.ALLOW) }, kind = AskButtonKind.Affirmative, enabled = !busy)
                    }
                    Box(Modifier.weight(1f)) {
                        AskButton("Always", { onAnswer(stamped, Decision.ALLOW_ALWAYS) }, enabled = !busy)
                    }
                    Box(Modifier.weight(1f)) {
                        AskButton("Deny", { onAnswer(stamped, Decision.DENY) }, kind = AskButtonKind.Destructive, enabled = !busy)
                    }
                }
            }
        }
    }
}

/** The one section heading, shared by every list that has more than one part. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = Metrics.ScreenPadding,
                end = Metrics.ScreenPadding,
                top = 14.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tgx.textMuted,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(it, style = TgxType.mono12, color = tgx.textFaint)
        }
    }
}

@Composable
fun BoardCardItem(
    card: BoardCard,
    busy: Boolean,
    onOpen: () -> Unit,
    onAnswer: (Ask, String) -> Unit,
    modifier: Modifier = Modifier,
    sudo: SudoActions? = null,
) {
    val colors = tgx
    val accent = when (card.boardReason) {
        BoardReason.Ask -> colors.yellow
        BoardReason.Error -> colors.red
        BoardReason.Here -> colors.green
        else -> Color.Transparent
    }
    // An ask also lifts the surface a step, so the card that needs you reads as
    // nearer than the ones that do not.
    val surface = if (card.boardReason == BoardReason.Ask) colors.s3 else colors.s2

    Column(
        modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(Radius.Lg))
            .drawBehind {
                drawRect(accent, Offset.Zero, Size(Metrics.ReasonBorder.toPx(), size.height))
            }
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                card.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            com.twentygx.tgxcode.ui.theme.projectAccent(card.cwd)?.let { c ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(start = 8.dp, bottom = 5.dp)
                        .size(8.dp)
                        .background(c, androidx.compose.foundation.shape.CircleShape),
                )
            }
            if (card.place.isNotBlank()) {
                Text(
                    card.place,
                    style = TgxType.mono12,
                    color = colors.textFaint,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Text(card.why, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)

        // The bridge's judgement, not the CLI's: busy, silent past the threshold, no
        // tool call open and nothing waiting on you. An ask outranks it — waiting on a
        // person is not silence.
        if (card.ask == null && card.runner?.stalled == true) {
            Text(
                "Stalled — nothing from it for a while",
                style = MaterialTheme.typography.labelMedium,
                color = colors.yellow,
            )
        }

        // Only when there is no ask: if something is blocked, the block is the news.
        if (card.ask == null) {
            card.headline?.let {
                Text(
                    it,
                    style = TgxType.mono125,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        card.taskLine?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        }

        val ask = card.ask
        val sudoAsk = ask?.sudo
        if (ask?.askKind == AskKind.Sudo && sudoAsk != null && sudo != null) {
            // Answerable here, as on the desktop's boards: the card shows everything
            // the conversation's does, and the password goes from this field.
            SudoCard(sudoAsk.copy(sessionId = sudoAsk.sessionId ?: card.sessionId), sudo, compact = true)
            AskButton("Open the session", onOpen)
        } else if (ask != null && ask.askKind == AskKind.Tool) {
            // A tool ask is answerable from the card: `{requestId, decision}` is the
            // complete answer and there is nothing to read first.
            val stamped = ask.copy(sessionId = ask.sessionId ?: card.sessionId)
            AskButton("Allow", { onAnswer(stamped, Decision.ALLOW) },
                kind = AskButtonKind.Affirmative, enabled = !busy)
            AskButton("Allow ${ask.toolLabel} all session",
                { onAnswer(stamped, Decision.ALLOW_ALWAYS) }, enabled = !busy)
            AskButton("Deny", { onAnswer(stamped, Decision.DENY) },
                kind = AskButtonKind.Destructive, enabled = !busy)
            AskButton("Open the session", onOpen)
        } else {
            // A plan or a question deliberately gets one button that goes and shows
            // it. Two buttons against a plan nobody has read is worse than one
            // button that opens it.
            AskButton(
                if (ask != null) "Read it →" else "Open",
                onOpen,
                kind = if (ask != null) AskButtonKind.Affirmative else AskButtonKind.Neutral,
            )
        }
    }
}
