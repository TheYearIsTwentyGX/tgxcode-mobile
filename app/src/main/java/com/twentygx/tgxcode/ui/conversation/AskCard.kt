package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.waitingFor
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.AskKind
import com.twentygx.tgxcode.data.model.AskOutcome
import com.twentygx.tgxcode.data.model.Decision
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.QuestionSpec
import com.twentygx.tgxcode.ui.common.AskButton
import com.twentygx.tgxcode.ui.common.AskButtonKind
import com.twentygx.tgxcode.ui.markdown.MarkdownText
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val OTHER = "__other__"

/**
 * How much of the screen an ask card may take.
 *
 * It has to be capped, and the buttons have to live *outside* whatever scrolls.
 * A question ask carries an arbitrary number of questions, each with an arbitrary
 * number of options — left to grow it pushes the Send button, and then the whole
 * composer, off the bottom of the screen, and there is no way to answer the thing
 * the card exists to answer.
 */
private const val MAX_CARD_FRACTION = 0.62f

/**
 * The pinned slot above the composer. One card at a time, ever.
 *
 * @param nowMillis ticked once every 30s from a single shared timer, rather than a
 *   timer per card — the number is measured in minutes, so a minute's resolution is
 *   plenty and one coroutine is enough.
 */
@Composable
fun AskSlot(
    ask: Ask?,
    lastOutcome: String?,
    nowMillis: Long,
    busy: Boolean,
    onAnswer: (PermissionRequestBody) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        ask != null -> AskCard(ask, nowMillis, busy, onAnswer, modifier)
        lastOutcome != null -> Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .background(tgx.s2, RoundedCornerShape(Radius.Md))
                .padding(12.dp),
        ) {
            Text(
                AskOutcome.word(lastOutcome),
                style = MaterialTheme.typography.bodySmall,
                color = tgx.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AskCard(
    ask: Ask,
    nowMillis: Long,
    busy: Boolean,
    onAnswer: (PermissionRequestBody) -> Unit,
    modifier: Modifier = Modifier,
) {
    val yellow = tgx.yellow
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // State lives here, above the scroller, so the pinned action buttons can read
    // what the scrollable body collected.
    var planNote by remember(ask.requestId) { mutableStateOf("") }
    val questions = remember(ask.requestId) { ask.questions() }
    val picks = remember(ask.requestId) { mutableStateMapOf<Int, Set<String>>() }
    val otherText = remember(ask.requestId) { mutableStateMapOf<Int, String>() }

    fun resolved(index: Int): List<String> =
        picks[index].orEmpty().mapNotNull { label ->
            if (label == OTHER) otherText[index]?.trim()?.takeIf { it.isNotBlank() } else label
        }

    Column(
        modifier
            .fillMaxWidth()
            .padding(10.dp)
            .background(tgx.s3, RoundedCornerShape(Radius.Lg))
            .drawBehind { drawRect(yellow, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .heightIn(max = screenHeight * MAX_CARD_FRACTION)
            .padding(Metrics.CardPadding),
    ) {
        Text(
            when (ask.askKind) {
                AskKind.Plan -> "PLAN"
                AskKind.Question -> "QUESTION"
                else -> "PERMISSION NEEDED"
            },
            style = TgxType.askKind,
            color = yellow,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        // The body scrolls; `fill = false` lets a short card stay short instead of
        // stretching to the cap.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (ask.askKind) {
                AskKind.Plan -> PlanBody(ask, planNote) { planNote = it }
                AskKind.Question -> QuestionBody(
                    questions = questions,
                    picks = picks,
                    otherText = otherText,
                    onPick = { index, label, multi ->
                        val current = picks[index].orEmpty().toMutableSet()
                        if (multi) {
                            if (!current.add(label)) current.remove(label)
                        } else {
                            current.clear()
                            current.add(label)
                        }
                        picks[index] = current
                    },
                    onOther = { index, text, multi ->
                        otherText[index] = text
                        if (text.isNotBlank()) {
                            val current = picks[index].orEmpty().toMutableSet()
                            if (!multi) current.clear()
                            current.add(OTHER)
                            picks[index] = current
                        }
                    },
                )
                else -> ToolBody(ask)
            }
        }

        // Pinned below the scroller, so an answer is always one tap away no matter
        // how long the plan or the question list is.
        Column(
            Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (ask.askKind) {
                AskKind.Plan -> PlanActions(ask, planNote, busy, onAnswer)
                AskKind.Question -> QuestionActions(
                    ask = ask,
                    questions = questions,
                    resolve = ::resolved,
                    busy = busy,
                    onAnswer = onAnswer,
                )
                else -> ToolActions(ask, busy, onAnswer)
            }

            // Counts up, not down. Asks do not expire, and a clock running while you
            // read a plan would be the wrong kind of pressure.
            Text(
                waitingFor(ask.askedAt, java.time.Instant.ofEpochMilli(nowMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------- tool

@Composable
private fun ColumnScope.ToolBody(ask: Ask) {
    Text(ask.toolLabel, style = MaterialTheme.typography.bodyLarge)
    ask.reason?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = tgx.textMuted)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(Radius.Sm))
            .padding(10.dp),
    ) {
        Text(
            toolBody(ask.input),
            style = TgxType.mono125,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.ToolActions(
    ask: Ask,
    busy: Boolean,
    onAnswer: (PermissionRequestBody) -> Unit,
) {
    AskButton(
        "Allow",
        { onAnswer(PermissionRequestBody(ask.requestId, Decision.ALLOW)) },
        kind = AskButtonKind.Affirmative,
        enabled = !busy,
    )
    AskButton(
        "Allow ${ask.toolLabel} all session",
        { onAnswer(PermissionRequestBody(ask.requestId, Decision.ALLOW_ALWAYS)) },
        enabled = !busy,
    )
    AskButton(
        "Deny",
        { onAnswer(PermissionRequestBody(ask.requestId, Decision.DENY)) },
        kind = AskButtonKind.Destructive,
        enabled = !busy,
    )
}

// ---------------------------------------------------------------------- plan

@Composable
private fun ColumnScope.PlanBody(ask: Ask, note: String, onNote: (String) -> Unit) {
    Text("Ready to start", style = MaterialTheme.typography.bodyLarge)
    MarkdownText(ask.planMarkdown.orEmpty(), Modifier.fillMaxWidth())
    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        placeholder = { Text("A note to send with your answer (optional)") },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * **`mode` rides along with the approval, and it has to.** The session is *in* plan
 * mode while this card is up, so approving without changing it would agree to the
 * work and then refuse every edit in it.
 *
 * `feedback` on a deny is where the value is: it comes back to the model as the
 * tool's error, so "too broad, do the parser first" produces a *different* plan
 * rather than the same one again.
 */
@Composable
private fun ColumnScope.PlanActions(
    ask: Ask,
    note: String,
    busy: Boolean,
    onAnswer: (PermissionRequestBody) -> Unit,
) {
    AskButton(
        "Approve",
        { onAnswer(PermissionRequestBody(ask.requestId, Decision.ALLOW, mode = "auto")) },
        kind = AskButtonKind.Affirmative,
        enabled = !busy,
    )
    AskButton(
        "Approve — auto-accept edits",
        { onAnswer(PermissionRequestBody(ask.requestId, Decision.ALLOW, mode = "acceptEdits")) },
        enabled = !busy,
    )
    AskButton(
        "Approve with the note above",
        {
            onAnswer(
                PermissionRequestBody(
                    ask.requestId,
                    Decision.ALLOW,
                    mode = "auto",
                    feedback = note.trim().ifBlank { null },
                ),
            )
        },
        enabled = !busy,
    )
    AskButton(
        "Keep planning",
        {
            onAnswer(
                PermissionRequestBody(
                    ask.requestId,
                    Decision.DENY,
                    feedback = note.trim().ifBlank { null },
                ),
            )
        },
        kind = AskButtonKind.Destructive,
        enabled = !busy,
    )
}

// ------------------------------------------------------------------ question

/**
 * Every question gets an **Other** row with a free-text field, because a question you
 * cannot answer honestly is worse than no question.
 */
@Composable
private fun ColumnScope.QuestionBody(
    questions: List<QuestionSpec>,
    picks: Map<Int, Set<String>>,
    otherText: Map<Int, String>,
    onPick: (index: Int, label: String, multi: Boolean) -> Unit,
    onOther: (index: Int, text: String, multi: Boolean) -> Unit,
) {
    questions.forEachIndexed { index, spec ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                spec.question,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            (spec.options.map { it.label to it.description } + (OTHER to null)).forEach { (label, desc) ->
                val selected = picks[index]?.contains(label) == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Metrics.TouchTarget)
                        .selectable(
                            selected = selected,
                            onClick = { onPick(index, label, spec.multiSelect) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (spec.multiSelect) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onPick(index, label, true) },
                        )
                    } else {
                        RadioButton(
                            selected = selected,
                            onClick = { onPick(index, label, false) },
                        )
                    }
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            if (label == OTHER) "Other" else label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        desc?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = tgx.textMuted,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = otherText[index].orEmpty(),
                onValueChange = { onOther(index, it, spec.multiSelect) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
                placeholder = { Text("Something else…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ColumnScope.QuestionActions(
    ask: Ask,
    questions: List<QuestionSpec>,
    resolve: (Int) -> List<String>,
    busy: Boolean,
    onAnswer: (PermissionRequestBody) -> Unit,
) {
    // Claude asked all of them; leaving some to guesswork is what this card exists
    // to avoid.
    val complete = questions.indices.all { resolve(it).isNotEmpty() }

    AskButton(
        if (complete) "Send" else "Answer every question to send",
        {
            // Keyed by the EXACT question text: the bridge drops keys that name no
            // question, so an approximation silently answers nothing. Values are a
            // string for single-select and an array for multi-select, which is why
            // this is JsonElement rather than String.
            val answers = buildMap<String, JsonElement> {
                questions.forEachIndexed { index, spec ->
                    val values = resolve(index)
                    if (values.isEmpty()) return@forEachIndexed
                    put(
                        spec.question,
                        if (spec.multiSelect) {
                            JsonArray(values.map { JsonPrimitive(it) })
                        } else {
                            JsonPrimitive(values.first())
                        },
                    )
                }
            }
            onAnswer(PermissionRequestBody(ask.requestId, Decision.ALLOW, answers = answers))
        },
        kind = AskButtonKind.Affirmative,
        enabled = !busy && complete,
    )
    AskButton(
        "Skip",
        { onAnswer(PermissionRequestBody(ask.requestId, Decision.DENY)) },
        kind = AskButtonKind.Destructive,
        enabled = !busy,
    )
}
