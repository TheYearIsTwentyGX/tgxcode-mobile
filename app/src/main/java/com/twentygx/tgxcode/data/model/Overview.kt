package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/** Why a card is on the board. The bridge has already ordered by this. */
enum class BoardReason { Ask, Error, Here, Elsewhere, Pinned, Unknown }

@Serializable
data class Headline(val text: String? = null, val ts: String? = null)

@Serializable
data class TaskProgress(
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
)

@Serializable
data class DevServer(
    val port: Int? = null,
    val title: String? = null,
    val owned: Boolean = false,
)

@Serializable
data class BoardCard(
    val sessionId: String,
    val reason: String? = null,
    val title: String? = null,
    val projectName: String? = null,
    val cwd: String? = null,
    val worktree: Worktree? = null,
    val pinned: Boolean = false,
    val test: Boolean = false,
    val model: String? = null,
    val permissionMode: String? = null,
    val lastTs: String? = null,
    val lastUserTs: String? = null,
    val toolCalls: Int = 0,
    val userMessages: Int = 0,
    val runner: CardRunner? = null,
    val live: LiveInfo? = null,
    /** A permission ask, or a sudo one (`kind: "sudo"`) when that is all it waits on. */
    val ask: @Serializable(with = BoardAskSerializer::class) Ask? = null,
    val headlines: List<Headline> = emptyList(),
    val tasks: TaskProgress? = null,
    val devservers: List<DevServer> = emptyList(),
    /**
     * `you` or `orchestrator` — who an [ask] is waiting on. `orchestrator` only on a
     * worker whose orchestrator is still handling its asks; it turns `you` once the ask
     * is stuck. Answerable from the card either way: the first answer wins.
     */
    val askFor: String? = null,
    /** On a worker card: the orchestrator whose card this is drawn inside. */
    val orchestratorId: String? = null,
    /** On an orchestrator's card: the workers it carries. */
    val workers: BoardWorkers? = null,
    val standing: Standing? = null,
) {
    /** An ask its orchestrator is still carrying — answerable, but not yet yours. */
    val askForOrchestrator: Boolean get() = ask != null && askFor == "orchestrator"

    val boardReason: BoardReason
        get() = when (reason) {
            "ask" -> BoardReason.Ask
            "error" -> BoardReason.Error
            "here" -> BoardReason.Here
            "elsewhere" -> BoardReason.Elsewhere
            "pinned" -> BoardReason.Pinned
            else -> BoardReason.Unknown
        }

    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"

    val place: String get() = worktree?.name ?: projectName ?: ""

    /**
     * The "why" line. An ask outranks whatever the runner is doing, because the ask
     * is the reason the card is at the top.
     */
    val why: String
        get() {
            ask?.let {
                val what = when (it.askKind) {
                    AskKind.Plan -> "A plan to approve"
                    AskKind.Question -> "A question to answer"
                    // `web/boards/taskboard.js`'s line, clipped the same.
                    AskKind.Sudo -> "Wants your password for sudo " +
                        (it.sudo?.command.orEmpty().let { c -> if (c.length > 50) c.take(49) + "…" else c })
                    else -> "Wants to run ${it.toolLabel}"
                }
                return if (askForOrchestrator) "$what — its orchestrator has it" else what
            }
            runner?.activity?.takeIf { it.isNotBlank() }?.let { return it }
            return when (boardReason) {
                BoardReason.Ask -> "Waiting for you"
                BoardReason.Error -> "Stopped with an error"
                BoardReason.Here -> "Running"
                BoardReason.Elsewhere -> "Running in a terminal"
                BoardReason.Pinned -> "Pinned"
                BoardReason.Unknown -> ""
            }
        }

    /**
     * One headline, the last — not three like the desktop. On a 390px screen the
     * most recent line is the one worth the space.
     *
     * Suppressed when it just repeats the why line, which happens often: the
     * runner's `activity` is frequently derived from the same event as the latest
     * headline, and printing the sentence twice wastes the row it is competing for.
     */
    val headline: String?
        get() = headlines.lastOrNull()?.text
            ?.takeIf { it.isNotBlank() && it.trim() != why.trim() }

    val taskLine: String?
        get() = tasks?.takeIf { it.total > 0 }?.let { t ->
            buildString {
                append("${t.done} of ${t.total} done")
                t.current?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            }
        }
}

@Serializable
data class Overview(
    val at: Long? = null,
    val ready: Boolean = false,
    val sessions: List<BoardCard> = emptyList(),
    val hidden: Int = 0,
    /** The count worth putting on a badge. */
    val waiting: Int = 0,
    val running: Int = 0,
)
