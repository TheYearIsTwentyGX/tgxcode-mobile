package com.twentygx.tgxcode.data.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

/**
 * Tells a Cloudflare Access refusal apart from an answer by the bridge.
 *
 * With Access in front of the tunnel, a request without a valid service token never
 * reaches the bridge. Cloudflare answers instead — with a redirect to
 * `<team>.cloudflareaccess.com` (the browser-login flow), a 403 HTML page, or, with
 * strict service-token authentication, a bare 401/403. None of those is the bridge's
 * JSON, and the one that matters most is the 401: read naively it is "not paired",
 * which sends someone to re-pair a device whose token is perfectly good.
 *
 * Headers only, never the body, so the same check works on an ordinary response and
 * on the one `okhttp-sse` hands to `onFailure` — whose body may already be gone.
 *
 * The rules, any one of which is enough — the same ones `docs/api.md` §*Cloudflare
 * Access, when it is in front* gives a client:
 *  1. **A 3xx to Access or to another host** — `Location` on `*.cloudflareaccess.com`,
 *     under `/cdn-cgi/access/`, or on any host other than the one asked. No bridge
 *     route redirects off its own origin. Only visible because [BridgeClients] does
 *     not follow redirects.
 *  2. **A 401/403 carrying an Access hint** — any `cf-access-*` header, or a
 *     `WWW-Authenticate` that names Cloudflare or `cf-access`. The bridge sends no
 *     `WWW-Authenticate` at all.
 *  3. **A 401/403 on `/api/` that is not JSON.** The bridge's own refusals there are
 *     always `{"error": …}` (`docs/api.md` §Authentication), so an HTML or
 *     empty-typed one came from something in front of it. Scoped to `/api/` because
 *     the bridge's `/pair` *does* answer a bad token with an HTML 401.
 *  4. **HTML on an `/api/` path** below 500. The bridge never serves HTML there, so
 *     this is a login page served with a 200.
 *
 * Not a 5xx. Cloudflare's `530` "error 1033" is also an HTML page on an `/api/`
 * path, and it means the tunnel has no `cloudflared` behind it — the bridge machine
 * is unreachable, which is offline, not a token problem. See [isTunnelDown].
 */
object AccessChallenge {

    fun isChallenge(response: Response): Boolean {
        val code = response.code
        if (code in 300..399) {
            return redirectsAway(response.header("Location"), response.request.url.host)
        }
        if (code >= 500) return false

        val onApi = response.request.url.encodedPath.startsWith("/api/")
        val refused = code == 401 || code == 403
        if (refused) {
            if (response.headers.names().any { it.startsWith("cf-access", ignoreCase = true) }) {
                return true
            }
            val challenge = response.header("WWW-Authenticate").orEmpty()
            if (challenge.contains("cloudflare", ignoreCase = true) ||
                challenge.contains("cf-access", ignoreCase = true)
            ) {
                return true
            }
            if (onApi && !isJson(response)) return true
        }

        return onApi && isHtml(response)
    }

    /** Only the host, for a log line — the query string carries a redirect URL and a nonce. */
    fun redirectHost(response: Response): String? =
        response.header("Location")?.let(::resolve)?.host

    /**
     * Cloudflare answering for a bridge it cannot reach: `530` ("error 1033") when no
     * `cloudflared` is connected, or any other 5xx HTML page on an `/api/` path. The
     * bridge's own 5xx are JSON.
     */
    fun isTunnelDown(response: Response): Boolean =
        response.code >= 500 && isHtml(response) &&
            response.request.url.encodedPath.startsWith("/api/")

    private fun redirectsAway(location: String?, requestHost: String): Boolean {
        if (location.isNullOrBlank()) return false
        if (location.contains("/cdn-cgi/access/")) return true
        // A relative Location stays on this host, which is not Access.
        val host = location.toHttpUrlOrNull()?.host ?: return false
        return host == "cloudflareaccess.com" || host.endsWith(".cloudflareaccess.com") ||
            !host.equals(requestHost, ignoreCase = true)
    }

    private fun resolve(location: String) =
        location.toHttpUrlOrNull() ?: "https://relative.invalid$location".toHttpUrlOrNull()

    private fun isJson(response: Response): Boolean =
        response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)

    private fun isHtml(response: Response): Boolean =
        response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)
}
