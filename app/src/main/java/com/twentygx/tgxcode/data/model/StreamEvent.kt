package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * The SSE events, keyed by the `event:` name.
 *
 * The name is out-of-band from the JSON body — the bridge writes
 * `event: tail` then `data: {...}` — so this is decoded by a plain `when(name)`
 * rather than polymorphic deserialization on a discriminator field.
 *
 * **Nothing is buffered for a disconnected client.** There is no `id:` field and no
 * `Last-Event-ID` replay. Recovery is: reconnect, re-subscribe from the byte offset
 * you hold, and call `/since`. Design for that rather than around it — on a phone it
 * happens constantly.
 */
sealed interface StreamEvent {

    /** The resynchronisation point. Everything restarts from here. */
    @Serializable
    data class Hello(val clientId: String, val version: String? = null) : StreamEvent

    @Serializable
    data class Tail(
        val sessionId: String,
        val events: List<TranscriptEvent> = emptyList(),
        val offset: Long = 0,
    ) : StreamEvent

    /** The transcript was rewritten — compacted or forked. Reload from scratch. */
    @Serializable
    data class Reset(val sessionId: String) : StreamEvent

    /** The followed subagent's new lines, keyed by the tool call that spawned it. */
    @Serializable
    data class AgentTail(
        val sessionId: String,
        val toolUseId: String? = null,
        val events: List<TranscriptEvent> = emptyList(),
        val offset: Long = 0,
    ) : StreamEvent

    @Serializable
    data class AgentReset(val sessionId: String, val toolUseId: String? = null) : StreamEvent

    /** Sent only when the board has actually changed. */
    @Serializable
    data class BoardChanged(val board: Overview) : StreamEvent

    @Serializable
    data class SessionsChanged(val at: Long? = null) : StreamEvent

    @Serializable
    data class SessionDeleted(val sessionId: String, val title: String? = null) : StreamEvent

    @Serializable
    data class RunnerStatusChanged(val status: RunnerStatus) : StreamEvent

    @Serializable
    data class PermissionRequested(val ask: Ask) : StreamEvent

    @Serializable
    data class PermissionResolved(
        val sessionId: String,
        val requestId: String? = null,
        val outcome: String? = null,
    ) : StreamEvent

    @Serializable
    data class Notice(
        val sessionId: String? = null,
        val level: String? = null,
        val kind: String? = null,
        val text: String? = null,
    ) : StreamEvent

    @Serializable
    data class TurnComplete(
        val sessionId: String? = null,
        val isError: Boolean = false,
        val detail: String? = null,
        val costUsd: Double? = null,
        val durationMs: Long? = null,
    ) : StreamEvent

    /** Hand the text back to the user — it did not go anywhere. */
    @Serializable
    data class SendFailed(
        val sessionId: String? = null,
        val kind: String? = null,
        val message: String? = null,
        val unsent: List<String> = emptyList(),
    ) : StreamEvent

    @Serializable
    data class SessionForked(val from: String, val to: String) : StreamEvent

    /**
     * The **whole** `GET /api/drafts` payload, not a nudge to refetch one.
     *
     * Two things follow from that. There is nothing to fetch when this arrives — apply
     * [drafts] directly. And it is **not gated by a `POST /api/subscribe` flag**, unlike
     * `overview`: a draft only changes because somebody changed it, so there is no tick
     * to switch on and every window gets every change.
     */
    @Serializable
    data class DraftsChanged(
        val at: Long? = null,
        val drafts: List<Draft> = emptyList(),
        val counts: DraftCounts? = null,
    ) : StreamEvent

