package com.twentygx.tgxcode.data.net

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * Adds the header the bridge quietly requires.
 *
 * The bridge rejects **every non-GET `/api/` request** that arrives without
 * `X-TGXCode-Client` — a CSRF guard, 403 with `{"error": "missing client header"}`.
 * Without it the whole write surface fails: subscribe, send, permission, create.
 * `docs/api.md` §*Authentication* documents it.
 *
 * `X-Claude-Sessions-Client` is the same header under the app's name from before the
 * rename to TGXCode. A current bridge accepts either; one started before the rename
 * knows only the old, so both are sent — the desktop's own clients do the same.
 * Drop the old one once no pre-rename bridge can be on the other end.
 *
 * Sent on every request rather than just the non-GETs, because a header that is only
 * sometimes right is a header someone will forget.
 */
private class ClientHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header("X-TGXCode-Client", "1")
                .header("X-Claude-Sessions-Client", "1")
                .build(),
        )
}

/**
 * Bearer auth, read fresh from [tokenProvider] on every call so that switching
 * profiles takes effect immediately rather than on next app start.
 */
private class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}

/**
 * A Cloudflare Access service token, for a bridge that sits behind Access.
 *
 * Read fresh on every call, like [AuthInterceptor], and on the base client for the
 * same reason the other two are: `BridgeSse` builds its own `Request` and never
 * passes through `BridgeApi`, so the interceptor list is the only point that reaches
 * every call — the stream, the held stream and a notification's answer included.
 *
 * Both headers or neither. [BridgeConfig.access] is null for a half-set pair.
 */
private class AccessInterceptor(
    private val accessProvider: () -> Pair<String, String>?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val (id, secret) = accessProvider() ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder()
                .header("CF-Access-Client-Id", id)
                .header("CF-Access-Client-Secret", secret)
                .build(),
        )
    }
}

/**
 * One client per process, in two flavours built with `newBuilder()` so they share a
 * dispatcher, a connection pool and their threads.
 *
 * There is deliberately **no HTTP cache**. Every response here is live state, and a
 * cache is only a way to render something stale.
 */
class BridgeClients(
    tokenProvider: () -> String?,
    accessProvider: () -> Pair<String, String>? = { null },
    /** When the stream last delivered a byte, `: ping` included. */
    val streamPulse: StreamPulse = StreamPulse(),
) {

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // No redirects, for two reasons, both about Cloudflare Access.
        //
        // OkHttp drops `Authorization` when a redirect changes host, but nothing
        // else — so following Access's 302 to `<team>.cloudflareaccess.com` would
        // hand that host the service token's client secret. And a followed redirect
        // lands on a 200 login page, which hides the refusal [AccessChallenge]
        // exists to see.
        //
        // Nothing legitimate is lost: no `/api/` route redirects (`docs/api.md` names
        // only `/pair`, which this app never calls), and an `http://` origin that
        // Cloudflare would upgrade should fail visibly rather than send a token in
        // the clear first.
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ClientHeaderInterceptor())
        .addInterceptor(AuthInterceptor(tokenProvider))
        .addInterceptor(AccessInterceptor(accessProvider))
        .build()

    /** Ordinary requests: bounded end to end. */
    val api: OkHttpClient = base

    /**
     * The live channel.
     *
     * The read timeout is **finite on purpose**, and this is the least obvious
     * decision in the app. The instinct for a long-lived stream is
     * `readTimeout(0)` — never time out. That is only correct when the server has no
     * heartbeat. This one pings every 25 seconds (`bridge/server.js`), and
     * `okhttp-sse` silently discards `: ping` comments without a callback, so the
     * heartbeat is invisible at the API level and cannot be watched directly.
     *
     * A 40s read timeout turns that ping into a dead-peer detector for free: a
     * healthy stream never trips it, and a half-open socket — a cell handoff, a NAT
     * binding evicted, the desktop asleep — surfaces within 40 seconds instead of
     * never.
     *
     * That matters more than it sounds. With `readTimeout(0)` the app sits looking
     * connected while the bridge has already dropped us, and `pool.hasViewer` is
     * false — which means **every ask is being auto-denied**. It is the worst
     * failure this app has, and one line prevents it.
     */
    val stream: OkHttpClient = base.newBuilder()
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(PulseInterceptor(streamPulse))
        .build()

    /**
     * The live channel on a transport that will not carry it.
     *
     * Same socket, with the dead-peer detector above deliberately switched off — and
     * that is not the regression it looks like. Some networks buffer SSE instead of
     * passing it through (see [BridgeSse]), so *zero bytes* is the expected steady
     * state rather than evidence of a half-open socket, and a 40s read timeout would
     * close this connection every 40 seconds on the dot.
     *
     * Closing it is the one thing that must not happen. `bridge/server.js:813`
     * registers the client the instant `/api/events` is handled, before it has read a
     * byte — the buffering is downstream of the bridge — so the socket still makes
     * `pool.hasViewer()` true, and `bridge/runner.js:734` auto-denies every ask the
     * moment it is false. The stream delivers nothing and is load-bearing anyway.
     *
     * What replaces the timeout is the poll: a round that fails is visible within
     * `POLL_MILLIS`, which is better than the 40 seconds it gave up.
     */
    val heldStream: OkHttpClient = stream.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Answering from a notification runs inside `BroadcastReceiver.goAsync()`, which
     * allows roughly ten seconds before an ANR. Finish cleanly inside that.
     */
    val notificationAction: OkHttpClient = base.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Attachment uploads. Up to 10MB over whatever the phone has, which the 20s write
     * and 30s whole-call bounds above would cut off on a weak cell link; a stuck upload
     * is still a failed chip with a Retry, never a spinner forever.
     */
    val upload: OkHttpClient = base.newBuilder()
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * APKs from the phone-installs queue. Tens of megabytes over a cell link through a
     * tunnel, so **no whole-call bound**: the 15 minutes this used to have would cut
     * off a slow transfer that was still moving, and on a 40 MB file that is about
     * 45 KB/s. What bounds it instead is stalling — a read that waits a minute here,
     * and `BridgeApi.downloadPhoneInstall`'s own watchdog, which also catches a socket
     * that never fails a read because it never gets as far as one.
     */
    val download: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun shutdown() {
        base.dispatcher.executorService.shutdown()
        base.connectionPool.evictAll()
    }

    /**
     * Sockets pooled against a network that has gone away are still in the pool, and
     * OkHttp has no way to know. Called from the connectivity callback.
     */
    fun evictPooledConnections() {
        base.connectionPool.evictAll()
    }
}

/**
 * The stream's last sign of life, on `elapsedRealtime`.
 *
 * `okhttp-sse` swallows the bridge's `: ping` comments without a callback, so a quiet
 * stream and a dead one look the same from the listener. Bytes do not: every ping is a
 * read on the socket. And unlike the read timeout, whose timer stops while the phone
 * sleeps, `elapsedRealtime` counts the sleep, so a dead socket shows as stale the
 * moment anything wakes the phone. See `BackgroundRules.streamStale`.
 */
class StreamPulse(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    @Volatile
    var lastByteAt: Long = 0L
        private set

    fun beat() {
        lastByteAt = clock()
    }
}

/** Counts the stream's bytes as they are read, without touching them. */
private class PulseInterceptor(private val pulse: StreamPulse) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        pulse.beat()
        val body = response.body
        val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it > 0) pulse.beat() }
        }.buffer()
        return response.newBuilder()
            .body(
                object : ResponseBody() {
                    override fun contentType(): MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): BufferedSource = counted
                    override fun close() = body.close()
                },
            )
            .build()
    }
}
