package com.twentygx.tgxcode.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.twentygx.tgxcode.MainActivity
import com.twentygx.tgxcode.R
import com.twentygx.tgxcode.data.background.BackgroundIntents
import com.twentygx.tgxcode.data.install.InstallWork
import com.twentygx.tgxcode.data.model.PhoneInstallState

/**
 * Notifications for the phone-installs queue: one per install, replaced as it moves.
 *
 * Two channels. A confirmation or a missing grant is waiting on you, so it is loud; a
 * result is something you asked for at the desk and is only worth a glance. The
 * ongoing progress goes on the quiet one too, and gives way to the result there.
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

    /**
     * The ongoing "Downloading … / Installing …" notification. One slot, because
     * installs run one at a time, and the slot [InstallService] holds the process up
     * with. The result gets the install's own slot as this one goes.
     */
    const val PROGRESS_ID = 4998

    /** Opens the Installs screen — `tgxcode://installs`, handled by MainActivity. */
    fun installsIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            PROGRESS_ID,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("tgxcode://installs")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * What is being installed right now, with a determinate bar once the length is
     * known. Silent and alert-once: it is updated every couple of seconds.
     */
    fun progress(context: Context, work: InstallWork?): Notification {
        val builder = NotificationCompat.Builder(context, Channels.INSTALL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(installsIntent(context))
        if (work == null) {
            return builder.setContentTitle("Getting an install ready").setProgress(0, 0, true).build()
        }
        val installing = work.phase == PhoneInstallState.INSTALLING
        builder.setContentTitle(if (installing) "Installing ${work.app}" else "Downloading ${work.app}")
        when {
            installing -> builder.setProgress(0, 0, true)
            work.total > 0 -> {
                val percent = (work.bytes * 100 / work.total).toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false)
                    .setContentText("${megabytes(work.bytes)} of ${megabytes(work.total)} MB")
            }
            else -> builder.setProgress(0, 0, true).setContentText("${megabytes(work.bytes)} MB")
        }
        work.note?.let { builder.setSubText(it) }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun showProgress(context: Context, work: InstallWork?) {
        context.getSystemService<NotificationManager>()?.notify(PROGRESS_ID, progress(context, work))
    }

    fun cancelProgress(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(PROGRESS_ID)
    }

    private fun megabytes(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

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

    private const val BATTERY_ID = 4997

    /**
     * The one-time ask for the battery exemption, when an install arrives with the app
     * off screen. The tap is the system's own request dialog, so one tap there and one
     * "Allow" is the whole fix.
     */
    @SuppressLint("MissingPermission")
    fun showBatteryPrompt(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val tap = PendingIntent.getActivity(
            context,
            BATTERY_ID,
            BackgroundIntents.batteryExemption(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Android is optimising TGXCode's battery use, so it can freeze the app and drop " +
            "its connection, and installs then wait until you open it. Tap to let it run in the " +
            "background. On a Motorola, also set App info → App battery usage → Allow background usage."
        manager.notify(
            BATTERY_ID,
            NotificationCompat.Builder(context, Channels.INSTALLS)
                .setSmallIcon(R.drawable.ic_stat_ask)
                .setContentTitle("Let installs run in the background")
                .setContentText("Tap to stop Android freezing TGXCode")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(tap)
                .addAction(0, "App info", PendingIntent.getActivity(
                    context,
                    BATTERY_ID + 1,
                    BackgroundIntents.appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
                .setAutoCancel(true)
                .build(),
        )
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
                .setContentIntent(installsIntent(context))
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
