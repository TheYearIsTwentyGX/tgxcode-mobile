package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * An APK the desktop has queued for this phone to install.
 *
 * A queue entry, not a command: the bridge files it `pending`, broadcasts it as
 * `phone_install`, and waits for whichever phone picks it up — now, or the next time
 * one connects. Every report back is a whole record on the stream again.
 */
@Serializable
data class PhoneInstall(
    val id: String,
    val fileName: String = "",
    val size: Long = 0,
    val sha256: String = "",
    /** What the desktop calls it — the build, not necessarily the app's own name. */
    val label: String = "",
    val sessionId: String? = null,
    /** Epoch ms. Never moves. */
    val createdAt: Long = 0,
    /** One of [PhoneInstallState]. */
    val state: String = PhoneInstallState.PENDING,
    val message: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val updatedAt: Long = 0,
    /** How far the claiming phone's download has got, from its heartbeats. */
    val progress: PhoneInstallProgress? = null,
    /** The bridge's flag: `downloading` or `installing` with no word for 90s. */
    val stalled: Boolean = false,
    /** How many times a phone has claimed it. The bridge fails it after three. */
    val attempts: Int = 0,
)

/** Bytes downloaded of [total]; [total] is 0 when the length is not known. */
@Serializable
data class PhoneInstallProgress(val bytes: Long, val total: Long = 0) {
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

object PhoneInstallState {
    const val PENDING = "pending"
    const val DOWNLOADING = "downloading"
    const val INSTALLING = "installing"
    const val NEEDS_ACTION = "needs_action"
    const val INSTALLED = "installed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** Nothing will happen to these again. `failed` is not one: a retry may follow. */
    val FINAL = setOf(INSTALLED, CANCELLED)

    /** A phone is working on these, so the bridge expects to hear from it. */
    val WORKING = setOf(DOWNLOADING, INSTALLING)
}

/** `GET /api/phone-installs` — newest first. */
@Serializable
data class PhoneInstallsResponse(val installs: List<PhoneInstall> = emptyList())

/** `POST /api/phone-installs/:id/status`. Absent fields are omitted, never sent as null. */
@Serializable
data class PhoneInstallStatus(
    val state: String,
    val message: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    /** With `downloading` only: the heartbeat that says the transfer is moving. */
    val progress: PhoneInstallProgress? = null,
)
