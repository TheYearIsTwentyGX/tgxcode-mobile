package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * An agent asking for a secret by name — `docs/api.md` §*Secrets*.
 *
 * Its own thing, not an [Ask]: it is answered to `/api/secrets/asks/:id/…`, never to
 * `/permission`, where its [id] means nothing. It arrives as `secret-ask`, on
 * `runner.secretAsk`, and on a board card's `ask` (with `kind: "secret"`) when that is
 * all the session is waiting on.
 *
 * **Nothing here can carry a value.** The bridge has no event that does, and this class
 * has no field that could.
 */
@Serializable
data class SecretAsk(
    /** Equal to [requestId]; the routes take it. */
    val id: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    /** What is being asked for. Always drawn: it is the thing you are filling in. */
    val name: String = "",
    /** The agent's own sentence, up to 500 characters, and possibly empty. Its words, not a fact. */
    val why: String = "",
    val cwd: String? = null,
    /** The project root the value is saved under. */
    val project: String? = null,
    /** The name was set while this waited. A phone never replaces a value, so this card is read-only. */
    val exists: Boolean = false,
    /** Epoch ms. No expiry: it lasts until answered, denied, or the agent stops waiting. */
    val askedAt: Long? = null,
    /** `secrets.allowRemote` as the bridge saw it when it sent this. Absent reads as off. */
    val remoteAnswer: Boolean = false,
)

/** `{id, requestId, sessionId, name, outcome, reason?}` — take the card down. */
object SecretOutcome {
    fun word(name: String?, outcome: String?, reason: String? = null): String =
        (name?.takeIf { it.isNotBlank() } ?: "Secret") + " — " + when (outcome) {
            "saved" -> "Saved."
            "denied" -> "Denied."
            "cancelled" -> "Withdrawn" + (reason?.takeIf { it.isNotBlank() }?.let { " — $it." } ?: ".")
            else -> "Answered."
        }
}

/**
 * The one body that carries a value.
 *
 * [toString] is overridden because a data class's default prints every field, and a
 * body that turns up in a log line, an exception message or a crash report would print
 * the value in it. Serialization reads the field, not this.
 */
@Serializable
data class SecretAnswerBody(val value: String) {
    override fun toString(): String = "SecretAnswerBody(value=•••)"
}

/** The `secrets` slice of the user-level prefs. */
@Serializable
data class SecretPrefs(
    /** Whether a remote caller — this phone — may send a value. Off until turned on at the desk. */
    val allowRemote: Boolean = false,
)

enum class SecretCardMode {
    /** A masked field, Save and Deny. */
    Enter,

    /** `secrets.allowRemote` is off: says where to turn it on, and Deny. */
    Off,

    /** The name was set while the ask waited: a phone cannot replace it. Deny. */
    AlreadySet,

    /** This origin would carry the value in clear text. Deny. */
    Insecure,
}

/**
 * Which card this phone draws.
 *
 * The transport comes first, on purpose, and is the one check the bridge cannot make for
 * us: by the time it sees a plain-HTTP request the value has already crossed the network.
 *
 * @param remote whether the bridge sees this client as remote (`/api/health`). Null
 *   before the first health read, which is read as remote: a field the bridge refuses
 *   is worse than a read-only card that turns answerable a moment later.
 * @param allowRemote the latest `secrets.allowRemote` from prefs, when known. It is pushed
 *   on every change, so it outranks the [SecretAsk.remoteAnswer] the ask was sent with.
 * @param encrypted [com.twentygx.tgxcode.data.net.BridgeConfig.encryptedOrigin].
 */
fun secretCardMode(ask: SecretAsk, remote: Boolean?, allowRemote: Boolean?, encrypted: Boolean): SecretCardMode = when {
    !encrypted -> SecretCardMode.Insecure
    remote == false -> SecretCardMode.Enter
    !(allowRemote ?: ask.remoteAnswer) -> SecretCardMode.Off
    ask.exists -> SecretCardMode.AlreadySet
    else -> SecretCardMode.Enter
}

/** What a read-only card says instead of a field; null when it has a field. */
fun secretCardHint(ask: SecretAsk, mode: SecretCardMode): String? = when (mode) {
    SecretCardMode.Enter -> null
    SecretCardMode.Off -> "Turn on in Settings → Secrets at your desk to enter this here. Until then it can only be typed there."
    SecretCardMode.AlreadySet -> "${ask.name} is already set. Change it at your desk."
    SecretCardMode.Insecure -> "This connection is not encrypted, so a secret can't be sent over it. Enter it at your desk."
}
