package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonPrimitive

/**
 * A `sudo -A` an agent ran, waiting on a password — `docs/api.md` §*Approving sudo*.
 *
 * Its own thing, not an [Ask]: it is answered to `/api/sudo/:id/…`, never to
 * `/permission`, where its [id] means nothing, and it can be up while a permission ask
 * is. It arrives as `sudo-ask`, on `runner.sudoAsk`, and on a board card's `ask` (with
 * `kind: "sudo"`) when that is all the session is waiting on.
 *
 * **Nothing here can carry a password.** The bridge has no event that does, and this
 * class has no field that could.
 */
@Serializable
data class SudoAsk(
    /** Equal to [requestId]; the routes take it. */
    val id: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    /** What sudo will run, shell-quoted. Empty for `sudo -s`/`-i` with no command. */
    val command: String = "",
    /**
     * The waiting sudo process's own command line, read from `/proc` by the bridge — not
     * anything the agent said. This is the thing being approved, so it is drawn whole.
     */
    val sudoLine: String = "",
    val argv: List<String> = emptyList(),
    val cwd: String? = null,
    /** A `-D`/`--chdir` sudo will move to first. */
    val chdir: String? = null,
    val user: String? = null,
    val group: String? = null,
    /** `run`, `shell`, `login`, `edit`, `list` or `validate`. */
    val mode: String? = null,
    /** Approving it approves whatever that shell goes on to run, which is not shown. */
    val runsShell: Boolean = false,
    /**
     * The whole Bash command the sudo is part of. sudo caches the password for the rest
     * of it, so `sudo -A true && sudo rm …` is approved by approving `true`.
     */
    val context: String? = null,
    /** 1, then one higher each time the same sudo asks again after a wrong password. */
    val attempt: Int = 1,
    /** Epoch ms. No expiry: it lasts as long as sudo waits. */
    val askedAt: Long? = null,
    /** `sudo.allowRemote` as the bridge saw it when it sent this. */
    val remoteAnswer: Boolean = false,
) {
    /** Who it runs as, `user:group` when a group was asked for. */
    val runsAs: String get() = (user?.takeIf { it.isNotBlank() } ?: "root") + (group?.let { ":$it" } ?: "")

    /** Where it runs: the `-D` directory, saying where it was given from. */
    val where: String
        get() = chdir?.let { "$it (from ${cwd ?: "unknown"})" } ?: (cwd ?: "unknown")

    /** The Bash command, when it says more than the sudo line does; else null. */
    val wholeCommand: String?
        get() = context?.takeIf { it.isNotBlank() && it.trim() != sudoLine.trim() }

    /** What is drawn as the thing approved: the sudo line, else the argv joined. */
    val line: String get() = sudoLine.ifBlank { argv.joinToString(" ") }
}

/** `{id, requestId, sessionId, outcome, reason?}` — take the card down. */
object SudoOutcome {
    fun word(outcome: String?, reason: String? = null): String = "sudo — " + when (outcome) {
        "approved" -> "Password sent."
        "denied" -> "Denied."
        "cancelled" -> "Withdrawn" + (reason?.takeIf { it.isNotBlank() }?.let { " — $it." } ?: ".")
        else -> "Answered."
    }
}

/**
 * The one body that carries a password.
 *
 * [toString] is overridden because a data class's default prints every field, and a
 * body that turns up in a log line, an exception message or a crash report would print
 * the password in it. Serialization reads the field, not this.
 */
@Serializable
data class SudoAnswerBody(val password: String) {
    override fun toString(): String = "SudoAnswerBody(password=•••)"
}

/** The `sudo` slice of the user-level prefs. */
@Serializable
data class SudoPrefs(
    val enabled: Boolean = false,
    /** Whether a remote caller — this phone — may send the password. */
    val allowRemote: Boolean = false,
)

enum class SudoCardMode {
    /** A password field, Approve and Deny. */
    Approve,

    /** "Approve at the desktop", and Deny. */
    ReadOnly,
}

/**
 * Whether this phone may send the password: the desktop card's
 * `!state.remote || ask.remoteAnswer`.
 *
 * @param remote whether the bridge sees this client as remote (`/api/health`). Null
 *   before the first health read, which is read as remote: an Approve the bridge refuses
 *   is worse than a read-only card that turns answerable a moment later.
 * @param allowRemote the latest `sudo.allowRemote` from prefs, when known. It is pushed
 *   on every change, so it outranks the [SudoAsk.remoteAnswer] the ask was sent with.
 */
fun sudoCardMode(ask: SudoAsk, remote: Boolean?, allowRemote: Boolean?): SudoCardMode =
    if (remote == false || (allowRemote ?: ask.remoteAnswer)) SudoCardMode.Approve else SudoCardMode.ReadOnly

/**
 * A board card's `ask`, which is a [SudoAsk] when `kind` is `sudo`.
 *
 * The board has one `ask` slot for both kinds, so the sudo fields would otherwise be
 * dropped by [Ask]'s decoder. This copies the whole object into [Ask.sudo] before
 * decoding, so a card keeps everything the sudo card draws.
 */
object BoardAskSerializer : JsonTransformingSerializer<Ask>(Ask.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val kind = runCatching { element["kind"]?.jsonPrimitive?.content }.getOrNull()
        if (kind != "sudo" || "sudo" in element) return element
        return JsonObject(element + ("sudo" to element))
    }

    override fun transformSerialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonObject(element - "sudo") else element
}
