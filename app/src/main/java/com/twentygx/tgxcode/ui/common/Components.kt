package com.twentygx.tgxcode.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.model.RunnerState
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.data.repo.ConnState
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Green / yellow / red, in the top bar. Small, but it is the honesty indicator.
 *
 * [ConnState.Polling] is drawn as a **ring** rather than a filled dot, matching `/m`.
 * It is a different kind of connected — working, but by asking rather than by being
 * told — and painting it green would make the two-second lag that comes with it read
 * as a bug. It is too small to explain itself, so [DegradedBanner] does that.
 */
@Composable
fun ConnectionDot(state: ConnState, modifier: Modifier = Modifier) {
    val colors = tgx
    if (state == ConnState.Polling) {
        // `primary` is `--blue`, same token the remote banner draws itself in.
        Box(modifier.size(8.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        return
    }
    val color = when (state) {
        ConnState.Live -> colors.green
        ConnState.Connecting -> colors.yellow
        else -> colors.red
    }
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** How a session row reads at a glance. Precedence matters more than the colours. */
enum class RowState { Ask, Error, Busy, Live, Idle }

/**
 * @param askingSessions ids the board says are blocked on an ask.
 *
 * This parameter exists because of a quirk worth knowing. `mobile.js:525` computes
 * its ask state from `runner.pendingPermission` on a session summary — but
 * `/api/sessions` only ever attaches `{state, activity, queued}`
 * (`bridge/server.js:633`), so `pendingPermission` is never there and that branch
 * can never fire. The web client's Sessions rows silently never show the ask dot.
 *
 * The board *does* carry `ask`, and this app already holds it in memory for the tab
 * badge, so cross-referencing costs nothing and makes the dot mean what it was
 * always meant to mean.
 */
fun rowStateOf(session: SessionSummary, askingSessions: Set<String> = emptySet()): RowState {
    val runner = session.runner
    return when {
        // An ask outranks everything: it is the only state that needs a human.
        session.sessionId in askingSessions -> RowState.Ask
        runner?.state == "error" -> RowState.Error
        runner?.state == "busy" || runner?.state == "starting" -> RowState.Busy
        session.live?.running == true -> RowState.Live
        else -> RowState.Idle
    }
}

@Composable
fun StateDot(state: RowState, modifier: Modifier = Modifier) {
    val colors = tgx
    val color = when (state) {
        RowState.Ask -> colors.yellow
        RowState.Error -> colors.red
        RowState.Busy -> colors.yellow
        RowState.Live -> colors.green
        RowState.Idle -> colors.textFaint
    }
    Box(modifier.size(8.dp).background(color, CircleShape))
}

@Composable
fun runnerStateColor(state: RunnerState): Color = when (state) {
    RunnerState.Error -> tgx.red
    RunnerState.Busy, RunnerState.Starting -> tgx.yellow
    RunnerState.Idle -> tgx.green
    else -> tgx.textFaint
}

enum class AskButtonKind { Affirmative, Neutral, Destructive }

/**
 * Full width, 48dp minimum, and the destructive one goes last — never adjacent to
 * the affirmative one. Approving a permission one-handed in a hurry is the thing
 * this whole surface exists to do, so the buttons are bigger than they look like
 * they need to be.
 */
@Composable
fun AskButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: AskButtonKind = AskButtonKind.Neutral,
    enabled: Boolean = true,
) {
    val colors = tgx
    val shared = modifier
        .fillMaxWidth()
        .heightIn(min = Metrics.AskButtonHeight)

    when (kind) {
        AskButtonKind.Destructive -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = Pill,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.red),
            modifier = shared,
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }

        else -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = Pill,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (kind == AskButtonKind.Affirmative) colors.blueDeep else colors.s5,
                contentColor = if (kind == AskButtonKind.Affirmative) Color.White
                else MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = colors.s4,
                disabledContentColor = colors.textFaint,
            ),
            modifier = shared,
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Bold headline, then the detail. Centred, used wherever a list is empty. */
@Composable
fun EmptyState(
    headline: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            headline,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = tgx.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** "Connected remotely — host". Raised when the bridge says we are not local. */
@Composable
fun RemoteBanner(host: String?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(tgx.blueTint)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(
            "Connected remotely — ${host ?: "this machine"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Shown instead of [RemoteBanner] when the network will not carry a live stream.
 *
 * Instead of, not as well as: polling only ever happens remotely, and the comment in
 * `TgxCodeRoot` is explicit that exactly one element may own the status-bar inset.
 *
 * It exists because the alternative is worse than an ugly banner. Everything still
 * works in this mode, just a couple of seconds behind — and an unexplained two-second
 * lag reads as a broken app, which is the one impression an honesty indicator is
 * supposed to prevent.
 */
@Composable
fun DegradedBanner(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(tgx.blueTint)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        Text(
            "This network will not carry a live stream — checking every few seconds",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
