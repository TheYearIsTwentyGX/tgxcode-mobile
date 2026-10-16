package com.twentygx.tgxcode.core

/**
 * The HTTP status has to survive all the way to the UI, because two of them are not
 * errors in the ordinary sense:
 *
 * - **401** means this device is not paired — a whole screen, not a toast.
 * - **409** on answering an ask means somebody already answered it, on the desktop
 *   or in another window. `docs/api.md`: "an ordinary outcome, not an error to
 *   shout about."
 *
 * And one response is not the bridge's at all: [AccessDenied] is Cloudflare Access
 * refusing the request before it reached the bridge. It gets its own case because
 * its 401/403 would otherwise read as "not paired" — the wrong screen, and the wrong
 * fix.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Http(val status: Int, val error: String?) : ApiResult<Nothing>
    data class Offline(val cause: Throwable) : ApiResult<Nothing>
    data class AccessDenied(val status: Int) : ApiResult<Nothing>
}

const val ACCESS_DENIED_MESSAGE =
    "Cloudflare Access refused this request — check the service token in Settings."

val ApiResult<*>.isAccessDenied: Boolean
    get() = this is ApiResult.AccessDenied

val ApiResult<*>.isUnauthorized: Boolean
    get() = this is ApiResult.Http && status == 401

val ApiResult<*>.isConflict: Boolean
    get() = this is ApiResult.Http && status == 409

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

/** What to put in a toast. Never the raw stack trace. */
fun ApiResult<*>.message(): String = when (this) {
    is ApiResult.Ok -> ""
    is ApiResult.Http -> error?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    is ApiResult.Offline -> friendlyNetworkMessage(cause)
    is ApiResult.AccessDenied -> ACCESS_DENIED_MESSAGE
}

/**
 * Cleartext blocking is the one network failure worth naming precisely: it is
 * indistinguishable from "the server is down" in the default message, and it costs
 * an hour to work out that the network security config is the reason.
 */
fun friendlyNetworkMessage(cause: Throwable): String {
    val text = cause.message.orEmpty()
    return when {
        text.contains("Cleartext HTTP traffic", ignoreCase = true) ->
            "This build only allows plain HTTP to a short list of dev addresses. " +
                "Use one of the HTTPS origins instead."
        cause is java.net.UnknownHostException -> "Can't resolve that host."
        cause is java.net.ConnectException -> "Nothing is listening there."
        cause is javax.net.ssl.SSLException -> "TLS failed: ${text.ifBlank { "bad certificate" }}"
        cause is java.net.SocketTimeoutException -> "Timed out."
        else -> text.ifBlank { "Network error" }
    }
}
