package com.twentygx.tgxcode.data.background

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.twentygx.tgxcode.core.Tgx

/**
 * The system screens behind the "Background installs" fixes. Each one has a fallback,
 * because OEM builds do not all carry every screen.
 */
object BackgroundIntents {

    /**
     * The one-tap "Let app always run in background?" dialog. Needs
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Play restricts to a few kinds of
     * app; it does not apply to one installed over adb or from the desktop.
     */
    @SuppressLint("BatteryLife")
    fun batteryExemption(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** The full list, for a build that has no request dialog. */
    fun batteryList(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * App info. On a Motorola it holds **App battery usage → Allow background usage**,
     * which is Motorola's own switch on top of Android's and is not the same thing as
     * the exemption: "Restricted" there stops a background service from starting at all.
     */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** This app's notification settings, which also works once the runtime prompt has been refused twice. */
    fun notifications(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Start the first of [intents] the phone has; false when none of them opened. */
    fun open(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Tgx.w("no screen for ${intent.action}")
            } catch (e: SecurityException) {
                Tgx.w("not allowed to open ${intent.action}", e)
            }
        }
        return false
    }

    fun openBatteryExemption(context: Context) = open(context, batteryExemption(context), batteryList(), appDetails(context))

    fun openAppDetails(context: Context) = open(context, appDetails(context))

    fun openNotifications(context: Context) = open(context, notifications(context), appDetails(context))
}
