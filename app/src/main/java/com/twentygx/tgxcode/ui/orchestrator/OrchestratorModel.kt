package com.twentygx.tgxcode.ui.orchestrator

import com.twentygx.tgxcode.core.toolDisplayText
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.BriefDecision
import com.twentygx.tgxcode.data.model.InboxItem
import com.twentygx.tgxcode.data.model.OrchestratorPayload
import com.twentygx.tgxcode.data.model.OrchestratorSettings
import com.twentygx.tgxcode.data.model.OrchestratorWorker
import com.twentygx.tgxcode.data.model.PendingSpawn
import com.twentygx.tgxcode.data.model.UsageStop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The orchestrator page's reading of a payload — `web/orchestrator-model.js`, ported.
 *
 * Pure and free of Compose, so the rules that decide what is drawn as needing you, how
 * far a worker has got and what lands in which order are tested rather than eyeballed,
 * and say the same thing the desktop says about the same payload.
 */

/** A worker ask's inbox kind, by what `pending` calls it. */
private val ASK_KINDS = mapOf("plan" to "plan", "permission" to "tool", "ask" to "question")
private val REPORT_KINDS = setOf("update", "done", "turn", "question")
private val APPROVED = setOf("allow", "allow-always", "plan-approved", "plan-approved-note")
private val PR_TROUBLE = setOf("checks-failed", "changes", "conflicting")

/** How long a worker's ask waits on a stalled orchestrator before it becomes yours. */
const val STUCK_MS = 180_000L

/** A task the orchestrator has not got to in this long stops reading as fresh. */
const val TASK_STALE_MS = 15 * 60_000L

private fun mine(inbox: List<InboxItem>, id: String) = inbox.filter { it.workerId == id }
private val InboxItem.settled: Boolean get() = status == "resolved" || status == "stale"

/** `running`, `idle`, `error`, `closed` or `stopped`. */
fun workerStateWord(w: OrchestratorWorker): String = when {
    w.closedAt != null -> "closed"
    w.state == "busy" || w.state == "starting" -> "running"
    w.state == "idle" || w.state == "error" -> w.state
    else -> "stopped"
}

/** What a worker is blocked on, and the inbox item that carries it if it is still there. */
data class Blocked(val kind: String, val item: InboxItem?)

fun blockedOn(w: OrchestratorWorker, inbox: List<InboxItem>): Blocked? {
    if (w.closedAt != null || w.pending == null) return null
    val item = mine(inbox, w.id).firstOrNull {
        ASK_KINDS[it.kind] == w.pending && it.requestId != null && !it.settled
    }
    return Blocked(w.pending, item)
}

/** Its newest report — the inbox is newest first. */
fun lastReport(w: OrchestratorWorker, inbox: List<InboxItem>): InboxItem? =
    mine(inbox, w.id).firstOrNull { it.kind in REPORT_KINDS }

fun openDecisionFor(p: OrchestratorPayload, workerId: String): BriefDecision? =
    p.summary?.decisions?.firstOrNull { it.workerId == workerId && it.answeredAt == null }

// ------------------------------------------------------------------ time

/** `now`, `4m`, `3h`, `2d`. */
fun shortAge(ms: Long): String {
    val m = (ms.coerceAtLeast(0) / 60_000)
    if (m < 1) return "now"
    if (m < 60) return "${m}m"
    val h = m / 60
    if (h < 24) return "${h}h"
    return "${h / 24}d"
}

/** `just now`, or `4m ago`. */
fun agoWords(at: Long, now: Long): String {
    val age = shortAge(now - at)
    return if (age == "now") "just now" else "$age ago"
}

/** `4:00 PM`, or `Fri 4:00 PM` when not today, plus how far off it is. */
fun clockAt(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    val day = if (at.toLocalDate() == today) "" else at.format(DateTimeFormatter.ofPattern("EEE ", Locale.US))
    val minutes = (ms - now) / 60_000
    val inWords = when {
        minutes <= 0 -> ""
        minutes < 90 -> " (in ${minutes}m)"
        else -> " (in ${minutes / 60}h)"
    }
    return "$day$time$inWords"
}

fun clip(text: String, n: Int): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length > n) flat.take(n - 1).trimEnd() + "…" else flat
}

fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

// ------------------------------------------------------------- needs you

sealed interface NeedItem {
    val key: String

    data class Paused(val reason: String, val resumeAt: Long?) : NeedItem {
        override val key = "paused"
    }

    /** The orchestrator itself is waiting on an ask — answered in its conversation. */
    data class OrchestratorAsk(val ask: Ask) : NeedItem {
        override val key = "ask"
    }

