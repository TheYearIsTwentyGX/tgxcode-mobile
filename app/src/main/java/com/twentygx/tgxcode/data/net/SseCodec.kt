package com.twentygx.tgxcode.data.net

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.LaterPayload
import com.twentygx.tgxcode.data.model.SchedulesPayload
import com.twentygx.tgxcode.data.model.SnippetsPayload
import com.twentygx.tgxcode.data.model.NotificationRow
import com.twentygx.tgxcode.data.model.Quota
import com.twentygx.tgxcode.data.model.TaskList
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.RunnerStatus
import com.twentygx.tgxcode.data.model.StreamEvent
import kotlinx.serialization.serializer

/**
 * `event:` name plus `data:` JSON, into a typed [StreamEvent].
 *
 * A name this build does not know returns null and is dropped, the same way
 * mobile.js simply has no handler for it. New bridge events must never be able to
 * break an older client.
 */
object SseCodec {

    fun decode(name: String?, data: String): StreamEvent? {
        if (data.isBlank()) return null
        return runCatching { parse(name, data) }.getOrNull()
    }

    private fun parse(name: String?, data: String): StreamEvent? = when (name) {
        "hello" -> AppJson.decodeFromString<StreamEvent.Hello>(data)
        "tail" -> AppJson.decodeFromString<StreamEvent.Tail>(data)
        "reset" -> AppJson.decodeFromString<StreamEvent.Reset>(data)
        "agent-tail" -> AppJson.decodeFromString<StreamEvent.AgentTail>(data)
        "agent-reset" -> AppJson.decodeFromString<StreamEvent.AgentReset>(data)

        // These arrive as the bare payload rather than a wrapper object, so
        // they are decoded into their inner type and wrapped here.
        "overview" ->
            StreamEvent.BoardChanged(AppJson.decodeFromString(serializer<Overview>(), data))
        "runner-status" ->
            StreamEvent.RunnerStatusChanged(AppJson.decodeFromString(serializer<RunnerStatus>(), data))
        "permission-request" ->
            StreamEvent.PermissionRequested(AppJson.decodeFromString(serializer<Ask>(), data))
        "prs-changed" ->
            StreamEvent.PrsChanged(AppJson.decodeFromString(serializer<PrsPayload>(), data))
        "task-list" ->
            StreamEvent.TaskListChanged(AppJson.decodeFromString(serializer<TaskList>(), data))
        "quota" ->
            StreamEvent.QuotaChanged(AppJson.decodeFromString(serializer<Quota>(), data))
        "later-changed" ->
            StreamEvent.LaterChanged(AppJson.decodeFromString(serializer<LaterPayload>(), data))
        "snippets-changed" ->
            StreamEvent.SnippetsChanged(AppJson.decodeFromString(serializer<SnippetsPayload>(), data))
        "schedules-changed" ->
            StreamEvent.SchedulesChanged(AppJson.decodeFromString(serializer<SchedulesPayload>(), data))
        "slash-commands" -> AppJson.decodeFromString<StreamEvent.SlashCommandsChanged>(data)
        "notification" ->
            StreamEvent.NotificationFiled(AppJson.decodeFromString(serializer<NotificationRow>(), data))

        "sessions-changed" -> AppJson.decodeFromString<StreamEvent.SessionsChanged>(data)
        "session-deleted" -> AppJson.decodeFromString<StreamEvent.SessionDeleted>(data)
        "permission-resolved" -> AppJson.decodeFromString<StreamEvent.PermissionResolved>(data)
        "notice" -> AppJson.decodeFromString<StreamEvent.Notice>(data)
        "turn-complete" -> AppJson.decodeFromString<StreamEvent.TurnComplete>(data)
        "send-failed" -> AppJson.decodeFromString<StreamEvent.SendFailed>(data)
        "session-forked" -> AppJson.decodeFromString<StreamEvent.SessionForked>(data)
        "drafts-changed" -> AppJson.decodeFromString<StreamEvent.DraftsChanged>(data)
        "suggestion-changed" -> AppJson.decodeFromString<StreamEvent.SuggestionChanged>(data)
        "standing-changed" -> AppJson.decodeFromString<StreamEvent.StandingChanged>(data)
        "runner-stalled" -> AppJson.decodeFromString<StreamEvent.RunnerStalled>(data)
        "notification-resolved" -> AppJson.decodeFromString<StreamEvent.NotificationResolved>(data)
        "notification-read" -> AppJson.decodeFromString<StreamEvent.NotificationRead>(data)
        "notifications-cleared" -> AppJson.decodeFromString<StreamEvent.NotificationsCleared>(data)

        else -> null
    }
}