    /**
     * A suggested follow-up was started, dismissed or undone — possibly in another
     * window. Carries the ids only, so the list has to be re-read.
     *
     * Note there is no push for a task being *filed*. `docs/api.md:855`: a client
     * watching one conversation sees the `suggestion` event on its tail, and *"anything
     * watching all of them refetches, and `sessions-changed` is the signal that the
     * index moved."*
     */
    /**
     * The **whole** `GET /api/prs` payload, so there is nothing to refetch for the rows.
     *
     * Ungated, exactly as [DraftsChanged] is — no `POST /api/subscribe` flag switches
     * it on. Unlike that one it fires **without anybody having done anything**: it is a
     * background refresher in the bridge noticing that a review landed, a build
     * finished, or somebody merged.
     *
     * **Not a tick.** It is pushed only when the answer actually moved, so a pass that
     * re-lists a quiet repository and finds nothing changed pushes nothing. Anything
     * that read its arrival as "the bridge is alive" would be wrong: it can be silent
     * for twenty minutes with everything healthy.
     *
     * The open conversation is a *second* question — per-PR detail rather than the
     * aggregate — so a client showing one has to re-read that itself.
     */
    @Serializable
    data class PrsChanged(val prs: PrsPayload) : StreamEvent

    @Serializable
    data class SuggestionChanged(
        val at: Long? = null,
        val sessionId: String? = null,
        val toolUseId: String? = null,
    ) : StreamEvent

    /** `{sessionId, standing}` — patch that row; there is nothing to refetch. */
    @Serializable
    data class StandingChanged(val sessionId: String, val standing: Standing? = null) : StreamEvent

    /**
     * A busy turn crossed into or out of *stalled*. Sent once per crossing; `false` also
     * comes when the turn ends any way at all, so it means "take the badge down".
     */
    @Serializable
    data class RunnerStalled(
        val sessionId: String,
        val stalled: Boolean = false,
        val lastActivityAt: Long? = null,
        val busySince: Long? = null,
        val stalledAfterMs: Long? = null,
    ) : StreamEvent

    /** The whole task-list payload, sent once on follow and then only when it moves. */
    @Serializable
    data class TaskListChanged(val list: TaskList) : StreamEvent

    /** The whole `GET /api/quota` payload. Account-wide, so no `sessionId`. */
    @Serializable
    data class QuotaChanged(val quota: Quota) : StreamEvent

    /** A whole notification row, just filed, with `unread` on it. */
    @Serializable
    data class NotificationFiled(val row: NotificationRow) : StreamEvent

    @Serializable
    data class NotificationResolved(
        val id: String,
        val outcome: String? = null,
        val outcomeAt: Long? = null,
    ) : StreamEvent

    /** A watermark moved, here or in another window. `sessionId` null is "everything". */
    @Serializable
    data class NotificationRead(
        val sessionId: String? = null,
        val at: Long = 0,
        val unread: Int = 0,
    ) : StreamEvent

    @Serializable
    data class NotificationsCleared(val at: Long? = null) : StreamEvent

    /** The whole `GET /api/later` payload; also fires on deliveries nobody pressed. */
    @Serializable
    data class LaterChanged(val payload: LaterPayload) : StreamEvent

    /** The whole `GET /api/snippets` payload, **unfiltered** by cwd. */
    @Serializable
    data class SnippetsChanged(val payload: SnippetsPayload) : StreamEvent

    /** The whole `GET /api/schedules` payload; moves on its own as schedules fire. */
    @Serializable
    data class SchedulesChanged(val payload: SchedulesPayload) : StreamEvent

    /** That directory's slash commands changed; drop what was cached. */
    @Serializable
    data class SlashCommandsChanged(val cwd: String? = null, val at: Long? = null) : StreamEvent

    /**
     * The **whole** `GET /api/sessions/:id/orchestrator` payload for one orchestrator,
     * debounced to the end of a burst. Ungated. `enabled: false` means the role was
     * just turned off.
     */
    @Serializable
    data class OrchestratorChanged(val payload: OrchestratorPayload) : StreamEvent

    /** The user-level settings, whole, after any change — in another window included. */
    @Serializable
    data class PrefsChanged(val prefs: Prefs) : StreamEvent
}