    data class Decision(val decision: BriefDecision) : NeedItem {
        override val key = "d:${decision.id}"
    }

    /** A worker's ask nobody is getting to: the orchestrator is paused or not working. */
    data class Stuck(val worker: OrchestratorWorker, val item: InboxItem) : NeedItem {
        override val key = "s:${worker.id}"
    }

    data class Failed(val worker: OrchestratorWorker) : NeedItem {
        override val key = "e:${worker.id}"
    }
}

/**
 * @param orchState the orchestrator's own runner state, null when it has no process.
 * @param orchAsk the ask the orchestrator itself is waiting on, if any.
 */
fun needsYou(
    p: OrchestratorPayload,
    orchState: String?,
    orchAsk: Ask?,
    now: Long,
    stuckMs: Long = STUCK_MS,
): List<NeedItem> {
    val out = mutableListOf<NeedItem>()
    p.paused?.let { out += NeedItem.Paused(it.reason, it.resumeAt) }
    orchAsk?.let { out += NeedItem.OrchestratorAsk(it) }
    p.summary?.decisions.orEmpty()
        .sortedWith(compareBy<BriefDecision> { it.answeredAt != null }.thenBy { it.at })
        .forEach { out += NeedItem.Decision(it) }
    val orchWorking = (orchState == "busy" || orchState == "starting") && orchAsk == null
    for (w in p.workers) {
        if (w.closedAt != null) continue
        val b = blockedOn(w, p.inbox)
        val item = b?.item
        if (item != null && (p.paused != null || !orchWorking) && now - (item.at.takeIf { it > 0 } ?: now) >= stuckMs) {
            out += NeedItem.Stuck(w, item)
        }
        if (workerStateWord(w) == "error") out += NeedItem.Failed(w)
    }
    return out
}

/** What a "needs you" heading counts: everything but questions already answered. */
fun needsCount(items: List<NeedItem>): Int =
    items.count { !(it is NeedItem.Decision && it.decision.answeredAt != null) }

// -------------------------------------------------------------- progress

enum class Segment { Merged, Fail, Wait, Run, Pr, Done, Idle, Queued }

data class Progress(
    val segments: List<Pair<String, Segment>>,
    val total: Int,
    val finished: Int,
    val running: Int,
    val max: Int,
    val queued: Int,
)

fun progress(p: OrchestratorPayload): Progress {
    val workers = p.workers.sortedBy { it.spawnedAt }
    fun running(w: OrchestratorWorker) = w.closedAt == null && (w.state == "busy" || w.state == "starting")
    fun finished(w: OrchestratorWorker) = w.closedAt != null || w.pr != null ||
        (mine(p.inbox, w.id).any { it.kind == "done" } && !running(w) && w.pending == null)
    val segments = workers.map { w ->
        val status = w.pr?.status
        val seg = when {
            status == "merged" -> Segment.Merged
            status != null && status in PR_TROUBLE -> Segment.Fail
            w.pending != null && w.closedAt == null -> Segment.Wait
            running(w) -> Segment.Run
            w.pr != null -> Segment.Pr
            finished(w) -> Segment.Done
            w.state == "error" -> Segment.Fail
            else -> Segment.Idle
        }
        (w.title ?: "untitled worker") to seg
    } + p.pendingSpawns.map { (it.title ?: "untitled") to Segment.Queued }
    return Progress(
        segments = segments,
        total = workers.size + p.pendingSpawns.size,
        finished = workers.count(::finished),
        running = workers.count(::running),
        max = p.settings.maxRunning,
        queued = p.pendingSpawns.size,
    )
}

fun segmentWord(seg: Segment, prStatus: String?): String = when (seg) {
    Segment.Merged -> "merged"
    Segment.Fail -> "needs a fix"
    Segment.Done -> "closed"
    Segment.Run -> "working"
    Segment.Wait -> "waiting for an answer"
    Segment.Idle -> "idle"
    Segment.Queued -> "queued"
    Segment.Pr -> "PR ${prWord(prStatus)}"
}

/** The page's PR words, which differ from the rail's in a couple of places. */
fun prWord(status: String?): String = when (status) {
    "open" -> "open"
    "approved" -> "approved"
    "changes" -> "changes asked"
    "checks-failed" -> "checks failed"
    "checks-pending" -> "checks running"
    "conflicting" -> "conflicts"
    "draft" -> "draft"
    "merged" -> "merged"
    "closed" -> "closed"
    else -> ""
}

// ------------------------------------------------------------ merge order

data class MergeRow(
    val n: Int,
    val number: Int,
    val repo: String?,
    val workerId: String?,
    val title: String?,
    val url: String?,
    val status: String?,
)

