package com.twentygx.tgxcode.data.install

import android.content.pm.PackageInstaller
import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.PhoneInstall
import com.twentygx.tgxcode.data.model.PhoneInstallState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The decisions in [PhoneInstaller] that need no device, kept apart so they can be
 * pinned by tests: which records to pick up, whether a download is the file that was
 * asked for, what a PackageInstaller status means, and whether a self-update landed.
 */
object PhoneInstallRules {

    /**
     * What a catch-up read should claim: `pending`, not already in hand, oldest first.
     *
     * Oldest first because the list is a queue — a build sent before another should go
     * on before it, so the newer one is what is left installed. The bridge answers
     * newest first, so this sorts rather than trusting the order.
     */
    fun toClaim(installs: List<PhoneInstall>, inHand: Set<String>): List<PhoneInstall> =
        installs
            .filter { it.state == PhoneInstallState.PENDING && it.id !in inHand }
            .sortedBy { it.createdAt }

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
