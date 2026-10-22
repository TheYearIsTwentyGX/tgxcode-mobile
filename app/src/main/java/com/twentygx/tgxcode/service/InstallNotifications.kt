package com.twentygx.tgxcode.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.twentygx.tgxcode.R

/**
 * Notifications for the phone-installs queue: one per install, replaced as it moves.
 *
 * Two channels. A confirmation or a missing grant is waiting on you, so it is loud; a
 * result is something you asked for at the desk and is only worth a glance.
 *
 * **Why a notification and not just the dialog.** From the background an app may not
 * start an activity, and PackageInstaller's confirmation *is* an activity — so a tap
 * on a notification is the path whenever the app is not on screen.
 */
object InstallNotifications {

    private const val ID_BASE = 5000

    /** One slot per install, so "tap to install" is replaced by its result. */
    fun idFor(installId: String): Int = ID_BASE + installId.hashCode().and(0xFFF)

    private const val PERMISSION_ID = 4999

    @SuppressLint("MissingPermission")
    fun showConfirm(context: Context, installId: String, app: String, confirm: Intent) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val tap = PendingIntent.getActivity(
            context,
            idFor(installId),
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            idFor(installId),
            NotificationCompat.Builder(context, Channels.INSTALLS)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle("Tap to install $app")
                .setContentText("Sent from the desktop. Android wants you to confirm this one.")
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The "Install unknown apps" grant is missing. One notification however many
     * installs are waiting on it, because one switch fixes all of them.
     */
    @SuppressLint("MissingPermission")
    fun showNeedsPermission(context: Context, app: String) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val tap = PendingIntent.getActivity(
            context,
            PERMISSION_ID,
            unknownSourcesIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            PERMISSION_ID,
            NotificationCompat.Builder(context, Channels.INSTALLS)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle("Allow TGXCode to install $app")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "The desktop sent an app to install. Turn on \"Allow from this source\" " +
                            "for TGXCode, then come back — the install carries on by itself.",
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelPermission(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(PERMISSION_ID)
    }

    @SuppressLint("MissingPermission")
    fun showInstalled(context: Context, installId: String, app: String, version: String?, packageName: String?) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        // Tapping opens what was installed. The installer of record can always see the
        // package, so this resolves without QUERY_ALL_PACKAGES.
        val launch = packageName
            ?.let { runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
            ?.let {
                PendingIntent.getActivity(
                    context,
                    idFor(installId),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        manager.notify(
            idFor(installId),
            NotificationCompat.Builder(context, Channels.INSTALL_RESULTS)
                .setSmallIcon(R.drawable.ic_stat_connected)
                .setContentTitle("Installed $app${version?.let { " $it" } ?: ""}")
                .setSilent(true)
                .setAutoCancel(true)
                .apply { launch?.let(::setContentIntent) }
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun showFailed(context: Context, installId: String, app: String, message: String) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.notify(
            idFor(installId),
            NotificationCompat.Builder(context, Channels.INSTALL_RESULTS)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle("Couldn't install $app")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSilent(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(context: Context, installId: String) {
        context.getSystemService<NotificationManager>()?.cancel(idFor(installId))
    }

    /** The per-app "Install unknown apps" screen, opened on this package. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
}
