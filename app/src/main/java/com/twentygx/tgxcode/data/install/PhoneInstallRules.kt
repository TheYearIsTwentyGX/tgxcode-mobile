package com.twentygx.tgxcode.data.install

import android.content.pm.PackageInstaller
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallState
import com.twentygx.tgxcode.data.model.PhoneInstallStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The decisions in [PhoneInstaller] that need no device, kept apart so they can be
 * pinned by tests: which records to pick up, when one stopped being ours, how to resume
 * a download, whether it is the file that was asked for, what a PackageInstaller
 * status means, and whether a self-update landed.
 */
object PhoneInstallRules {

    /**
     * What a catch-up read should pick up: every `pending` record, and every record
     * still `downloading` that this phone claimed ([ownClaims]) but is not working on
     * — the process died, or the answer to the claim was lost on the way back. Nothing
     * in hand, oldest first.
     *
     * Oldest first because the list is a queue — a build sent before another should go
     * on before it, so the newer one is what is left installed. The bridge answers
     * newest first, so this sorts rather than trusting the order.
     *
     * A claimed record left out of this is never looked at again: the bridge only
     * releases it after three minutes of silence, and before this took own claims the
     * phone stopped there for good.
     */
    fun toPickUp(installs: List<PhoneInstall>, ownClaims: Set<String>, inHand: Set<String>): List<PhoneInstall> =
        installs
            .filter { it.id !in inHand }
            .filter {
                it.state == PhoneInstallState.PENDING ||
                    (it.state == PhoneInstallState.DOWNLOADING && it.id in ownClaims)
            }
            .sortedBy { it.createdAt }

    /**
     * Whether [record], heard while this phone is working on it, says the work is no
     * longer this phone's — so the download should stop rather than finish into a
     * record somebody else now holds.
     *
     * - `cancelled`: taken back at the desk. Always, claimed or not.
     * - `failed`: the bridge gave up on it (three claims that went quiet).
     * - `pending` with no progress: the bridge released the claim after three quiet
     *   minutes. A `pending` that still carries progress is not a release.
     *
     * The last two only count once the claim has landed, and only when newer than
     * [since] — the bridge's `updatedAt` as of the claim. The `pending` that started
     * this work, arriving late on the stream after a catch-up already found it, is
     * not a release.
     */
    fun noLongerOurs(record: PhoneInstall, claimed: Boolean, since: Long): Boolean {
        if (record.state == PhoneInstallState.CANCELLED) return true
        if (!claimed || record.updatedAt <= since) return false
        return record.state == PhoneInstallState.FAILED ||
            (record.state == PhoneInstallState.PENDING && record.progress == null)
    }

    /** Whether the Installs screen offers Retry: anything stopped, or stuck. */
    fun canRetry(record: PhoneInstall): Boolean = when (record.state) {
        PhoneInstallState.FAILED, PhoneInstallState.CANCELLED, PhoneInstallState.NEEDS_ACTION -> true
        in PhoneInstallState.WORKING -> record.stalled
        else -> false
    }

    // ------------------------------------------------------------- download

    /**
     * Where to ask the bridge to start, given [partial] bytes already on disk from an
     * attempt that was cut off. Zero means the whole file: there is nothing to keep, or
     * what is there is already as long as the APK (or longer) and so cannot be a
     * prefix worth trusting — it was checked and failed, or is something else.
     */
    fun resumeFrom(partial: Long, size: Long): Long =
        if (partial > 0 && (size <= 0 || partial < size)) partial else 0

    enum class RangeAnswer {
        /** `206` starting where we asked: add to the file. */
        APPEND,
        /** `200`: the whole file, so start the file again — the If-Range did not match. */
        RESTART,
        /** A `206` for some other range. Nothing safe to do with it but drop the partial. */
        MISMATCH,
    }

