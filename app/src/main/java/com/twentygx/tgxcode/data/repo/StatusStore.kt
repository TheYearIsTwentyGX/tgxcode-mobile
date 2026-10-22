package com.twentygx.tgxcode.data.repo

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.getOrNull
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.MarkReadRequest
import com.twentygx.tgxcode.data.model.NotificationRow
import com.twentygx.tgxcode.data.model.Quota
import com.twentygx.tgxcode.data.model.ReadMarks
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.net.BridgeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The notification log as the inbox draws it. */
data class Inbox(
    /** Newest first. */
    val rows: List<NotificationRow> = emptyList(),
    /** The bridge's count across the whole log, never a count of [rows]. */
    val unread: Int = 0,
    val read: ReadMarks = ReadMarks(),
    val loaded: Boolean = false,
)

/**
 * Account-wide status that belongs to no one session: the quota windows, and the
 * notification log behind the bell.
 *
 * Both are whole payloads on the stream (`quota`, `notification`) or patches with
 * enough in them to repaint without a refetch (`notification-read`,
 * `notification-resolved`), so a read is needed only on attaching — [BridgeRepository.resyncs]
 * — and on a slow tier while polling, where nothing is pushed.
 */
class StatusStore(
    private val api: BridgeApi,
    private val repo: BridgeRepository,
    private val scope: CoroutineScope,
) {
    private val _quota = MutableStateFlow<Quota?>(null)
    val quota: StateFlow<Quota?> = _quota.asStateFlow()

    private val _inbox = MutableStateFlow(Inbox())
    val inbox: StateFlow<Inbox> = _inbox.asStateFlow()

    private val _refreshingQuota = MutableStateFlow(false)
    val refreshingQuota: StateFlow<Boolean> = _refreshingQuota.asStateFlow()

    init {
        scope.launch { repo.resyncs.collect { loadAll() } }
        scope.launch {
            // Every 20s, the same tier as the sessions list: neither is urgent, and both
            // would otherwise freeze for as long as the stream will not carry.
            repo.pollRounds.collect { round ->
                if (round % BridgeRepository.INDEX_EVERY == 0) loadAll()
            }
        }
        scope.launch { repo.events.collect(::onEvent) }
    }

    private suspend fun loadAll() {
        loadQuota()
        loadInbox()
    }

    suspend fun loadQuota() {
        api.quota().getOrNull()?.let { _quota.value = it }
    }

    suspend fun loadInbox() {
        api.notifications().getOrNull()?.let { page ->
            _inbox.value = Inbox(page.notifications, page.unread, page.read, loaded = true)
        }
    }

    private fun onEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.QuotaChanged -> _quota.value = event.quota

            // The default scope is the loud rows, so a quiet one filed now would not be
            // in a page fetched now either — keep the inbox to the same rule.
            is StreamEvent.NotificationFiled -> _inbox.value = _inbox.value.let { inbox ->
                val row = event.row
                inbox.copy(
                    rows = if (row.loud) listOf(row) + inbox.rows.filterNot { it.id == row.id } else inbox.rows,
                    unread = row.unread ?: inbox.unread,
                )
            }

            is StreamEvent.NotificationResolved -> _inbox.value = _inbox.value.let { inbox ->
                inbox.copy(
                    rows = inbox.rows.map {
                        if (it.id == event.id) it.copy(outcome = event.outcome, outcomeAt = event.outcomeAt) else it
                    },
                )
            }

            is StreamEvent.NotificationRead -> _inbox.value = _inbox.value.withRead(
                _inbox.value.read.advance(event.sessionId, event.at),
                event.unread,
            )

            is StreamEvent.NotificationsCleared -> _inbox.value = _inbox.value.copy(rows = emptyList(), unread = 0)

            else -> Unit
        }
    }

    /**
     * Run the beacon now. A 409 is not a failure — either nowhere is configured to run
     * it, or a run is already on its way — and both carry the payload, so the pill
     * still updates.
     */
    fun refreshQuota() {
        if (_refreshingQuota.value) return
        _refreshingQuota.value = true
        scope.launch {
            try {
                when (val result = api.refreshQuota()) {
                    is ApiResult.Ok -> {
                        result.value.quota?.let { _quota.value = it }
                        if (!result.value.ok) {
                            repo.report(
                                result.value.quota?.beacon?.reason
                                    ?.let { "No new reading: $it" } ?: "No new reading yet",
                            )
                        }
                    }
                    is ApiResult.Http -> repo.report(
                        when {
                            result.status != 409 -> "Could not refresh: ${result.message()}"
                            result.error?.contains("running", ignoreCase = true) == true ->
                                "A reading is already on its way"
                            else -> "The quota beacon is not set up on the desktop yet"
                        },
                    )
                    is ApiResult.Offline -> repo.report("Could not refresh: ${result.message()}", isError = true)
                }
            } finally {
                _refreshingQuota.value = false
            }
        }
    }

    /**
     * @param sessionId null for "everything". Applied locally first — a badge that sits
     *   there after you have looked reads as a tap that did not land — and settled by
     *   the bridge's own `unread`, which counts rows this page may not hold.
     */
    fun markRead(sessionId: String? = null) {
        val now = System.currentTimeMillis()
        val before = _inbox.value
        _inbox.value = before.withRead(before.read.advance(sessionId, now), null)
        scope.launch {
            val body = if (sessionId == null) MarkReadRequest(all = true) else MarkReadRequest(sessionId = sessionId)
            when (val result = api.markRead(body)) {
                is ApiResult.Ok -> _inbox.value = _inbox.value.withRead(result.value.read, result.value.unread)
                else -> _inbox.value = before
            }
        }
    }

    fun clear() {
        val before = _inbox.value
        _inbox.value = before.copy(rows = emptyList(), unread = 0)
        scope.launch {
            val result = api.clearNotifications()
            if (result !is ApiResult.Ok) {
                _inbox.value = before
                repo.report("Could not clear: ${result.message()}", isError = true)
            }
        }
    }
}

/**
 * Re-stamp every row's `read` against new watermarks — the bridge's own rule, so the
 * list repaints without a refetch.
 *
 * @param unread the bridge's count when it gave one. Without it the count is
 *   estimated from the rows in hand, which can only ever undercount.
 */
internal fun Inbox.withRead(marks: ReadMarks, unread: Int?): Inbox {
    val rows = rows.map { it.copy(read = marks.covers(it)) }
    return copy(
        rows = rows,
        read = marks,
        unread = unread ?: rows.count { it.loud && !it.read }.coerceAtMost(this.unread),
    )
}
