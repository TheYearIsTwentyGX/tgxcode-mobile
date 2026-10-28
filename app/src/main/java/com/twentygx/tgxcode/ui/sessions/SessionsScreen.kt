package com.twentygx.tgxcode.ui.sessions

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.common.PrBadge
import com.twentygx.tgxcode.ui.common.PrGlyph
import com.twentygx.tgxcode.ui.common.StateDot
import com.twentygx.tgxcode.ui.common.prBadgeOf
import com.twentygx.tgxcode.ui.common.prBadgeWords
import com.twentygx.tgxcode.ui.common.rowStateOf
import com.twentygx.tgxcode.ui.needs.SectionHeader
import com.twentygx.tgxcode.ui.conversation.SudoActions
import com.twentygx.tgxcode.ui.needs.needsSection
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The landing screen: what needs you, then everything else.
 *
 * A flat list, deliberately — no project grouping like the desktop rail.
 *
 * Filtering is entirely client-side, so typing never touches the network. The bridge
 * has a `q` parameter, but round-tripping a keystroke over a relay to filter a list
 * already in memory would be slower and worse.
 *
 * The filter box stays outside the scroll: it is how you find a session, and a control
 * you have to scroll back up to reach is one you stop using. The Needs-you cards do
 * scroll, and **disappear entirely once the filter has anything in it** — a card that
 * is on the board because something is blocked is not a search result, and leaving it
 * pinned above a list of matches would read as though it were one.
 */
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    board: Overview?,
    answering: Set<String>,
    askingSessions: Set<String>,
    filter: String,
    onFilterChanged: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAnswer: (Ask, String) -> Unit,
    /** Answers a board card's sudo ask. */
    sudo: SudoActions? = null,
    /**
     * The whole `GET /api/prs` payload rather than a pre-derived map: `gh.error` is
     * the wording for a grey glyph, so the rows need it too.
     */
    prs: PrsPayload?,
    /** The row's long press: pin, archive, rename, delete, and its pull requests. */
    onOptions: (SessionSummary) -> Unit,
    showArchived: Boolean = false,
    onShowArchivedChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val needle = filter.trim().lowercase()
    val searching = needle.isNotEmpty()
    val archivedCount = sessions.count { it.archived }
    // An orchestrator's workers are its business: listed on its own page, not loose
    // among everything else — the rail's rule. A pinned or archived one stays a row of
    // its own, as does one whose orchestrator is not in the list, and a search finds
    // them all.
    val hosts = sessions.asSequence().filter { it.orchestrator != null && !it.archived }.map { it.sessionId }.toSet()
    val visible = sessions.asSequence()
        .filter { s ->
            searching || s.pinned || s.archived ||
                s.worker?.orchestratorId?.let { it in hosts } != true
        }
        // Archived *instead of* the rest, not as well: the chip is a way to find one
        // you put away, and burying it under every live session defeats that.
        .filter { it.archived == showArchived }
        .filter { session ->
            !searching || listOfNotNull(
                session.title,
                session.projectName,
                session.worktree?.name,
            ).any { it.lowercase().contains(needle) }
        }
        // Pinned first, as on the rail; otherwise the bridge's order, untouched —
        // `sortedByDescending` is stable.
        .sortedByDescending { it.pinned }
        .toList()

    val needsCount = board?.sessions.orEmpty().size
    // With no cards and no rows there is nothing to scroll, and a full-height empty
    // state reads better than two headings over nothing.
    val bare = visible.isEmpty() && (searching || needsCount == 0)

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp)
                .heightIn(min = Metrics.TouchTarget),
            placeholder = { Text("Filter sessions") },
            singleLine = true,
            shape = Pill,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        // Only once there is something archived to show — a chip that can only ever
        // produce an empty list is a control with no purpose.
        if (archivedCount > 0 || showArchived) {
            FilterChip(
                selected = showArchived,
                onClick = { onShowArchivedChanged(!showArchived) },
                label = { Text("Archived · $archivedCount") },
                modifier = Modifier.padding(horizontal = Metrics.ScreenPadding),
            )
        }

        if (bare) {
            EmptyState(
                headline = when {
                    searching -> "Nothing matches"
                    showArchived -> "Nothing archived"
                    board == null -> "Waiting for the bridge…"
                    else -> "No sessions yet"
                },
                detail = if (!searching && board != null && board.hidden > 0) {
                    "${board.hidden} archived or idle session${if (board.hidden == 1) "" else "s"}."
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                // A zero-height item that is *always* here, and the reason is not
                // cosmetic. `/api/sessions` resolves before `/api/overview`, so the
                // rows arrive first and the needs cards are **prepended** a moment
                // later — and a lazy list anchors its scroll position on the first
                // visible item's key, so it holds that first row against the top and
                // pushes the entire section above the fold. On a cold start the part
                // of this screen that matters most would be invisible until you
                // scrolled up, which is exactly what it was doing.
                //
                // With a permanent item at index 0, the anchor is something that never
                // moves, and everything inserted after it stays on screen.
                item(key = "list-anchor") { Spacer(Modifier.height(0.dp)) }

                if (!searching && !showArchived) {
                    needsSection(board, answering, onOpen, onAnswer, sudo)
                }

                // The heading earns its place only when there is something above it to
                // be distinguished from.
                if (!searching && !showArchived && needsCount > 0) {
                    item(key = "sessions-header") {
                        SectionHeader("All sessions", trailing = "${visible.size}")
                    }
                }

                items(visible, key = { it.sessionId }) { session ->
                    val badge = prBadgeOf(session, prs)
                    SessionRow(
                        session = session,
                        asking = session.sessionId in askingSessions,
                        badge = badge,
                        ghError = prs?.gh?.error,
                        onClick = { onOpen(session.sessionId) },
                        onLongClick = { onOptions(session) },
                    )
                    HorizontalDivider(color = tgx.outlineSoft, thickness = 1.dp)
                }
            }
        }
    }
}