    /**
     * What a download answer means for a request that asked to start at [from].
     *
     * @param contentRange the `Content-Range` header, `bytes a-b/total`.
     */
    fun rangeAnswer(code: Int, contentRange: String?, from: Long): RangeAnswer {
        if (code != 206) return RangeAnswer.RESTART
        if (from <= 0) return RangeAnswer.MISMATCH
        val start = contentRange?.let { CONTENT_RANGE.find(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()
        return if (start == from) RangeAnswer.APPEND else RangeAnswer.MISMATCH
    }

    /** The `/total` of a `Content-Range`, when the server knows it. */
    fun rangeTotal(contentRange: String?): Long? =
        contentRange?.let { CONTENT_RANGE.find(it.trim()) }?.groupValues?.get(3)?.toLongOrNull()

    private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

    /**
     * Null when [actualSize] and [actualSha256] are what [record] promised, else the
     * sentence the record's `message` should carry. The bridge's `X-SHA256` header is
     * checked too when present: it describes the bytes being served, so disagreeing
     * with the record means the record is not what is on the desk any more.
     */
    fun verify(
        record: PhoneInstall,
        actualSize: Long,
        actualSha256: String,
        headerSha256: String? = null,
    ): String? {
        if (record.size > 0 && actualSize != record.size) {
            return "Download was $actualSize bytes, expected ${record.size} — the transfer was cut short or altered."
        }
        val want = record.sha256.trim().lowercase()
        val got = actualSha256.trim().lowercase()
        if (want.isEmpty()) return "The bridge sent no sha256 to check the download against."
        if (got != want) {
            return "Download did not match: sha256 ${got.take(12)}…, expected ${want.take(12)}…"
        }
        val header = headerSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (header != null && header != want) {
            return "The bridge served a file whose sha256 (${header.take(12)}…) is not the one it recorded."
        }
        return null
    }

    /** What to tell the bridge, and the person, about a PackageInstaller result. */
    data class Outcome(val state: String, val message: String?)

    /**
     * @param platformMessage `EXTRA_STATUS_MESSAGE`, which on a failure carries the
     *   `INSTALL_FAILED_*` code — the only place a signature mismatch is named outright.
     * @param app the name to put in the sentence.
     */
    fun outcomeOf(status: Int, platformMessage: String?, app: String): Outcome {
        val detail = platformMessage?.trim()?.takeIf { it.isNotEmpty() }
        fun failed(text: String) =
            Outcome(PhoneInstallState.FAILED, if (detail != null) "$text ($detail)" else text)

        return when {
            status == PackageInstaller.STATUS_SUCCESS -> Outcome(PhoneInstallState.INSTALLED, null)
            status == PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Outcome(PhoneInstallState.NEEDS_ACTION, "Waiting for a tap on the phone to install $app.")
            // Checked before the plain status codes: an update signed with another key
            // arrives as CONFLICT on most builds and INCOMPATIBLE on some, and either
            // way the only fix is the same.
            isSignatureMismatch(status, detail) -> failed(
                "$app is already installed with a different signature (signing key). " +
                    "Uninstall it on the phone first, then send it again.",
            )
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> failed("Cancelled on the phone.")
            status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                failed("The phone blocked installing $app (a device policy or another installer owns it).")
            status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                failed("$app conflicts with a package already on the phone.")
            status == PackageInstaller.STATUS_FAILURE_INVALID -> failed("The APK is invalid or corrupt.")
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> failed("Not enough storage on the phone.")
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                failed("$app is not compatible with this phone (its ABI or minimum Android version).")
            status == PackageInstaller.STATUS_FAILURE_TIMEOUT -> failed("The install timed out.")
            else -> failed("Install failed (status $status).")
        }
    }

    private fun isSignatureMismatch(status: Int, detail: String?): Boolean {
        val text = detail.orEmpty()
        if (text.contains("UPDATE_INCOMPATIBLE", ignoreCase = true)) return true
        if (text.contains("signatures do not match", ignoreCase = true)) return true
        if (text.contains("INCONSISTENT_CERTIFICATES", ignoreCase = true)) return true
        // A bare CONFLICT with no detail is still most often this, but not reliably
        // enough to tell someone to uninstall — so it keeps the generic sentence.
        return false
    }

    /**
     * Whether an install we committed went through, judged without its result — above
     * all a self-update, whose result never arrives because it killed the process that
     * committed it.
     *
     * Either says yes: the package was updated at or after the moment we claimed the
     * record, or it now carries the version we sent and that is not the version that
     * was running when we sent it. The clock is the one that covers a rebuild that
     * kept its version name, which is the everyday case for a debug build; the name
     * alone would call a failed reinstall of the same version a success.
     */
    fun installLanded(claim: Claim, installedVersion: String?, lastUpdateTime: Long): Boolean {
        if (claim.claimedAt > 0 && lastUpdateTime >= claim.claimedAt) return true
        val sent = claim.versionName ?: return false
        return sent == installedVersion && sent != claim.previousVersionName
    }
}

/**
 * One record this phone has claimed and not yet seen finish. The only thing that
 * survives the process — which a self-update is guaranteed to kill.
 */
@Serializable
data class Claim(
    val id: String,
    val claimedAt: Long,
    val label: String = "",
    val packageName: String? = null,
    val versionName: String? = null,
    /** What this package was at before, for judging a self-update. */
    val previousVersionName: String? = null,
    /** Set while the install waits on the "Install unknown apps" grant. */
    val waitingForPermission: Boolean = false,
    /**
     * A report that has not reached the bridge yet — a result, or the `failed` from a
     * download cut off by the bridge restarting, which is exactly when posting it fails
     * too. Sent again on the next catch-up; without it the record says `downloading`
     * on the desk for good.
     */
    val outbox: PhoneInstallStatus? = null,
) {
    companion object {
        private val listSerializer = ListSerializer(serializer())

        fun encode(claims: List<Claim>): String = AppJson.encodeToString(listSerializer, claims)

        /** Anything unreadable is no claims — losing track beats failing to start. */
        fun decode(text: String?): List<Claim> =
            if (text.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching { AppJson.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
            }
    }
}

/**
 * Which download progress is worth a heartbeat: about every [intervalMillis], or every
 * [step] of the file, whichever comes first — and the last byte, always.
 *
 * The bridge flags a record stalled after 90s without a word and releases it after
 * three minutes, so the heartbeat is what keeps a slow transfer this phone's; it is
 * also all the desk sees of how far along it is. Every buffer would be hundreds of
 * posts a second, so most are dropped. Nothing is sent while the byte count stands
 * still: a heartbeat with no progress would hide exactly the stall it is for.
 */
class ProgressThrottle(
    private val intervalMillis: Long = 2_000,
    private val step: Double = 0.05,
) {
    private var lastAt = Long.MIN_VALUE
    private var lastBytes = -1L

    fun shouldSend(bytes: Long, total: Long, now: Long): Boolean {
        if (bytes == lastBytes) return false
        val first = lastBytes < 0
        val done = total > 0 && bytes >= total
        val due = now - lastAt >= intervalMillis
        val stepped = total > 0 && bytes - lastBytes >= (total * step).toLong().coerceAtLeast(1)
        if (!(first || done || due || stepped)) return false
        lastAt = now
        lastBytes = bytes
        return true
    }
}
