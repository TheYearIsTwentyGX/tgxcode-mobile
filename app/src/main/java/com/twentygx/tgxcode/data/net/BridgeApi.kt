package com.twentygx.tgxcode.data.net

import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.CreateSessionRequest
import com.twentygx.tgxcode.data.model.CreateSessionResponse
import com.twentygx.tgxcode.data.model.DraftBody
import com.twentygx.tgxcode.data.model.DraftResponse
import com.twentygx.tgxcode.data.model.DraftsResponse
import com.twentygx.tgxcode.data.model.Health
import com.twentygx.tgxcode.data.model.Overview
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import com.twentygx.tgxcode.data.model.PrsPayload
import com.twentygx.tgxcode.data.model.SendRequest
import com.twentygx.tgxcode.data.model.SendResponse
import com.twentygx.tgxcode.data.model.SessionDetail
import com.twentygx.tgxcode.data.model.SessionPrsResponse
import com.twentygx.tgxcode.data.model.SessionSuggestions
import com.twentygx.tgxcode.data.model.SessionsResponse
import com.twentygx.tgxcode.data.model.SinceResponse
import com.twentygx.tgxcode.data.model.StopResponse
import com.twentygx.tgxcode.data.model.SubscribeRequest
import com.twentygx.tgxcode.data.model.SuggestionActRequest
import com.twentygx.tgxcode.data.model.SuggestionsResponse
import com.twentygx.tgxcode.data.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Every route this app uses, and only those.
 *
 * Deliberately absent, because the bridge refuses them for remote callers and a
 * button that always fails is worse than no button: the terminal routes, shutdown,
 * dev-server control, DevBrowser control, and reveal. Also absent because the phone
 * surface does not have them: queue management, flags, delete, and the filesystem
 * picker.
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
    suspend fun session(sessionId: String, tail: Int? = null): ApiResult<SessionDetail> =
        get(
            "api/sessions/${sessionId.encoded()}",
            serializer(),
            "tail" to tail?.toString(),
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

    // ---------------------------------------------------------------- writing

    suspend fun subscribe(body: SubscribeRequest): ApiResult<JsonObject> =
        post("api/subscribe", body, serializer())

    suspend fun createSession(body: CreateSessionRequest): ApiResult<CreateSessionResponse> =
        post("api/sessions", body, serializer())

    suspend fun send(sessionId: String, body: SendRequest): ApiResult<SendResponse> =
        post("api/sessions/${sessionId.encoded()}/send", body, serializer())

    suspend fun stop(sessionId: String): ApiResult<StopResponse> =
        post("api/sessions/${sessionId.encoded()}/stop", JsonObject(emptyMap()), serializer())

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
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (config == null) {
            Tgx.w("request skipped: no bridge configured")
            return@withContext ApiResult.Http(0, "No bridge configured")
        }
        try {
            client.newCall(build(config)).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Tgx.w("${response.request.method} ${response.request.url.encodedPath} -> ${response.code}")
                    return@withContext ApiResult.Http(response.code, errorOf(text, response.code))
                }
                ApiResult.Ok(AppJson.decodeFromString(deserializer, text))
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

private fun String.encoded(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