data class MergeOrder(
    val rows: List<MergeRow>,
    /** Open workers with no PR yet — they join the order once they open one. */
    val rest: List<OrchestratorWorker>,
    val fromBrief: Boolean,
)

fun mergeOrder(p: OrchestratorPayload): MergeOrder {
    val workers = p.workers.sortedBy { it.spawnedAt }
    val brief = p.summary?.mergeOrder.orEmpty()
    val fromBrief = brief.isNotEmpty()
    val picked: List<Pair<Int, OrchestratorWorker?>> = if (fromBrief) {
        brief.map { n -> n to workers.firstOrNull { it.pr?.number == n } }
    } else {
        workers.mapNotNull { w -> w.pr?.let { it.number to w } }
    }
    val seen = mutableSetOf<String>()
    val rows = mutableListOf<MergeRow>()
    for ((number, w) in picked) {
        val pr = w?.pr
        val key = "${pr?.repo.orEmpty()}#$number"
        if (!seen.add(key)) continue
        rows += MergeRow(
            n = rows.size + 1,
            number = number,
            repo = pr?.repo,
            workerId = w?.id,
            title = w?.let { pr?.title ?: it.title ?: "#$number" },
            url = pr?.url,
            status = pr?.status,
        )
    }
    val rest = workers.filter { it.closedAt == null && it.pr == null }
    return MergeOrder(rows, rest, fromBrief)
}

// ---------------------------------------------------------------- stages

val STAGES = listOf("Plan", "Approved", "Building", "Done", "PR", "Merged")

enum class StepState { Done, Now, Todo, Skipped, Unknown }

private data class Timeline(val plan: StepState, val approved: StepState, val done: StepState)

private fun timeline(w: OrchestratorWorker, inbox: List<InboxItem>): Timeline {
    val items = mine(inbox, w.id)
    val plans = items.filter { it.kind == "plan" }
    val firstPlan = plans.lastOrNull()
    val lastPlan = plans.firstOrNull()
    val approved = lastPlan?.takeIf { it.settled && it.outcome in APPROVED }
    val doneItem = items.firstOrNull { it.kind == "done" }
    val finished = doneItem != null || w.pr != null || w.closedAt != null
    val planWaiting = w.pending == "plan" || (lastPlan != null && !lastPlan.settled && approved == null)
    val trimmed = inbox.size >= 100 && (inbox.lastOrNull()?.at ?: 0) > w.spawnedAt
    val running = w.closedAt == null && (w.state == "busy" || w.state == "starting")
    val plan: StepState
    val appr: StepState
    when {
        firstPlan != null -> {
            plan = StepState.Done
            appr = when {
                approved != null -> StepState.Done
                planWaiting -> StepState.Now
                finished -> StepState.Unknown
                else -> StepState.Todo
            }
        }
        w.pending == "plan" -> {
            plan = StepState.Now
            appr = StepState.Todo
        }
        else -> {
            plan = if (trimmed) StepState.Unknown else StepState.Skipped
            appr = plan
        }
    }
    val working = !planWaiting && !finished && running
    val done = when {
        doneItem != null || w.closedAt != null -> StepState.Done
        w.pr != null -> StepState.Unknown
        working -> StepState.Now
        else -> StepState.Todo
    }
    return Timeline(plan, appr, done)
}

/** `wait`, `you`, `error`, `run`, `merged`, `fail`, `pr`, `done`, `idle` or `stopped`. */
enum class Tone { Wait, You, Error, Run, Merged, Fail, Pr, Done, Idle, Stopped }

data class Stage(
    /** 1-based index into [STAGES] of where it is now. */
    val at: Int,
    val tone: Tone,
    val steps: List<StepState>,
    val prStatus: String?,
)

/**
 * How far a worker has got, and the tone to draw it in.
 *
 * @param mode the worker session's permission mode — a worker in plan mode that has not
 *   filed a plan yet is at Plan, not Building.
 */
