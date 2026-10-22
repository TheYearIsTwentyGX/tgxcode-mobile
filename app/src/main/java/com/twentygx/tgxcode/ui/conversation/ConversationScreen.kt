package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.PrDetail
import com.twentygx.tgxcode.data.repo.Conversation
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.common.PrGlyph
import com.twentygx.tgxcode.ui.common.prFade
import com.twentygx.tgxcode.ui.common.prLookOf
import com.twentygx.tgxcode.ui.common.prTint
import com.twentygx.tgxcode.ui.tasks.TaskActions
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The one screen that is not a plain scroller: a scrolling log, a pinned ask slot,
 * and a pinned composer.
 */
@Composable
fun ConversationScreen(
    conversation: Conversation?,
    expandedTools: Set<String>,
    onToggleTool: (String) -> Unit,
    draft: String,
    onDraftChanged: (String) -> Unit,
    mode: String,
    modes: List<String>,
    onModeChanged: (String) -> Unit,
    sending: Boolean,
    answering: Boolean,
    nowMillis: Long,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAnswer: (PermissionRequestBody) -> Unit,
    onLoadAll: () -> Unit,
    /** Hand a pull request's URL to whatever opens links. */
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    starting: Boolean = false,
    /** Absent only in a preview; a real conversation always has one. */
    suggestionActions: TaskActions? = null,
    acting: Set<String> = emptySet(),
    controls: ComposerControls = ComposerControls(),
    userActions: UserTurnActions? = null,
    onDropQueued: (String) -> Unit = {},
    onMoveQueued: (String, Int) -> Unit = { _, _ -> },
) {
    if (conversation == null) {
        // A just-created session is not readable until Claude Code has written its
        // transcript — a few seconds. Saying "Starting…" is the difference between a
        // wait that looks deliberate and one that looks broken.
        if (starting) {
            EmptyState(
                headline = "Starting the session…",
                detail = "It appears here as soon as it writes its first line.",
                modifier = modifier,
            )
        } else {
            EmptyState("Loading…", modifier = modifier)
        }
        return
    }

    val listState = rememberLazyListState()
    var queueOpen by rememberSaveable(conversation.sessionId) { mutableStateOf(false) }

    /**
     * LazyColumn row space, named once.
     *
     * The "load all" button is row 0 when it exists, so event *i* is row
     * *i + headerCount* — and because every open uses `tail = 120`, the header is
     * present in the ordinary case, not the exotic one. A target computed as
     * `events.lastIndex` therefore lands one event short of the end, every single
     * time. Hence a val, rather than a `+ 1` at each use site to get wrong.
     */
    val headerCount = if ((conversation.truncated?.dropped ?: 0) > 0) 1 else 0
    val rowCount = headerCount + conversation.events.size

    /**
     * Whether we are following the end of the transcript.
     *
     * Separating this *intent* from the proximity test below is the whole fix.
     * mobile.js only ever needs the proximity test, because a div is already at the
     * top when it appears and `scrollTop = scrollHeight` always works. A LazyColumn has
     * to be told — and "am I near the end?" cannot answer "should I go there in the
     * first place?", because on a fresh open we are as far from the end as it is
     * possible to be, and that is exactly when we must move.
     *
     * `rememberSaveable`, keyed on the session: rotating while reading back through
     * history must not be answered by a jump to the end. A different session id
     * re-arms it. Process death rebuilds this screen from scratch and re-arms it too,
     * which is the wanted behaviour there — the tail it refetches may not be the one
     * the position was saved against.
     */
    var pinned by rememberSaveable(conversation.sessionId) { mutableStateOf(true) }

    /**
     * The event that was oldest before "load all" ran, so the transcript can grow
     * *upward* from where you were reading. See the button below.
     */
    var resumeAt by rememberSaveable(conversation.sessionId) { mutableStateOf<String?>(null) }

    val nearEndSlopPx = with(LocalDensity.current) { Metrics.StickToBottomSlop.roundToPx() }
    val distanceFromEnd by remember { derivedStateOf { listState.layoutInfo.distanceFromEnd() } }

    /**
     * A drag is the one unambiguous "I am reading, leave me alone" — it is what stops a
     * tool result landing mid-sentence from yanking the transcript to the end. The
     * fling that follows needs no signal of its own; the pin is already off. And a
     * programmatic scroll emits nothing here, which is what makes this safe: only
     * gestures reach the scrollable's interaction source.
     */
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) pinned = false
        }
    }

    /**
     * …and coming to rest near the end re-arms it.
     *
     * [Metrics.StickToBottomSlop] earns its 120.dp here rather than in the test below.
     * This is the forgiving question — "did you mean to be at the bottom?" — where
     * mobile.js's own tolerance applies and where being generous costs nothing. Waiting
     * for the scroll to settle lets a fling that ends near the bottom re-pin without
     * rubber-banding out from under the finger. (It was previously compared against
     * *pixels*, which made it about 40dp on a 3x phone.)
     */
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && distanceFromEnd <= nearEndSlopPx) pinned = true
        }
    }

    /**
     * Assert the pin against the layout, not against `events.size`.
     *
     * Three deliberate choices, each of them a bug that was in here:
     *
     * - **`requestScrollToItem`, not `scrollToItem`.** It is applied by whichever
     *   measure pass comes next instead of racing the first one, so it lands whether
     *   this effect runs before the LazyColumn has ever been measured or after. Which
     *   of those happens is genuinely undefined, and it is why a session opened at the
     *   top only *sometimes*.
     * - **Keyed on the layout, not the event count.** A tool result patches a row in
     *   place, so `events.size` does not change when the transcript grows under it; and
     *   the keyboard and the ask slot shrink the viewport without touching the events at
     *   all. All three move the end away from us and none of them changes a count.
     * - **`rows - 1`, in row space.** See [headerCount].
     *
     * It takes two steps because a row anchor cannot express "the bottom". Landing on a
     * row positions its *top*, and a LazyColumn only back-fills to the end when the
     * remaining content is shorter than the viewport. A final assistant message three
     * screens tall — routine here — would therefore be anchored two screens from its
     * end, which is the same complaint in a smaller shape. So: jump to the row when it
     * is not in the layout at all, then close the exact remainder. That reaches the
     * same place `mobile.js` does with `scrollTop = scrollHeight`.
     *
     * It cannot spin, because each step strictly reduces the distance and neither can
     * reintroduce it: the jump puts the newest row into the layout, and the nudge
     * consumes exactly the overhang it was given. If the list is already at its true
     * end the nudge consumes nothing, the distance does not change, and `snapshotFlow`
     * does not re-emit an unchanged value.
     */
    val rows by rememberUpdatedState(rowCount)
    LaunchedEffect(listState) {
        snapshotFlow {
            // Int.MIN_VALUE is "not following". Everything else is how far the end sits
            // below the fold, which is what both branches want — and folding it into
            // one value is what makes an appended event and a shrinking viewport both
            // re-fire.
            if (pinned && rows > 0) distanceFromEnd else Int.MIN_VALUE
        }.collect { overhang ->
            when {
                // Not in the layout: nothing measured yet, or it is somewhere below.
                overhang == Int.MAX_VALUE -> listState.requestScrollToItem(rows - 1)
                // On screen, but running off the bottom.
                overhang > 0 -> listState.scrollBy(overhang.toFloat())
            }
        }
    }

    /**
     * Land the replacement transcript on the event you were reading.
     *
     * `indexById` is already the id → row lookup the repository keeps for patching
     * tools; this is its second customer.
     */
    LaunchedEffect(resumeAt, rowCount) {
        val id = resumeAt ?: return@LaunchedEffect
        // Only once the replacement has landed — `truncated` going null is what says so.
        if (conversation.truncated != null) return@LaunchedEffect
        conversation.indexById[id]?.let { at -> listState.requestScrollToItem(at + headerCount) }
        // Cleared either way. If the boundary event is somehow not in the replacement
        // there is nothing to return to, and a request that can never be satisfied
        // should not outlive the load that made it.
        resumeAt = null
    }

    Column(modifier.fillMaxSize()) {
        PrChips(conversation.prs, onOpenUrl)
        ChecklistBar(conversation.checklist, conversation.sessionId)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            conversation.truncated?.takeIf { it.dropped > 0 }?.let { truncated ->
                item(key = "load-all") {
                    TextButton(
                        onClick = {
                            // Tapping this says "I want to read what came before", so
                            // drop the pin and remember where we are. Answering a
                            // request for history with a jump to the end is the exact
                            // opposite of it — and the button's own key is about to
                            // disappear, so without a remembered event LazyColumn has
                            // nothing left to anchor on and lands at the very top.
                            pinned = false
                            resumeAt = conversation.events.firstOrNull()?.id
                            onLoadAll()
                        },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Text(
                            "${truncated.dropped} earlier events — load all",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            items(
                count = conversation.events.size,
                key = { conversation.events[it].id },
                contentType = { conversation.events[it]::class.simpleName },
            ) { index ->
                val event = conversation.events[index]
                EventItem(
                    event = event,
                    expanded = event.id in expandedTools,
                    onToggleTool = onToggleTool,
                    suggestions = suggestionActions?.let {
                        SuggestionSlot(
                            sessionId = conversation.sessionId,
                            decisions = conversation.suggestions,
                            acting = acting,
                            actions = it,
                        )
                    },
                    userActions = userActions,
                )
            }
        }

        AskSlot(
            ask = conversation.ask,
            lastOutcome = conversation.lastOutcome,
            nowMillis = nowMillis,
            busy = answering,
            onAnswer = onAnswer,
        )

        Composer(
            text = draft,
            onTextChanged = onDraftChanged,
            mode = mode,
            modes = modes,
            onModeChanged = onModeChanged,
            runner = conversation.runner,
            sending = sending,
            onSend = onSend,
            onStop = onStop,
            modifier = Modifier.imePadding().navigationBarsPadding(),
            controls = controls.copy(onOpenQueue = { queueOpen = true }),
        )
    }

    if (queueOpen) {
        QueueSheet(
            queue = conversation.runner?.queue.orEmpty(),
            onDrop = onDropQueued,
            onMove = onMoveQueued,
            onDismiss = { queueOpen = false },
        )
    }
}

/**
 * How far the end of the transcript sits below the fold, in pixels: 0 or negative when
 * the newest event's last line is on screen.
 *
 * [Int.MAX_VALUE] when the newest event is not in the layout at all — either nothing has
 * been measured yet, or it is below the viewport. Both mean the same thing to every
 * caller, and getting that case backwards is the bug this screen had: the previous
 * version answered "we are already at the end" for an unmeasured list, so the check
 * passed, nothing scrolled, and with the event count unchanged nothing ever asked again.
 * An unmeasured list is not at the end. There is a scroll to do.
 *
 * `internal` rather than `private` so the arithmetic can be unit-tested; both interfaces
 * are four members wide and trivial to fake.
 */
internal fun LazyListLayoutInfo.distanceFromEnd(): Int {
    val last = visibleItemsInfo.lastOrNull() ?: return Int.MAX_VALUE
    if (last.index < totalItemsCount - 1) return Int.MAX_VALUE
    return last.offset + last.size - viewportEndOffset
}

/**
 * What this session has left on GitHub, one chip per pull request.
 *
 * **Per PR, not the session's aggregate** — that is the whole difference between this
 * surface and a Sessions row. A row has space for one glyph and folds a session's set
 * down to whichever most needs attention; here there is room to say what became of
 * each, so each carries its own status and its own colour. Mirrors `.conv-sub .pr`.
 *
 * A sub-bar rather than something in the top app bar, and rather than a row in the
 * transcript. The bar is a fixed-height container, so a two-line title with a chip row
 * under it clips — silently, which is the worst kind: the chips would simply not be
 * there on exactly the sessions with the longest titles. And a chip inside the
 * `LazyColumn` would scroll away, which is not what a sub-header does.
 *
 * Scrolls sideways instead of wrapping: ten PRs will not fit, and the transcript must
 * not lose height to make them.
 */
@Composable
private fun PrChips(prs: List<PrDetail>, onOpenUrl: (String) -> Unit) {
    if (prs.isEmpty()) return

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            // No vertical padding: each chip's own 44dp tap target already sets the
            // bar's height, and this is height taken off the transcript.
            .padding(horizontal = Metrics.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prs.forEach { pr ->
            val look = prLookOf(pr.status)
            Row(
                Modifier
                    .heightIn(min = Metrics.TouchTarget)
                    .clickable { onOpenUrl(pr.url) }
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrGlyph(look = look, contentDescription = null, size = 13.dp)
                Text(
                    "PR #${pr.number}",
                    style = TgxType.mono12,
                    color = prTint(look).copy(alpha = prFade(look)),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
    HorizontalDivider(color = tgx.outlineSoft)
}
