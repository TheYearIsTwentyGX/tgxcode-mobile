package com.twentygx.tgxcode.data.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the bridge is and what proves we may talk to it.
 *
 * Both travel together on purpose. Keeping the origin and the token as two
 * independent settings makes it possible for a profile switch mid-flight to send one
 * profile's token to another profile's host, which is exactly the mistake worth
 * designing out.
 */
data class BridgeConfig(
    val baseUrl: HttpUrl,
    val token: String,
) {
    fun url(path: String, vararg query: Pair<String, String?>): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments(path.trimStart('/'))
            .apply {
                query.forEach { (k, v) -> v?.let { addQueryParameter(k, it) } }
            }
            .build()

    val origin: String get() = "${baseUrl.scheme}://${baseUrl.host}" +
        if (baseUrl.port != HttpUrl.defaultPort(baseUrl.scheme)) ":${baseUrl.port}" else ""
}

/**
 * Turns whatever the user pasted into a config.
 *
 * A native client does not need the `/pair` cookie handshake at all — `docs/api.md`
 * says so plainly: "it should store the token and send the header." But the desktop
 * already generates a perfectly good `https://host/pair?token=…` link, and pasting
 * that whole thing is far kinder than retyping 43 characters of base64url on a
 * phone keyboard. So accept the link and take it apart.
 */
object PairingInput {

    /** 32 random bytes as base64url is 43 characters. A warning, not a hard gate. */
    private val TOKEN_SHAPE = Regex("^[A-Za-z0-9_-]{20,}$")

    data class Parsed(
        val origin: String,
        val token: String?,
        val note: String? = null,
    )

    /**
     * Accepts, in order of how likely it is to be what someone pasted:
     *  - `https://tgxcode.com/pair?token=…`                  (the pairing link)
     *  - `https://tg-dylanh.taild5c420.ts.net/pair?token=…`  (…from the other origin)
     *  - `http://10.11.64.43:45888/m`                        (any URL on the bridge)
     *  - `tgxcode.com`                                       (a bare host)
     *
     * Nothing here knows which origin is which, and that is the point: the origin is
     * whatever survives stripping the path, so a third one needs no code.
     */
    fun parse(input: String): Parsed? {
        val text = input.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null

        val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) {
            text
        } else {
            // A bare host is almost always a tunnel or tailnet name; both are HTTPS.
            "https://$text"
        }

        val url = withScheme.toHttpUrlOrNull() ?: return null
        val token = url.queryParameter("token")?.takeIf { it.isNotBlank() }

        // Strip path, query and fragment: only the origin identifies the bridge.
        val origin = url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .removeSuffix("/")

        val note = when {
            token != null && !TOKEN_SHAPE.matches(token) ->
                "That token does not look like one of the bridge's. Check it before saving."
            url.host == "127.0.0.1" || url.host == "localhost" ->
                "That is a loopback address, so it means *this phone* — which only works " +
                    "with `adb reverse tcp:45888 tcp:45888` running."
            else -> null
        }

        return Parsed(origin = origin, token = token, note = note)
    }

    fun build(origin: String, token: String): BridgeConfig? {
        val url = origin.trim().removeSuffix("/").toHttpUrlOrNull() ?: return null
        val clean = token.trim()
        if (clean.isEmpty()) return null
        return BridgeConfig(url, clean)
    }
}