/**
 * @param badge null for a session that raised no pull requests, which draws nothing.
 *   Not the same as one whose PRs nobody has resolved yet — that is a grey glyph.
 */
@Composable
private fun SessionRow(
    session: SessionSummary,
    asking: Boolean,
    badge: PrBadge?,
    ghError: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val orch = session.orchestrator
    val meta = listOfNotNull(
        "stalled".takeIf { session.isStalled },
        orch?.let {
            when {
                it.paused -> "orch paused"
                it.inbox > 0 -> "orch · ${it.inbox} unread"
                else -> "orch"
            } + (if (it.workers > 0) " · ${it.workers} worker${if (it.workers == 1) "" else "s"}" else "")
        },
        "worker".takeIf { session.worker != null },
        "pinned".takeIf { session.pinned },
        session.place?.takeIf { it.isNotBlank() },
        ago(session.lastTs).takeIf { it.isNotBlank() },
        "${session.userMessages} turn${if (session.userMessages == 1) "" else "s"}",
    ).joinToString(" · ")

    val haptics = LocalHapticFeedback.current
    // The project's colour from the desktop, as the rail wears it: a stripe on the
    // leading edge. No colour, no stripe — nothing else about the row changes.
    val accent = com.twentygx.tgxcode.ui.theme.projectAccent(session.cwd ?: session.projectCwd)

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.SessionRowHeight)
            .drawBehind {
                if (accent != null) drawRect(accent, Offset.Zero, Size(3.dp.toPx(), size.height))
            }
            // Replaces `clickable` rather than joining it: two of these would mean two
            // indication nodes and two overlapping gesture detectors. A long press that
            // fires does *not* also fire the tap — the gesture loop consumes the
            // pointer sequence, so the following up never reaches `onClick`.
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // Done explicitly because whether Foundation does it for us has
                    // changed across versions. If the phone buzzes twice, drop this
                    // rather than keeping both.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                // What TalkBack reads out for the custom action. Without it the gesture
                // is invisible to a screen reader and the sheet is unreachable.
                onLongClickLabel = "Session options",
            )
            .padding(horizontal = Metrics.ScreenPadding, vertical = 10.dp),
    ) {
        Box(Modifier.padding(top = 7.dp, end = 10.dp)) {
            StateDot(
                rowStateOf(session, if (asking) setOf(session.sessionId) else emptySet()),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Where the last turn left things. Hidden while it works — see
            // [SessionSummary.standingLine] — and absent far more often than not.
            session.standingLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tgx.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The glyph leads the meta line, which is where the rail puts it: *"ahead
            // of the worktree name and the activity, which are the two things this line
            // is allowed to squeeze out"*. Leading also means it sits in a straight
            // column down the list, which a trailing one — floating after
            // variable-length text — would not.
            //
            // A sibling of the `Text`, never inside it. The line is `maxLines = 1` with
            // an ellipsis, and the glyph carries no weight, so it takes its intrinsic
            // 14dp and the text truncates into what is left. That ordering is
            // load-bearing: the glyph's *absence* means "no pull requests", so one that
            // vanished on a narrow row would be worse than none at all.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    PrGlyph(
                        look = badge.look,
                        contentDescription = prBadgeWords(badge, ghError),
                        modifier = Modifier.padding(end = 5.dp),
                    )
                }
                Text(
                    meta,
                    style = TgxType.mono12,
                    color = tgx.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