fun workerStage(
    w: OrchestratorWorker,
    inbox: List<InboxItem>,
    prStatus: String? = null,
    mode: String? = null,
    decision: BriefDecision? = null,
): Stage {
    val tl = timeline(w, inbox)
    val status = if (w.pr != null) (prStatus ?: w.pr.status) else null
    val word = workerStateWord(w)
    val planFiled = tl.plan == StepState.Done || tl.plan == StepState.Unknown
    val planningAgain = mode == "plan" && tl.plan == StepState.Done && tl.approved == StepState.Todo && w.closedAt == null
    val at = when {
        w.pr != null && status == "merged" -> 6
        w.pr != null -> 5
        tl.done == StepState.Done -> 4
        tl.approved == StepState.Now -> 2
        tl.plan == StepState.Now || planningAgain || (mode == "plan" && !planFiled && w.closedAt == null) -> 1
        else -> 3
    }
    fun passed(i: Int): StepState = when (i) {
        0 -> if (tl.plan == StepState.Skipped || tl.plan == StepState.Unknown) tl.plan else StepState.Done
        1 -> if (tl.approved == StepState.Done || tl.approved == StepState.Now) StepState.Done else tl.approved
        3 -> if (tl.done == StepState.Unknown || tl.done == StepState.Todo) StepState.Unknown else StepState.Done
        else -> StepState.Done
    }
    val steps = STAGES.indices.map { i ->
        when {
            i + 1 < at -> passed(i)
            i + 1 == at -> StepState.Now
            else -> StepState.Todo
        }
    }
    val tone = when {
        w.pending != null && w.closedAt == null -> Tone.Wait
        decision != null && w.closedAt == null && word != "running" -> Tone.You
        word == "error" -> Tone.Error
        word == "running" -> Tone.Run
        w.pr != null -> when {
            status == "merged" -> Tone.Merged
            status in PR_TROUBLE -> Tone.Fail
            else -> Tone.Pr
        }
        w.closedAt != null -> Tone.Done
        word == "idle" -> Tone.Idle
        else -> Tone.Stopped
    }
    return Stage(at, tone, steps, status)
}

data class WorkerLine(val lead: String, val detail: String?)

private val PENDING_WORD = mapOf("plan" to "Plan", "tool" to "Permission", "question" to "Question")

/**
 * The words beside a worker. [activity] is the worker session's runner `detail` or
 * `activity`, when the session list has one.
 */
fun stageLine(
    w: OrchestratorWorker,
    stage: Stage,
    inbox: List<InboxItem>,
    decision: BriefDecision?,
    activity: String?,
    now: Long,
): WorkerLine = when (stage.tone) {
    Tone.Wait -> {
        val b = blockedOn(w, inbox)
        val item = b?.item
        val age = item?.let { (now - it.at).let { ms -> if (ms < 60_000) "<1m" else shortAge(ms) } }
        val tool = item?.ask?.toolLabel?.takeIf { w.pending == "tool" }
        val who = item?.let { if (it.status == "new") "the orchestrator has not read it yet" else "the orchestrator is on it" }
        WorkerLine(
            "${PENDING_WORD[w.pending] ?: "Something"} waiting" + (age?.let { " $it" } ?: ""),
            listOfNotNull(tool, who).joinToString(" · ").ifBlank { null },
        )
    }
    Tone.You -> WorkerLine(
        "Waiting on you",
        decision?.let { clip(it.text, 60) + " · " + shortAge(now - it.at) },
    )
    Tone.Error -> WorkerLine("Stopped with an error", null)
    Tone.Run -> WorkerLine(
        activity?.let(::toolDisplayText)?.trim()?.removeSuffix("…")?.removeSuffix("...")?.takeIf { it.isNotBlank() }?.let { clip(it, 70) } ?: "Working",
        null,
    )
    Tone.Idle -> WorkerLine("Idle", lastReport(w, inbox)?.let { clip(firstLine(it.text), 70) }?.ifBlank { null })
    Tone.Done -> WorkerLine("Closed", null)
    Tone.Stopped -> WorkerLine("Not running", null)
    Tone.Pr, Tone.Fail, Tone.Merged -> WorkerLine("", null)
}

/** The queued spawns' lines: the first starts when a slot frees, the rest are in line. */
fun queuedWords(index: Int): String = when (index) {
    0 -> "Queued — starts when a slot frees"
    1 -> "Queued, second in line"
    2 -> "Queued, third in line"
    else -> "Queued, ${index + 1}th in line"
}

fun originChip(origin: com.twentygx.tgxcode.data.model.TaskOrigin?): String? = when (origin?.from) {
    "draft" -> "draft"
    "dialog" -> "yours"
    else -> null
}

fun spawnTitle(s: PendingSpawn): String = s.title?.takeIf { it.isNotBlank() } ?: "untitled"

// ----------------------------------------------------------------- inbox

fun inboxKindLabel(kind: String): String = when (kind) {
    "plan" -> "Plan"
    "permission" -> "Permission"
    "ask" -> "Question for it"
    "question" -> "Question"
    "update" -> "Update"
    "done" -> "Done"
    "turn" -> "Turn report"
    "note" -> "Note"
    "task" -> "Task from you"
    else -> kind
}

