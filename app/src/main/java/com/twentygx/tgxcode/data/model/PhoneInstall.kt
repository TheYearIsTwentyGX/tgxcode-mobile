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
)

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
)
