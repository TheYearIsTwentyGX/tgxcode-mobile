package com.twentygx.tgxcode.ui.conversation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.waitingFor
import com.twentygx.tgxcode.data.model.SudoAsk
import com.twentygx.tgxcode.data.model.SudoCardMode
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** The desktop's `clip(ask.context, 1200)`. */
private const val CONTEXT_CLIP = 1200

/** A Bash command this long or shorter starts open; a longer one starts folded. */
private const val CONTEXT_OPEN_UP_TO = 240

/** Everything a sudo card needs from above, so each surface passes one thing. */
class SudoActions(
    /** Approve or read-only, from `sudo.allowRemote` — [com.twentygx.tgxcode.data.model.sudoCardMode]. */
    val mode: (SudoAsk) -> SudoCardMode,
    /** Ask ids with an answer in flight. */
    val answering: Set<String>,
    val onApprove: (SudoAsk, String) -> Unit,
    val onDeny: (SudoAsk) -> Unit,
)

/**
 * The password, in Compose state and nowhere else, until it is sent.
 *
 * Held with `remember`, **never `rememberSaveable`**: a saveable value goes into the
 * activity's saved-state Bundle, which the system keeps across process death — on disk.
 * [submit] reads it once into the request and empties the field in the same step, as
 * `web/transcript/sudo.js` does; [clear] runs when the card leaves the screen.
 */
@Stable
class SudoPassword {
    var text by mutableStateOf("")

    /** Hand the password to [send] and empty the field first. False when there is none. */
    fun submit(send: (String) -> Unit): Boolean {
        val password = text
        if (password.isEmpty()) return false
        text = ""
        send(password)
        return true
    }

    fun clear() {
        text = ""
    }

    /** Never the password, should this end up in a log or a crash report. */
    override fun toString(): String = "SudoPassword(${if (text.isEmpty()) "empty" else "•••"})"
}

/**
 * A `sudo -A` waiting on your password: `web/transcript/sudo.js`'s card.
 *
 * It shows what was measured, not what the agent said — the waiting sudo's own command
 * line, who it runs as, where — and the whole Bash command it is part of, because sudo
 * remembers the password for the rest of that command. With `sudo.allowRemote` on it
 * takes the password; off, it is read-only, says "Approve at the desktop", and can
 * still deny, which grants nothing.
 *
 * While it is on screen the window is `FLAG_SECURE` and hidden from autofill
 * ([SecureWhileShown]); the keyboard is asked not to learn from the field.
 *
 * @param sessionTitle drawn as a fact row when the card is away from its conversation.
 * @param compact a row on a board: the Bash command starts folded and nothing scrolls.
 */