fun inboxStatusWord(item: InboxItem): String = when (item.status) {
    "new" -> "unread"
    "read" -> if (item.requestId != null) "read · waiting" else "read"
    "stale" -> "gone"
    "resolved" -> (item.outcome ?: "done") + (item.by?.let { " · by $it" } ?: "")
    else -> item.status
}

/** Whether a row offers Dismiss: still open, and not a task (that is Take back). */
fun dismissable(item: InboxItem): Boolean = item.open && item.kind != "task"

private val NUDGE_WORDS = mapOf(
    "plan" to ("plan" to "plans"),
    "permission" to ("permission prompt" to "permission prompts"),
    "ask" to ("question for you" to "questions for you"),
    "question" to ("question" to "questions"),
    "update" to ("update" to "updates"),
    "done" to ("done report" to "done reports"),
    "turn" to ("turn report" to "turn reports"),
    "note" to ("note" to "notes"),
    "task" to ("task" to "tasks"),
)

/** The `orchestrator-inbox` transcript row: `3 items waiting — 1 plan, 2 turn reports`. */
fun inboxNudgeText(count: Int, counts: Map<String, Int>?, summary: String?): String {
    val what = counts?.entries?.filter { it.value > 0 }?.joinToString(", ") { (kind, n) ->
        val words = NUDGE_WORDS[kind]
        "$n ${if (words == null) kind else if (n == 1) words.first else words.second}"
    }?.ifBlank { null } ?: summary?.takeIf { it.isNotBlank() }?.let(::toolDisplayText)
    return "$count item${if (count == 1) "" else "s"} waiting" + (what?.let { " — $it" } ?: "")
}

// ----------------------------------------------------------------- usage

data class Meter(val label: String, val used: Double?, val cutoff: Int?) {
    /** `over`, `near` (within 10 points), or `ok`. */
    val tone: String
        get() = when {
            used == null || cutoff == null -> "ok"
            used >= cutoff -> "over"
            used >= cutoff - 10 -> "near"
            else -> "ok"
        }
}

private val METER_LABELS = listOf(
    "five_hour" to "5h",
    "seven_day" to "Week",
    "seven_day_opus" to "Week Opus",
    "seven_day_sonnet" to "Week Sonnet",
)

/**
 * One meter per usage window: five-hour and weekly always, the model-specific weekly
 * windows only when the cutoff watches them.
 *
 * @param used each window's `usedPercent` by type.
 */
fun usageMeters(stop: UsageStop, used: Map<String, Double>): List<Meter> =
    METER_LABELS.mapNotNull { (type, label) ->
        val cutoff = when {
            !stop.enabled -> null
            stop.window == "all" -> stop.limits.of(type)
            stop.window == type -> stop.percent
            else -> null
        }
        if (type != "five_hour" && type != "seven_day" && cutoff == null) null
        else Meter(label, used[type], cutoff)
    }

// ------------------------------------------------------------------- models

/** A model id as a row says it: `claude-opus-4-1-20250805` → `opus-4-1`, an alias as is. */
fun shortModel(model: String): String = model.removePrefix("claude-").replace(Regex("-\\d{8}$"), "")

/**
 * A worker's model and effort, as `web/orchestrator-model.js`'s `workerModelWords` says
 * them — "haiku · low effort", "default model" for one started on inherit. [marker] is
 * `fixed` when the worker setting overrode the orchestrator, `default` when the default
 * for when it does not say filled the gap, for either half; null otherwise. Null on a
 * row from a bridge that does not send `modelSource` and has no model or effort either.
 */
data class WorkerModelWords(val text: String, val marker: String?)

fun workerModelWords(w: OrchestratorWorker): WorkerModelWords? {
    val src = w.modelSource
    if (src == null && w.model == null && w.effort == null) return null
    val text = listOfNotNull(w.model?.let(::shortModel) ?: "default model", w.effort?.let { "$it effort" })
        .joinToString(" · ")
    val sources = listOfNotNull(src?.model, src?.effort)
    val marker = when {
        "fixed" in sources -> "fixed"
        "default" in sources -> "default"
        else -> null
    }
    return WorkerModelWords(text, marker)
}

/** The line under the model pickers, as the desktop popover's. [label] names a model id. */
fun workerModelWords(settings: OrchestratorSettings, label: (String) -> String): String {
    val choose = settings.workerModel == OrchestratorSettings.CHOOSE
    val policy = if (choose) "It is told which models there are and when each fits, and picks per worker."
    else "Every worker runs on ${label(settings.workerModel)}, whatever the orchestrator asks for — it is told when it was overridden."
    return "$policy A task you hand it keeps the model you picked."
}
