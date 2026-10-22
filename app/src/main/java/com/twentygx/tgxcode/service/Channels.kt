package com.twentygx.tgxcode.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.twentygx.tgxcode.R

/**
 * Five channels, split so the user can mute the chatter and keep the one that
 * matters. Importance is user-owned after creation, so these are created once and
 * never touched again — if the user has lowered [ASKS], the Settings screen says so
 * rather than trying to put it back.
 */
object Channels {
    /** A session is blocked on you. The one channel that must not be quiet. */
    const val ASKS = "asks"

    /** The ongoing foreground-service notice. */
    const val CONNECTION = "connection"

    /** Turn errors, warnings, messages that failed to send. */
    const val ACTIVITY = "activity"

    /** A phone install waiting on you: a tap to confirm, or the unknown-apps grant. */
    const val INSTALLS = "installs"

    /** How a phone install ended. Quiet: you asked for it at the desk. */
    const val INSTALL_RESULTS = "install_results"

    fun create(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                ASKS,
                context.getString(R.string.channel_asks_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_asks_desc)
                enableVibration(true)
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION,
                context.getString(R.string.channel_connection_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_connection_desc)
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                ACTIVITY,
                context.getString(R.string.channel_activity_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_activity_desc)
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                INSTALLS,
                context.getString(R.string.channel_installs_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_installs_desc)
                setShowBadge(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                INSTALL_RESULTS,
                context.getString(R.string.channel_install_results_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_install_results_desc)
                setShowBadge(false)
            },
        )
    }
}
