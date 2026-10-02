package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

// ------------------------------------------------------------------ quota

/**
 * `GET /api/quota`, and the whole payload of the `quota` event.
 *
 * On a quota plan `costUsd` is 0 in every transcript, so this is the only answer to
 * "is there room to release that draft". Readable remotely for exactly that reason.
 */
@Serializable
data class Quota(
    /** Unix **seconds**, the bridge's clock — not millis, unlike most of this API. */
    val now: Long? = null,
    /** In the order to draw them: 5-hour first, then the weekly ones. Do not re-sort. */
    val windows: List<QuotaWindow> = emptyList(),
    val beacon: QuotaBeacon? = null,
)

@Serializable
data class QuotaWindow(
    /** `five_hour`, `seven_day`, … — not a closed set, so draw from [label]. */
    val type: String = "",
    val label: String? = null,
    val shortLabel: String? = null,
    /** 0–100, or null — and null means nobody has said, which is **not** zero. */
    val usedPercent: Double? = null,
    /** Unix seconds: when [usedPercent] was learned. Per window, and it can be hours old. */
    val usedPercentAt: Long? = null,
    val resetsAt: Long? = null,
    /** `allowed`, `allowed_warning`, `rejected`, or null when never observed. */
    val status: String? = null,
    val isUsingOverage: Boolean = false,
) {
    val name: String get() = shortLabel?.takeIf { it.isNotBlank() } ?: label ?: type

    /**
     * How old the percentage is, in seconds, against the bridge's own clock — the
     * phone's clock is not the one the stamp was written against.
     */
    fun ageSeconds(now: Long?): Long? {
        val at = usedPercentAt ?: return null
        val clock = now ?: return null
        return (clock - at).coerceAtLeast(0)
    }

    /** `web/app.js` greys a reading past 30 minutes. A bare old number is the failure to avoid. */
    fun isStale(now: Long?): Boolean = (ageSeconds(now) ?: Long.MAX_VALUE) > 30 * 60

    val isRejected: Boolean get() = status == "rejected"
    val isWarning: Boolean get() = status == "allowed_warning"
}

/** Why the percentage is or is not moving. A failing beacon is not an error to raise. */
@Serializable
data class QuotaBeacon(
    val enabled: Boolean = false,
    val running: Boolean = false,
    /** Unix seconds. Absent means it has never run. */
    val at: Long? = null,
    val ok: Boolean? = null,
    val probed: Boolean? = null,
    val reason: String? = null,
)

/** `POST /api/quota/refresh` answers 200 and 409 alike with the whole payload. */
@Serializable
data class QuotaRefreshResponse(
    val ok: Boolean = false,
    val quota: Quota? = null,
    val error: String? = null,
    val needsSetup: Boolean = false,
    val running: Boolean = false,
)

// ---------------------------------------------------------- notifications

/**
 * One row of `GET /api/notifications`, and the payload of a `notification` event (which
 * adds [unread], the badge after this row).
 *
 * [sessionId] **may be null** — two of the schedule types are about a run that never
 * produced a session — so a row only opens something when it has one.
 */
@Serializable
data class NotificationRow(
    val id: String,
    /** Epoch millis. */
    val at: Long = 0,
    /** `permission`, `plan`, `question`, `finished`, `failed`, `stalled`, `agent-done`, … */
    val type: String? = null,
    val sessionId: String? = null,
    val title: String? = null,
    val project: String? = null,
    val summary: String? = null,
    val detail: String? = null,
    /** Cleared the bar for interrupting somebody. Only loud rows count toward unread. */
    val loud: Boolean = false,
    val requestId: String? = null,
    /** Filled in later, on the row that already exists, when an ask is answered. */
    val outcome: String? = null,
    val outcomeAt: Long? = null,
    /** Computed by the bridge from the watermarks; see [ReadMarks.covers]. */
    val read: Boolean = false,
    /** Only on the event: the badge count after this row. */
    val unread: Int? = null,
)

/**
 * The read watermarks, all epoch millis. A row is read when
 * `row.at <= max(all, sessions[row.sessionId] ?: 0)` — the bridge's own rule, which a
 * `notification-read` event has to be folded through to repaint without a refetch.
 */
@Serializable
data class ReadMarks(
    val all: Long = 0,
    val sessions: Map<String, Long> = emptyMap(),
) {
    fun covers(row: NotificationRow): Boolean =
        row.at <= maxOf(all, row.sessionId?.let { sessions[it] } ?: 0)

    /** Watermarks only ever move forward. */
    fun advance(sessionId: String?, at: Long): ReadMarks =
        if (sessionId == null) {
            copy(all = maxOf(all, at))
        } else {
            copy(sessions = sessions + (sessionId to maxOf(sessions[sessionId] ?: 0, at)))
        }
}

@Serializable
data class NotificationsResponse(
    /** Newest first. */
    val notifications: List<NotificationRow> = emptyList(),
    /** Across the whole log, not this page — render the badge from it directly. */
    val unread: Int = 0,
    val read: ReadMarks = ReadMarks(),
)

/** `{all: true}` or `{sessionId}`; sending neither is a 400. */
@Serializable
data class MarkReadRequest(
    val all: Boolean? = null,
    val sessionId: String? = null,
)

@Serializable
data class MarkReadResponse(
    val ok: Boolean = false,
    val moved: Boolean = false,
    val unread: Int = 0,
    val read: ReadMarks = ReadMarks(),
)

// ------------------------------------------------------------- task list

/**
 * The agent's own checklist for a session — `GET /api/sessions/:id/tasks`, and the
 * whole payload of `task-list`.
 *
 * [TaskItem.id] is null for a `todo` list and must not be synthesised from position:
 * TodoWrite sends the whole list every time with no ids, so an index-based key would
 * mis-associate items the moment the agent inserted a step.
 */
@Serializable
data class TaskList(
    val sessionId: String? = null,
    /** `directory`, `todo`, or null when the session kept no list. */
    val source: String? = null,
    val items: List<TaskItem> = emptyList(),
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /** Work is left and nothing is in progress: a list that has stopped. */
    val idle: Boolean = false,
    val truncated: Int = 0,
)

@Serializable
data class TaskItem(
    val id: String? = null,
    val subject: String = "",
    val description: String? = null,
    val activeForm: String? = null,
    /** `pending`, `in_progress` or `completed` — a closed set. */
    val status: String = "pending",
) {
    val isDone: Boolean get() = status == "completed"
    val isActive: Boolean get() = status == "in_progress"
}
