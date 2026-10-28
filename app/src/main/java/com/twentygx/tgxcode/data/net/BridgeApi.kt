package com.twentygx.tgxcode.data.net

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.CreateSessionRequest
import com.twentygx.tgxcode.data.model.CreateSessionResponse
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.DraftResponse
import com.twentygx.tgxcode.data.model.DraftsResponse
import com.twentygx.tgxcode.data.model.FlagsRequest
import com.twentygx.tgxcode.data.model.FlagsResponse
import com.twentygx.tgxcode.data.model.Changes
import com.twentygx.tgxcode.data.model.CronDescription
import com.twentygx.tgxcode.data.model.FileDiff
import com.twentygx.tgxcode.data.model.Health
import com.twentygx.tgxcode.data.model.LaterCreate
import com.twentygx.tgxcode.data.model.LaterPatch
import com.twentygx.tgxcode.data.model.LaterPayload
import com.twentygx.tgxcode.data.model.LaterResponse
import com.twentygx.tgxcode.data.model.ScheduleResponse
import com.twentygx.tgxcode.data.model.ScheduleRunResponse
import com.twentygx.tgxcode.data.model.SchedulesPayload
import com.twentygx.tgxcode.data.model.ScratchFileContent
import com.twentygx.tgxcode.data.model.Scratchpad
import com.twentygx.tgxcode.data.model.SlashCommandsResponse
import com.twentygx.tgxcode.data.model.SnippetGroupBody
import com.twentygx.tgxcode.data.model.SnippetGroupResponse
import com.twentygx.tgxcode.data.model.SnippetReorder
import com.twentygx.tgxcode.data.model.SnippetResponse
import com.twentygx.tgxcode.data.model.SnippetsPayload
import com.twentygx.tgxcode.data.model.MarkReadRequest
import com.twentygx.tgxcode.data.model.MarkReadResponse
import com.twentygx.tgxcode.data.model.NotificationsResponse
import com.twentygx.tgxcode.data.model.Quota
import com.twentygx.tgxcode.data.model.QuotaRefreshResponse
import com.twentygx.tgxcode.data.model.TaskList
import com.twentygx.tgxcode.data.model.DecisionAnswered
import com.twentygx.tgxcode.data.model.DecisionAnsweredResponse
import com.twentygx.tgxcode.data.model.InboxDismissed
import com.twentygx.tgxcode.data.model.OrchestratorPayload
import com.twentygx.tgxcode.data.model.OrchestratorPut
import com.twentygx.tgxcode.data.model.OrchestratorsResponse
import com.twentygx.tgxcode.data.model.TaskFiled
import com.twentygx.tgxcode.data.model.TaskFiling
import com.twentygx.tgxcode.data.model.TaskWithdrawn
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.Prefs
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import com.twentygx.tgxcode.data.model.PhoneInstallsResponse
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.RangeResponse
import com.twentygx.tgxcode.data.model.SubagentResponse
import com.twentygx.tgxcode.data.model.QueueReorderRequest
import com.twentygx.tgxcode.data.model.QueueWriteResponse
import com.twentygx.tgxcode.data.model.SendRequest
import com.twentygx.tgxcode.data.model.RestartRequest
import com.twentygx.tgxcode.data.model.RestartResponse
import com.twentygx.tgxcode.data.model.SendResponse
import com.twentygx.tgxcode.data.model.SessionDetail
import com.twentygx.tgxcode.data.model.SessionImages
import com.twentygx.tgxcode.data.model.SessionPrsResponse
import com.twentygx.tgxcode.data.model.SessionSuggestions
import com.twentygx.tgxcode.data.model.SessionsResponse
import com.twentygx.tgxcode.data.model.SinceResponse
import com.twentygx.tgxcode.data.model.StopRequest
import com.twentygx.tgxcode.data.model.StopResponse
import com.twentygx.tgxcode.data.model.SubscribeRequest
import com.twentygx.tgxcode.data.model.SuggestionActRequest
import com.twentygx.tgxcode.data.model.SuggestionsResponse
import com.twentygx.tgxcode.data.model.SudoAnswerBody
import com.twentygx.tgxcode.data.model.TaskStatus
import com.twentygx.tgxcode.data.model.UploadedAttachment
import com.twentygx.tgxcode.data.install.PhoneInstallRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** No bytes for this long and a download attempt has failed, however the socket looks. */
const val DOWNLOAD_STALL_MILLIS = 60_000L

/** Download answers that leave nothing to resume: gone, unknown, or a range past the end. */
private val DROP_PARTIAL_ON = setOf(404, 410, 416)