@Composable
fun SudoCard(
    ask: SudoAsk,
    actions: SudoActions,
    modifier: Modifier = Modifier,
    sessionTitle: String? = null,
    nowMillis: Long? = null,
    compact: Boolean = false,
) {
    SecureWhileShown()

    val colors = tgx
    val yellow = colors.yellow
    val mode = actions.mode(ask)
    val busy = ask.id in actions.answering
    val password = remember(ask.id) { SudoPassword() }
    DisposableEffect(password) { onDispose { password.clear() } }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.s3, RoundedCornerShape(Radius.Lg))
            .drawBehind { drawRect(yellow, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .then(if (compact) Modifier else Modifier.heightIn(max = screenHeight * 0.62f))
            .padding(Metrics.CardPadding),
    ) {
        Row(Modifier.padding(bottom = 10.dp)) {
            Text("SUDO — NEEDS YOUR PASSWORD", style = TgxType.askKind, color = yellow, modifier = Modifier.weight(1f))
            if (ask.runsShell) Text("RUNS A SHELL", style = TgxType.askKind, color = colors.red)
        }

        Column(
            (if (compact) Modifier else Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SudoBody(ask, sessionTitle, compact)
        }

        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (mode) {
                SudoCardMode.Approve -> {
                    val approve = { password.submit { actions.onApprove(ask, it) } }
                    NoLearningTextInput {
                        OutlinedTextField(
                            value = password.text,
                            onValueChange = { password.text = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
                            placeholder = { Text("Your password") },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { approve() }),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    AskButton(
                        "Approve",
                        { approve() },
                        kind = AskButtonKind.Affirmative,
                        enabled = !busy && password.text.isNotEmpty(),
                    )
                }
                SudoCardMode.ReadOnly -> Text(
                    "Approve at the desktop. Answering sudo from a phone is off in Settings › Sudo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            AskButton(
                "Deny",
                {
                    password.clear()
                    actions.onDeny(ask)
                },
                kind = AskButtonKind.Destructive,
                enabled = !busy,
            )
            if (nowMillis != null) {
                // Counts up: sudo waits as long as the helper does, and has no deadline.
                Text(
                    waitingFor(ask.askedAt, java.time.Instant.ofEpochMilli(nowMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SudoBody(ask: SudoAsk, sessionTitle: String?, compact: Boolean) {
    val colors = tgx
    // What sudo was given, exactly — read from the sudo process, not from the agent.
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(Radius.Sm))
            .padding(10.dp),
    ) {
        Text(ask.line, style = TgxType.mono125, color = MaterialTheme.colorScheme.onSurface, softWrap = true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Fact("As", ask.runsAs)
        Fact("In", ask.where)
        sessionTitle?.takeIf { it.isNotBlank() }?.let { Fact("Session", it) }
    }

    if (ask.runsShell) {
        Text(
            "This starts a shell as ${ask.runsAs}: approving it approves whatever that shell runs, " +
                "which is not shown here.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.red,
        )
    }

    // The cached credential covers the rest of the Bash call, so that is part of what
    // is being approved.
    ask.wholeCommand?.let { whole ->
        var open by remember(ask.id) { mutableStateOf(!compact && whole.length <= CONTEXT_OPEN_UP_TO) }
        Text(
            "Part of this Bash command. sudo remembers the password for the rest of it, " +
                "so any later sudo in it runs without asking. " + if (open) "Hide ▴" else "Show ▾",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.TouchTarget)
                .clickable { open = !open }
                .padding(vertical = 6.dp),
        )
        if (open) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(Radius.Sm))
                    .padding(10.dp),
            ) {
                Text(
                    if (whole.length > CONTEXT_CLIP) whole.take(CONTEXT_CLIP - 1) + "…" else whole,
                    style = TgxType.mono125,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (ask.attempt > 1) {
        Text(
            "That password was not accepted. Try ${ask.attempt} of sudo's usual 3.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.yellow,
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tgx.textFaint, modifier = Modifier.width(64.dp))
        Text(value, style = TgxType.mono12, color = tgx.textMuted, modifier = Modifier.weight(1f))
    }
}

/**
 * Asks the keyboard not to learn from what is typed here: `IME_FLAG_NO_PERSONALIZED_LEARNING`.
 *
 * `KeyboardOptions` has no switch for it — Compose's `EditorInfo` mapping sets the
 * password variation and, with `autoCorrectEnabled = false`, no auto-correct, but never
 * this flag — so it is added to the request on its way to the IME, the way the
 * platform-input interceptor's own documentation does it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoLearningTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                PlatformTextInputMethodRequest { info: EditorInfo ->
                    request.createInputConnection(info).also {
                        info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
                },
            )
        },
        content = content,
    )
}

/**
 * `FLAG_SECURE` on the window, and the window's views out of autofill, for as long as
 * any sudo card is composed — counted, because the board can draw several at once.
 *
 * Secure keeps the card out of screenshots, screen recording and the recents thumbnail.
 * Excluding the view from autofill means the password field is never in the structure
 * an autofill service is shown, so there is nothing for it to offer to save; and the
 * session is cancelled as the card goes, which ends one without a save prompt.
 */
@Composable
fun SecureWhileShown() {
    val view = LocalView.current
    val autofill = LocalAutofillManager.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        SecureHolds.hold(window, view)
        onDispose {
            autofill?.cancel()
            SecureHolds.release(window, view)
        }
    }
}

private object SecureHolds {
    private var count = 0
    private var autofillBefore = View.IMPORTANT_FOR_AUTOFILL_AUTO

    fun hold(window: Window?, view: View) {
        if (count++ == 0) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            autofillBefore = view.importantForAutofill
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    fun release(window: Window?, view: View) {
        if (count == 0) return
        if (--count == 0) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            view.importantForAutofill = autofillBefore
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
