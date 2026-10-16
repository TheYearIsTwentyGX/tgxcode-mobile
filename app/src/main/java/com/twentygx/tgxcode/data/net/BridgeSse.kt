package com.twentygx.tgxcode.data.net

import com.twentygx.tgxcode.data.model.StreamEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** What the stream tells the repository. */
sealed interface SseSignal {
    data object Open : SseSignal
    data class Message(val event: StreamEvent) : SseSignal
    /**
     * @param accessDenied the response was Cloudflare Access refusing the stream, not
     *   the bridge. See [AccessChallenge].
     */
    data class Closed(
        val cause: Throwable?,
        val httpStatus: Int?,
        val accessDenied: Boolean = false,
    ) : SseSignal
}

/**
 * The live channel, as a cold [Flow].
 *
 * Uses `okhttp-sse` rather than a hand-rolled reader. Its `ServerSentEventReader` is
 * a couple of hundred lines of *correct* SSE framing — multi-line `data:`
 * accumulation, CR/LF/CRLF, BOM handling — and every bug in a hand-rolled version
 * shows up as "the app randomly misses an event", which is close to undebuggable.
 *
 * It deliberately does not auto-retry, which is what we want: a generic retry cannot
 * know that reconnecting here means `hello` → reload the list → re-subscribe →
 * `/since`. Recovery is inherently application-level, so the flow just closes and
 * lets the caller decide.
 *
 * The one thing lost is visibility of the `: ping` comments, which the reader
 * discards without a callback. That is handled by the finite read timeout on
 * [BridgeClients.stream] instead — see the comment there, it is the subtler half of
 * this design.
 *
 * **What this cannot detect is a transport that buffers the stream.** Some networks —
 * a Cloudflare quick tunnel, a corporate or hotel proxy — hold server-sent events
 * instead of passing them through, and they fail silently: HTTP 200, the right
 * content type, and not one byte for 75 seconds. Cloudflare strips the
 * `X-Accel-Buffering: no` header the bridge sets, so no header fixes it from either
 * end. Nor can it be spotted by watching for silence, because `: ping` is invisible
 * here: an idle-but-healthy stream and a buffered one look identical.
 *
 * `hello` is the only signal that separates them, and the bridge writes it before the
 * ping timer exists — 0.04s on loopback, 0.29s through a named tunnel. So the caller
 * decides once, up front, on a timeout: see `HELLO_GRACE_MILLIS` in
 * `BridgeRepository`. Having decided, it keeps collecting from [stream] with
 * `held = true` rather than cancelling, because the socket is what keeps this client
 * a viewer.
 */
class BridgeSse(
    private val clients: BridgeClients,
    private val configProvider: () -> BridgeConfig?,
) {
    /**
     * @param held open it on [BridgeClients.heldStream], i.e. with no read timeout,
     *   for a transport already known to buffer. Nothing will arrive on it; it is
     *   open so that the bridge still counts a viewer.
     */
    fun stream(held: Boolean = false): Flow<SseSignal> = callbackFlow {
        val config = configProvider()
        if (config == null) {
            trySend(SseSignal.Closed(IllegalStateException("No bridge configured"), null))
            close()
            return@callbackFlow
        }

        val request = Request.Builder()
            .url(config.url("api/events"))
            .header("Accept", "text/event-stream")
            // Proxies that buffer turn a live board into a frozen one.
            .header("Cache-Control", "no-cache")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(SseSignal.Open)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                SseCodec.decode(type, data)?.let { trySend(SseSignal.Message(it)) }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(SseSignal.Closed(null, null))
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                // A redirect to the Access login, or the login page itself served
                // as 200 `text/html` — which okhttp-sse rejects as the wrong content
                // type — both land here with the response attached.
                val denied = response?.let(AccessChallenge::isChallenge) == true
                trySend(SseSignal.Closed(t, response?.code, denied))
                close()
            }
        }

        val source = EventSources.createFactory(if (held) clients.heldStream else clients.stream)
            .newEventSource(request, listener)

        awaitClose { source.cancel() }
    }
}