/**
 * Every route this app uses, and only those.
 *
 * Deliberately absent, because the bridge refuses them for remote callers and a
 * button that always fails is worse than no button: the terminal routes, shutdown,
 * dev-server control, DevBrowser control, and reveal. Also absent because the phone
 * surface does not have it: the filesystem picker.
 *
 * `GET /api/taskboard` is absent for a third reason — not refused and not unwanted,
 * but redundant. See [suggestions].
 */
class BridgeApi(
    private val clients: BridgeClients,
    private val configProvider: () -> BridgeConfig?,
) {

    // ---------------------------------------------------------------- reading

    /**
     * The only unauthenticated route.
     *
     * Reaching it proves the bridge is up, DNS resolves, TLS works and cleartext is
     * permitted — and proves **nothing at all** about the token. That is what
     * [sessions] is for.
     */
    suspend fun health(): ApiResult<Health> = get("api/health", serializer())

    suspend fun sessions(limit: Int = 200): ApiResult<SessionsResponse> =
        get("api/sessions", serializer(), "limit" to limit.toString())

    /**
     * @param tail only the last N events. Mobile clients should always pass this: a
     *   60-turn session is ~1,800 events and half a megabyte of JSON, and none of
     *   the first 1,500 is why someone opened their phone.
     */
    suspend fun session(sessionId: String, tail: Int? = null, turns: Int? = null): ApiResult<SessionDetail> =
        get(
            "api/sessions/${sessionId.encoded()}",
            serializer(),
            "tail" to tail?.toString(),
            "turns" to turns?.toString(),
        )

    /**
     * The stretch above a window opened with `?turns=`. Both ends must be 0 or an
     * `offset` from `turns[]`; anything else is a 400.
     */
    suspend fun range(sessionId: String, from: Long, to: Long): ApiResult<RangeResponse> =
        get(
            "api/sessions/${sessionId.encoded()}/range",
            serializer(),
            "from" to from.toString(),
            "to" to to.toString(),
        )

    /** Every image in the conversation, once each — the Images sheet. No bytes. */
    suspend fun images(sessionId: String): ApiResult<SessionImages> =
        get("api/sessions/${sessionId.encoded()}/images", serializer())

    /**
     * One image's bytes, by the key [images] named. Raw, not JSON, and immutable: the
     * URL is the bytes' own hash, so a caller may keep them for good. A transcript
     * rewritten since the list was read answers 404 rather than a different picture.
     * Through [BridgeClients.api] like any route, so the token and the Access headers
     * go with it — which is why this cannot be a URL handed to an image loader.
     */
    suspend fun imageBytes(sessionId: String, key: String): ApiResult<ByteArray> =
        executeWith(clients.api, { config ->
            Request.Builder().url(config.url("api/sessions/${sessionId.encoded()}/images/$key")).get().build()
        }) { response -> response.body.bytes() }

    suspend fun subagent(sessionId: String, toolUseId: String, offset: Long = 0): ApiResult<SubagentResponse> =
        get(
            "api/sessions/${sessionId.encoded()}/subagent",
            serializer(),
            "toolUseId" to toolUseId,
            "offset" to offset.toString(),
        )

    /**
     * The catch-up call, and much cheaper than refetching.
     *
     * @param offset a **byte position**, taken from a previous response. Never
     *   computed locally.
     */
    suspend fun since(sessionId: String, offset: Long): ApiResult<SinceResponse> =
        get(
            "api/sessions/${sessionId.encoded()}/since",
            serializer(),
            "offset" to offset.toString(),
        )

    /**
     * Also pushed over SSE, so most of the time this is never called — but it is the
     * right answer to "what is happening right now", and reconnect reconciliation
     * needs it on demand.
     */
    suspend fun overview(): ApiResult<Overview> = get("api/overview", serializer())

    /**
     * Suggested follow-ups, newest first.
     *
     * @param status a **comma-separated list** — `open`, `started`, `dismissed`, or any
     *   combination. An unknown value is a 400 naming the three, so this is never built
     *   from free text.
     *
     * Deliberately not `GET /api/taskboard`, whose `suggested` column is this route
     * verbatim: the other three columns are a second answer to what the Needs-you
     * section and the Sessions list already say, and subscribing to its push would send
     * several hundred cards every three seconds to keep one column fresh.
     */
    suspend fun suggestions(
        status: String = TaskStatus.OPEN,
        limit: Int? = null,
    ): ApiResult<SuggestionsResponse> =
        get(
            "api/suggestions",
            serializer(),
            "status" to status,
            "limit" to limit?.toString(),
        )

    /**
     * One session's decisions, **without** the offers — which are already in the
     * transcript this client is holding. `GET /api/suggestions?session=` would return
     * both and cost a great deal more to learn the same thing.
     */
    suspend fun sessionSuggestions(sessionId: String): ApiResult<SessionSuggestions> =
        get("api/sessions/${sessionId.encoded()}/suggestions", serializer())

    /**
     * Read once, on connect. After that `drafts-changed` carries the whole payload, so
     * there is nothing left for this to answer that the stream has not already said.
     */
    suspend fun drafts(): ApiResult<DraftsResponse> = get("api/drafts", serializer())

    /**
     * One aggregate PR status per session — a glyph per row without a request per row.
     *
     * Read on connect, on pull-to-refresh, and on a slow poll tier. `prs-changed`
     * carries this whole payload thereafter, but **it is not a heartbeat**: it fires
     * only when the answer actually moves, and on a repository nobody is working in
     * that can be twenty minutes. A client that skipped this read would sit on
     * colourless glyphs until somebody happened to merge something.
     *
     * Costs nothing worth planning around — it reads a snapshot in the bridge's
     * memory and never waits on `gh`.
     */
    suspend fun prs(): ApiResult<PrsPayload> = get("api/prs", serializer())

    /**
     * One session's pull requests, resolved individually.
     *
     * A separate route from [prs] because it answers a different question: what became
     * of *each* PR, ranked settled-first, rather than which one of them most needs
     * attention. The conversation chips want the former and the rows the latter, and
     * the two go stale on different clocks. Also does not shell out.
     */
    suspend fun sessionPrs(sessionId: String): ApiResult<SessionPrsResponse> =
        get("api/sessions/${sessionId.encoded()}/prs", serializer())

    /** The agent's checklist for one session. An empty list is a 200, not a 404. */
    suspend fun taskList(sessionId: String): ApiResult<TaskList> =
        get("api/sessions/${sessionId.encoded()}/tasks", serializer())

    /** The user-level settings, for the project colours. Readable remotely; writing them is not. */
    suspend fun prefs(): ApiResult<Prefs> = get("api/prefs", serializer())

    /** Account-wide, and open to a remote caller on purpose. */
    suspend fun quota(): ApiResult<Quota> = get("api/quota", serializer())

    /**
     * @param scope `notable` (the default: loud rows only) or `all`.
     */
    suspend fun notifications(scope: String = "notable", limit: Int = 200): ApiResult<NotificationsResponse> =
        get("api/notifications", serializer(), "scope" to scope, "limit" to limit.toString())

    /** Shells out to `git status` behind a 15s cache; `refresh` drops it for this directory. */
    suspend fun changes(sessionId: String, refresh: Boolean = false): ApiResult<Changes> =
        get(
            "api/sessions/${sessionId.encoded()}/changes",
            serializer(),
            "refresh" to if (refresh) "1" else null,
        )

    /** @param mode `worktree` (the default), `staged` or `unstaged`. */
    suspend fun diff(sessionId: String, path: String, mode: String? = null): ApiResult<FileDiff> =
        get(
            "api/sessions/${sessionId.encoded()}/diff",
            serializer(),
            "path" to path,
            "mode" to mode,
        )

    suspend fun scratchpad(sessionId: String): ApiResult<Scratchpad> =
        get("api/sessions/${sessionId.encoded()}/scratchpad", serializer())

    suspend fun scratchFile(sessionId: String, dir: String, path: String): ApiResult<ScratchFileContent> =
        get(
            "api/sessions/${sessionId.encoded()}/scratchpad/file",
            serializer(),
            "dir" to dir,
            "path" to path,
        )

    /** Read once; `later-changed` carries the whole payload after that. */
    suspend fun later(): ApiResult<LaterPayload> = get("api/later", serializer())

    /** Unfiltered: the event that follows is never filtered, so the first read is not either. */
    suspend fun snippets(): ApiResult<SnippetsPayload> = get("api/snippets", serializer())

    suspend fun schedules(): ApiResult<SchedulesPayload> = get("api/schedules", serializer())

    /** The bridge's English for a cron expression — never a second parser in a client. */
    suspend fun describeCron(cron: String, once: Boolean = false): ApiResult<CronDescription> =
        get("api/schedules/describe", serializer(), "cron" to cron, "once" to if (once) "1" else null)

    suspend fun slashCommands(sessionId: String? = null, cwd: String? = null): ApiResult<SlashCommandsResponse> =
        get("api/slash-commands", serializer(), "session" to sessionId, "cwd" to cwd)

    // ---------------------------------------------------------------- writing

    /** `permissionMode` and `at` are required; `at` in the future and within 30 days. */
    suspend fun createLater(sessionId: String, body: LaterCreate): ApiResult<LaterResponse> =
        post("api/sessions/${sessionId.encoded()}/later", body, serializer())

    /** `409` once it is no longer pending — editing a sent message would be editing the past. */
    suspend fun patchLater(id: String, body: LaterPatch): ApiResult<LaterResponse> =
        patch("api/later/${id.encoded()}", body, serializer())

    /** The only way to cancel one: there is no `cancelled` state. */
    suspend fun deleteLater(id: String): ApiResult<JsonObject> =
        delete("api/later/${id.encoded()}", serializer())

    suspend fun sendLaterNow(id: String): ApiResult<JsonObject> =
        post("api/later/${id.encoded()}/send", JsonObject(emptyMap()), serializer())

    /**
     * Built as JSON by the caller, like [createSchedule]: on a PATCH `null` is a value
     * (`groupId: null` ungroups, `permissionMode: null` is inherit) and absence is not,
     * and a data class under `explicitNulls = false` can only say the second.
     */
    suspend fun createSnippet(body: JsonObject): ApiResult<SnippetResponse> =
        post("api/snippets", body, serializer())

    suspend fun patchSnippet(id: String, body: JsonObject): ApiResult<SnippetResponse> =
        patch("api/snippets/${id.encoded()}", body, serializer())

    suspend fun deleteSnippet(id: String): ApiResult<JsonObject> =
        delete("api/snippets/${id.encoded()}", serializer())

    suspend fun reorderSnippets(body: SnippetReorder): ApiResult<SnippetsPayload> =
        post("api/snippets/reorder", body, serializer())

    suspend fun createSnippetGroup(body: SnippetGroupBody): ApiResult<SnippetGroupResponse> =
        post("api/snippet-groups", body, serializer())

    suspend fun patchSnippetGroup(id: String, body: SnippetGroupBody): ApiResult<SnippetGroupResponse> =
        patch("api/snippet-groups/${id.encoded()}", body, serializer())

    suspend fun deleteSnippetGroup(id: String): ApiResult<JsonObject> =
        delete("api/snippet-groups/${id.encoded()}", serializer())

    /**
     * A genuine partial, built by the caller as JSON so only the fields that changed
     * are sent — a schedule in `dontAsk` can still be paused from a phone, but a body
     * that restated its mode would be refused.
     */
    suspend fun createSchedule(body: JsonObject): ApiResult<ScheduleResponse> =
        post("api/schedules", body, serializer())

    suspend fun patchSchedule(id: String, body: JsonObject): ApiResult<ScheduleResponse> =
        patch("api/schedules/${id.encoded()}", body, serializer())

    suspend fun deleteSchedule(id: String): ApiResult<JsonObject> =
        delete("api/schedules/${id.encoded()}", serializer())

    /** The gate is skipped and tonight's slot left alone; the mode refusal still applies. */
    suspend fun runSchedule(id: String): ApiResult<ScheduleRunResponse> =
        post("api/schedules/${id.encoded()}/run", JsonObject(emptyMap()), serializer())

    /**
     * Run the beacon now. A `409` carries the payload too (`needsSetup` or `running`),
     * and the caller reads the next scheduled reading instead of reporting a failure.
     */
    suspend fun refreshQuota(): ApiResult<QuotaRefreshResponse> =
        post("api/quota/refresh", JsonObject(emptyMap()), serializer())

    suspend fun markRead(body: MarkReadRequest): ApiResult<MarkReadResponse> =
        post("api/notifications/read", body, serializer())

    suspend fun clearNotifications(): ApiResult<JsonObject> =
        delete("api/notifications", serializer())

    suspend fun subscribe(body: SubscribeRequest): ApiResult<JsonObject> =
        post("api/subscribe", body, serializer())

    suspend fun createSession(body: CreateSessionRequest): ApiResult<CreateSessionResponse> =
        post("api/sessions", body, serializer())

    suspend fun send(sessionId: String, body: SendRequest): ApiResult<SendResponse> =
        post("api/sessions/${sessionId.encoded()}/send", body, serializer())

    /**
     * Raw bytes, one file per request — not JSON, so the bridge's cap is about the
     * file. [contentType] is only a hint: the bridge sniffs images from the bytes.
     * `413` is the cap (10MB from a phone), `400` an empty file or a name it refuses.
     */
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        bytes: ByteArray,
        contentType: String,
    ): ApiResult<UploadedAttachment> =
        postBytes(
            "api/sessions/${sessionId.encoded()}/attachments", bytes, contentType, serializer(),
            "name" to name,
        )

    /** The same upload for a session that does not exist yet: upload, then create. */
    suspend fun uploadAttachmentTo(
        cwd: String,
        name: String,
        bytes: ByteArray,
        contentType: String,
    ): ApiResult<UploadedAttachment> =
        postBytes("api/attachments", bytes, contentType, serializer(), "cwd" to cwd, "name" to name)

    /**
     * @param hard SIGTERM then SIGKILL rather than asking the CLI to interrupt itself.
     *   What a stalled turn gets: a process that has gone silent is the one least
     *   likely to answer an interrupt.
     */
    suspend fun stop(sessionId: String, hard: Boolean = false): ApiResult<StopResponse> =
        post(
            "api/sessions/${sessionId.encoded()}/stop",
            StopRequest(hard = hard.takeIf { it }),
            serializer(),
        )

    /**
     * Replace the session's process, keeping the conversation, so it starts with the
     * tool list as it is now. `409` with `busy` while a turn or an ask is in flight;
     * `409` with `background: n` when [force] is off and background tasks would stop.
     */
    suspend fun restart(sessionId: String, force: Boolean = false): ApiResult<RestartResponse> =
        post(
            "api/sessions/${sessionId.encoded()}/restart",
            RestartRequest(force = force.takeIf { it }),
            serializer(),
        )

    /**
     * Drop one queued message. `409` means it already went — including a `handed` one
     * the running turn read first — which is an outcome, not a failure.
     */
    suspend fun dropQueued(sessionId: String, queueId: String): ApiResult<QueueWriteResponse> =
        delete("api/sessions/${sessionId.encoded()}/queue/${queueId.encoded()}", serializer())

    /** The whole new order. A `handed` id keeps its place at the front whatever is sent. */
    suspend fun reorderQueue(sessionId: String, ids: List<String>): ApiResult<QueueWriteResponse> =
        post("api/sessions/${sessionId.encoded()}/queue/reorder", QueueReorderRequest(ids), serializer())

    suspend fun setFlags(sessionId: String, body: FlagsRequest): ApiResult<FlagsResponse> =
        post("api/sessions/${sessionId.encoded()}/flags", body, serializer())

    /** A hard delete of the transcript. `409` while a turn is running. */
    suspend fun deleteSession(sessionId: String): ApiResult<JsonObject> =
        delete("api/sessions/${sessionId.encoded()}", serializer())

    /**
     * Answers all three kinds of ask.
     *
     * `404` means no live process. **`409` means it was already answered** — by
     * another window, or the desktop's notification buttons. Callers must treat that
     * as an ordinary outcome.
     */
    suspend fun permission(
        sessionId: String,
        body: PermissionRequestBody,
        client: OkHttpClient = clients.api,
    ): ApiResult<JsonObject> =
        post("api/sessions/${sessionId.encoded()}/permission", body, serializer(), client)

    /**
     * Hand the password to the sudo waiting on ask [id] — `POST /api/sudo/:id/answer`.
     *
     * Through the same [post] and [executeWith] as every other route, which log the
     * method, the path and the status and never a body, so the password reaches the
     * socket and nothing else. `SudoLoggingTest` holds that.
     *
     * `403 {remote: true}` while `sudo.allowRemote` is off; `409` when it was already
     * answered or has gone. A `200` means handed to sudo, not correct: a wrong password
     * comes back as a fresh ask with `attempt` + 1.
     */
    suspend fun answerSudo(id: String, password: String): ApiResult<JsonObject> =
        post("api/sudo/${id.encoded()}/answer", SudoAnswerBody(password), serializer())

    /** Refuse it. Open to a remote caller whatever `allowRemote` says: refusing grants nothing. */
    suspend fun denySudo(id: String): ApiResult<JsonObject> =
        post("api/sudo/${id.encoded()}/deny", JsonObject(emptyMap()), serializer())

    /**
     * Start, dismiss, or undo a decision about one suggested follow-up.
     *
     * @param sessionId the session that **raised** the offer — never the one a start
     *   created. The decision store is keyed session → toolUseId, and posting to the
     *   new session would file the decision against a conversation that never made it.
     *
     * An empty [SuggestionActRequest] is the undo, and `explicitNulls = false` is what
     * makes it encode as `{}` rather than `{"status": null}` — which would be a 400.
     */
    suspend fun actOnSuggestion(
        sessionId: String,
        toolUseId: String,
        body: SuggestionActRequest,
    ): ApiResult<JsonObject> =
        post(
            "api/sessions/${sessionId.encoded()}/suggestions/${toolUseId.encoded()}",
            body,
            serializer(),
        )

    /**
     * `400` for a cwd that does not exist or an empty prompt, `403` for a mode a remote
     * caller may not set, `409` past **200 drafts** — a ceiling, not a lifetime budget.
     */
    suspend fun createDraft(body: DraftBody): ApiResult<DraftResponse> =
        post("api/drafts", body, serializer())

    /**
     * **A genuine partial**: a field left out of the body is left alone. That is the
     * whole reason this is a PATCH, and why [DraftBody] carries no `model` and no
     * `title` — a phone with no picker for either must not be able to clear them.
     */
    suspend fun patchDraft(id: String, body: DraftBody): ApiResult<DraftResponse> =
        patch("api/drafts/${id.encoded()}", body, serializer())

    /** A hard delete of a small file; deleting twice is a 404, not worth handling. */
    suspend fun deleteDraft(id: String): ApiResult<JsonObject> =
        delete("api/drafts/${id.encoded()}", serializer())

    /**
     * Runs the create call that was written down, **and deletes the draft** — but only
     * after the process starts. So a `400` here means *nothing happened* and the draft
     * is still listed, which is the whole reason this is one route rather than the
     * client doing create-then-delete.
     *
     * Same response as [createSession], `status` included: a **whole runner status
     * object**, not a word describing the outcome.
     */
    suspend fun startDraft(id: String): ApiResult<CreateSessionResponse> =
        post("api/drafts/${id.encoded()}/start", JsonObject(emptyMap()), serializer())

    // ------------------------------------------------------------ orchestrators

    /**
     * Every orchestrator a task can go to, live ones first. Also *the* answer to "is
     * there one to send to" — the session list's `orchestrator` field is not, as it
     * includes the archived ones a task should not go to.
     */
    suspend fun orchestrators(): ApiResult<OrchestratorsResponse> =
        get("api/orchestrators", serializer())

    /** Never a 404: a session that is not one answers `enabled: false`. */
    suspend fun orchestrator(sessionId: String): ApiResult<OrchestratorPayload> =
        get("api/sessions/${sessionId.encoded()}/orchestrator", serializer())

    /**
     * Turn the role on or off, or change settings — which merge field by field, a bad
     * value leaving that field alone. `409` for a worker. Answers the payload.
     */
    suspend fun putOrchestrator(sessionId: String, body: OrchestratorPut): ApiResult<OrchestratorPayload> =
        put("api/sessions/${sessionId.encoded()}/orchestrator", body, serializer())

    /** `409` while usage is still over the cutoff, naming the window and the reading. */
    suspend fun resumeOrchestrator(sessionId: String): ApiResult<OrchestratorPayload> =
        post("api/sessions/${sessionId.encoded()}/orchestrator/resume", JsonObject(emptyMap()), serializer())

    /** Settles it as dismissed. It does not answer a worker's ask — that is the worker's own route. */
    suspend fun dismissInboxItem(sessionId: String, itemId: String): ApiResult<InboxDismissed> =
        post(
            "api/sessions/${sessionId.encoded()}/orchestrator/inbox/${itemId.encoded()}/dismiss",
            JsonObject(emptyMap()),
            serializer(),
        )

    /**
     * Records that the user answered one of the brief's questions. **It does not
     * deliver the answer** — that is a `Re: …` message through [send], which goes
     * first. `404` usually means the orchestrator already dropped it: done.
     */
    suspend fun answerDecision(sessionId: String, decisionId: String, text: String): ApiResult<DecisionAnsweredResponse> =
        post(
            "api/sessions/${sessionId.encoded()}/orchestrator/decisions/${decisionId.encoded()}/answered",
            DecisionAnswered(text),
            serializer(),
        )

    /**
     * Hand it a task. Nothing is started: the orchestrator decides. The draft, if one
     * was named, is removed only after filing, so a refusal keeps it. `409` past 20
     * open tasks; `403` for a mode a phone may not set.
     */
    suspend fun fileTask(sessionId: String, body: TaskFiling): ApiResult<TaskFiled> =
        post("api/sessions/${sessionId.encoded()}/orchestrator/tasks", body, serializer())

    /** Take it back: it becomes a draft again — the same one, when it came from one. */
    suspend fun withdrawTask(sessionId: String, itemId: String): ApiResult<TaskWithdrawn> =
        post(
            "api/sessions/${sessionId.encoded()}/orchestrator/tasks/${itemId.encoded()}/withdraw",
            JsonObject(emptyMap()),
            serializer(),
        )

    // ---------------------------------------------------------- phone installs

    /** Newest first — what a client reads on (re)connect to catch a `pending` it missed. */
    suspend fun phoneInstalls(): ApiResult<PhoneInstallsResponse> =
        get("api/phone-installs", serializer())

    /**
     * Report a state. `downloading` is the claim on a `pending` record, and a `409` on
     * it means another phone has it.
     *
     * @param viewer what the bridge keys a claim on (`X-TGXCode-Viewer`). Sent here and
     *   nowhere else: on any other route it says "this client polls and shows asks",
     *   which is not what this app means by it.
     */
    suspend fun phoneInstallStatus(
        id: String,
        body: PhoneInstallStatus,
        viewer: String,
    ): ApiResult<JsonObject> = execute(serializer(), clients.api) { config ->
        Request.Builder()
            .url(config.url("api/phone-installs/${id.encoded()}/status"))
            .header("X-TGXCode-Viewer", viewer)
            .post(AppJson.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()
    }

    /** Put a record back to `pending`. `410`: the APK is gone. `409`: it is installed. */
    suspend fun retryPhoneInstall(id: String): ApiResult<JsonObject> =
        post("api/phone-installs/${id.encoded()}/retry", JsonObject(emptyMap()), serializer())

    /**
     * Stream the APK to [dest], hashing it on the way, so a large one never sits in
     * memory and the check costs no second read. Verification is the caller's: this
     * reports what arrived, not whether it is right.
     *
     * **Resuming.** With [resumeFrom] > 0 the request carries `Range: bytes=N-` and an
     * `If-Range` of the record's sha256, which is the bridge's ETag. A `206` from N is
     * added to what is on disk (hashed first, so the digest covers the whole file); a
     * `200` means the bridge sent all of it, and the file starts again.
     *
     * **Stalls.** No bytes for [stallMillis] fails the attempt, whatever the socket is
     * doing — the read timeout cannot see a call stuck before its first read, or a
     * process that was frozen and is not reading at all. Cancelling the coroutine
     * cancels the call too, rather than waiting out a blocked read.
     *
     * A transfer cut off part-way leaves its partial file for the next attempt to
     * resume from; only an answer that says there is nothing to resume deletes it.
     *
     * @param onProgress bytes on disk so far, and the whole length (0 if unknown).
     *   Called on the IO thread for every buffer: throttle it.
     */
    suspend fun downloadPhoneInstall(
        id: String,
        dest: java.io.File,
        resumeFrom: Long = 0,
        sha256: String? = null,
        stallMillis: Long = DOWNLOAD_STALL_MILLIS,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): ApiResult<DownloadedApk> = withContext(Dispatchers.IO) {
        val config = configProvider() ?: return@withContext ApiResult.Http(0, "No bridge configured")
        val request = Request.Builder()
            .url(config.url("api/phone-installs/${id.encoded()}/apk"))
            .get()
            .apply {
                if (resumeFrom > 0) {
                    header("Range", "bytes=$resumeFrom-")
                    sha256?.takeIf { it.isNotBlank() }?.let { header("If-Range", "\"$it\"") }
                }
            }
            .build()
        val call = clients.download.newCall(request)
        val lastByteAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope<ApiResult<DownloadedApk>> {
            // Also the cancellation path: when the caller gives up, this child is
            // cancelled with it, and its `finally` breaks the blocked read below.
            val watchdog = launch {
                try {
                    while (true) {
                        delay(1_000)
                        if (System.currentTimeMillis() - lastByteAt.get() > stallMillis) {
                            stalled.set(true)
                            call.cancel()
                            break
                        }
                    }
                } finally {
                    if (!isActive) call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    if (AccessChallenge.isChallenge(response)) {
                        Tgx.w("GET ${request.url.encodedPath} -> ${response.code}: Cloudflare Access refused it")
                        return@coroutineScope ApiResult.AccessDenied(response.code)
                    }
                    if (!response.isSuccessful) {
                        val text = response.body.string()
                        Tgx.w("GET ${request.url.encodedPath} -> ${response.code}")
                        // Nothing to resume from: gone, unknown, or a range past the end.
                        if (response.code in DROP_PARTIAL_ON) dest.delete()
                        return@coroutineScope ApiResult.Http(response.code, errorOf(text, response.code))
                    }
                    val contentRange = response.header("Content-Range")
                    val answer = PhoneInstallRules.rangeAnswer(response.code, contentRange, resumeFrom)
                    if (answer == PhoneInstallRules.RangeAnswer.MISMATCH) {
                        dest.delete()
                        return@coroutineScope ApiResult.Http(
                            response.code,
                            "The bridge answered a resume with the wrong range ($contentRange)",
                        )
                    }
                    val append = answer == PhoneInstallRules.RangeAnswer.APPEND
                    val body = response.body
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    dest.parentFile?.mkdirs()
                    if (append) {
                        dest.inputStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                written += n
                            }
                        }
                    }
                    val length = body.contentLength().takeIf { it >= 0 }
                    val total = (if (append) PhoneInstallRules.rangeTotal(contentRange) else null)
                        ?: length?.let { written + it }
                        ?: 0L
                    if (append) Tgx.d("download $id: resuming at $written of $total")
                    lastByteAt.set(System.currentTimeMillis())
                    onProgress(written, total)
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(dest, append).use { output ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                lastByteAt.set(System.currentTimeMillis())
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                written += n
                                onProgress(written, total)
                            }
                        }
                    }
                    ApiResult.Ok(
                        DownloadedApk(
                            file = dest,
                            size = written,
                            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                            headerSha256 = response.header("X-SHA256"),
                            contentLength = length,
                            resumedFrom = if (append) resumeFrom else 0,
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // A read broken by our own cancel is the caller giving up, not a failure.
                ensureActive()
                val cause = if (stalled.get()) {
                    java.net.SocketTimeoutException("No data for ${stallMillis / 1000}s — the transfer stalled")
                } else {
                    t
                }
                Tgx.w("download $id failed with ${dest.length()} bytes on disk", cause)
                ApiResult.Offline(cause)
            } finally {
                watchdog.cancel()
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> get(
        path: String,
        deserializer: KSerializer<T>,
        vararg query: Pair<String, String?>,
    ): ApiResult<T> = execute(deserializer, clients.api) { config ->
        Request.Builder().url(config.url(path, *query)).get().build()
    }

    private suspend inline fun <reified B, T> post(
        path: String,
        body: B,
        deserializer: KSerializer<T>,
        client: OkHttpClient = clients.api,
    ): ApiResult<T> = execute(deserializer, client) { config ->
        val json = AppJson.encodeToString(body)
        Request.Builder()
            .url(config.url(path))
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private suspend fun <T> postBytes(
        path: String,
        bytes: ByteArray,
        contentType: String,
        deserializer: KSerializer<T>,
        vararg query: Pair<String, String?>,
    ): ApiResult<T> = execute(deserializer, clients.upload) { config ->
        Request.Builder()
            .url(config.url(path, *query))
            .post(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
    }

    private suspend inline fun <reified B, T> patch(
        path: String,
        body: B,
        deserializer: KSerializer<T>,
    ): ApiResult<T> = execute(deserializer, clients.api) { config ->
        val json = AppJson.encodeToString(body)
        Request.Builder()
            .url(config.url(path))
            .patch(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private suspend inline fun <reified B, T> put(
        path: String,
        body: B,
        deserializer: KSerializer<T>,
    ): ApiResult<T> = execute(deserializer, clients.api) { config ->
        val json = AppJson.encodeToString(body)
        Request.Builder()
            .url(config.url(path))
            .put(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private suspend fun <T> delete(
        path: String,
        deserializer: KSerializer<T>,
    ): ApiResult<T> = execute(deserializer, clients.api) { config ->
        Request.Builder().url(config.url(path)).delete().build()
    }

    private suspend fun <T> execute(
        deserializer: KSerializer<T>,
        client: OkHttpClient,
        build: (BridgeConfig) -> Request,
    ): ApiResult<T> = executeWith(client, build) { response ->
        AppJson.decodeFromString(deserializer, response.body.string())
    }

    /** [execute] with the success body read by [read] — JSON for every route but a raw one. */
    private suspend fun <T> executeWith(
        client: OkHttpClient,
        build: (BridgeConfig) -> Request,
        read: (okhttp3.Response) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config == null) {
            Tgx.w("request skipped: no bridge configured")
            return@withContext ApiResult.Http(0, "No bridge configured")
        }
        try {
            client.newCall(build(config)).execute().use { response ->
                // Before the status check, because one of the shapes is a 200 login
                // page — and before reading the body, which is Cloudflare's, not ours.
                if (AccessChallenge.isChallenge(response)) {
                    Tgx.w(
                        "${response.request.method} ${response.request.url.encodedPath} -> " +
                            "${response.code}: Cloudflare Access refused it" +
                            (AccessChallenge.redirectHost(response)?.let { " (redirect to $it)" } ?: ""),
                    )
                    return@withContext ApiResult.AccessDenied(response.code)
                }
                if (AccessChallenge.isTunnelDown(response)) {
                    Tgx.w("${response.request.method} ${response.request.url.encodedPath} -> ${response.code}: tunnel has no bridge behind it")
                    return@withContext ApiResult.Http(
                        response.code,
                        "Cloudflare could not reach the bridge (HTTP ${response.code}) — " +
                            "is the bridge machine on and cloudflared running?",
                    )
                }
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    Tgx.w("${response.request.method} ${response.request.url.encodedPath} -> ${response.code}")
                    return@withContext ApiResult.Http(response.code, errorOf(text, response.code), text)
                }
                ApiResult.Ok(read(response))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Tgx.w("request failed", t)
            ApiResult.Offline(t)
        }
    }

    /** The bridge's failures carry `{"error": "…"}`; fall back to the status line. */
    private fun errorOf(body: String, code: Int): String = runCatching {
        AppJson.parseToJsonElement(body).let { it as JsonObject }["error"]?.jsonPrimitive?.content
    }.getOrNull() ?: "HTTP $code"
}

/** What arrived, measured on the way in. */
data class DownloadedApk(
    val file: java.io.File,
    val size: Long,
    /** Lower-case hex, of the bytes written. */
    val sha256: String,
    /** The bridge's own `X-SHA256`, if it sent one. */
    val headerSha256: String?,
    val contentLength: Long?,
    /** Where this answer started, when it continued a partial file; else 0. */
    val resumedFrom: Long = 0,
)

private fun String.encoded(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
