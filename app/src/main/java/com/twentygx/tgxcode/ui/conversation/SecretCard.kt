package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.waitingFor
import com.twentygx.tgxcode.data.model.SecretAsk
import com.twentygx.tgxcode.data.model.SecretCardMode
import com.twentygx.tgxcode.data.model.secretCardHint
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** Everything a secret card needs from above, so each surface passes one thing. */
class SecretActions(
    /** Field or read-only — [com.twentygx.tgxcode.data.model.secretCardMode]. */
    val mode: (SecretAsk) -> SecretCardMode,
    /** The host a value would be sent through, for the line under the field. */
    val via: () -> String?,
    /** Ask ids with an answer in flight. */
    val answering: Set<String>,
    val onSave: (SecretAsk, String) -> Unit,
    val onDeny: (SecretAsk) -> Unit,
)

/**
 * The value, in Compose state and nowhere else, until it is sent.
 *
 * Held with `remember`, **never `rememberSaveable`**: a saveable value goes into the
 * activity's saved-state Bundle, which the system keeps across process death — on disk.
 * [submit] reads it once into the request and empties the field in the same step, so a
 * failed send means typing it again; [clear] runs when the card leaves the screen.
 */
@Stable
class SecretValue {
    var text by mutableStateOf("")

    /** Hand the value to [send] and empty the field first. False when there is none. */
    fun submit(send: (String) -> Unit): Boolean {
        val value = text
        if (value.isEmpty()) return false
        text = ""
        send(value)
        return true
    }

    fun clear() {
        text = ""
    }

    /** Never the value, should this end up in a log or a crash report. */
    override fun toString(): String = "SecretValue(${if (text.isEmpty()) "empty" else "•••"})"
}

/**
 * An agent asking for a secret by name: `web/transcript/secret.js`'s card.
 *
 * With `secrets.allowRemote` on it takes the value; off, which is the default, it is
 * read-only, says where to turn it on, and can still deny, which grants nothing. The
 * value is single-line, so something like a PEM key is one for the desk.
 *
 * While it is on screen the window is `FLAG_SECURE` and hidden from autofill
 * ([SecureWhileShown], shared with the sudo card); the keyboard is asked not to learn
 * from the field.
 *
 * @param compact a row on a board.
 */
@Composable
fun SecretCard(
    ask: SecretAsk,
    actions: SecretActions,
    modifier: Modifier = Modifier,
    nowMillis: Long? = null,
    compact: Boolean = false,
) {
    SecureWhileShown()

    val colors = tgx
    val yellow = colors.yellow
    val mode = actions.mode(ask)
    val busy = ask.id in actions.answering
    val value = remember(ask.id) { SecretValue() }
    DisposableEffect(value) { onDispose { value.clear() } }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.s3, RoundedCornerShape(Radius.Lg))
            .drawBehind { drawRect(yellow, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .then(if (compact) Modifier else Modifier.heightIn(max = screenHeight * 0.62f))
            .padding(Metrics.CardPadding),
    ) {
        Text(
            "SECRET — THE AGENT ASKS FOR A VALUE",
            style = TgxType.askKind,
            color = yellow,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Column(
            (if (compact) Modifier else Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(Radius.Sm))
                    .padding(10.dp),
            ) {
                Text(ask.name, style = TgxType.mono125, color = MaterialTheme.colorScheme.onSurface)
            }

            // The agent's own words, and it can write anything, so they are said to be its.
            if (ask.why.isNotBlank()) {
                Text(
                    "The agent says: ${ask.why}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = if (compact) 3 else Int.MAX_VALUE,
                )
            }

            Text(
                "Saved for ${ask.project ?: "this project"}. The agent uses it as $${ask.name} in Bash " +
                    "and never sees it; it is not written to the conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }

        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == SecretCardMode.Enter) {
                val save = { value.submit { actions.onSave(ask, it) } }
                NoLearningTextInput {
                    OutlinedTextField(
                        value = value.text,
                        onValueChange = { value.text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
                        placeholder = { Text("Value") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
                actions.via()?.let { host ->
                    Text(
                        "Sent over an encrypted connection via $host.",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textFaint,
                    )
                }
                AskButton(
                    "Save",
                    { save() },
                    kind = AskButtonKind.Affirmative,
                    enabled = !busy && value.text.isNotEmpty(),
                )
            } else {
                secretCardHint(ask, mode)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            AskButton(
                "Deny",
                {
                    value.clear()
                    actions.onDeny(ask)
                },
                kind = AskButtonKind.Destructive,
                enabled = !busy,
            )
            if (nowMillis != null) {
                // Counts up: an ask waits as long as the agent does, and has no deadline.
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
