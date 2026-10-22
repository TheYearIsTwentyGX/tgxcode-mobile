package com.twentygx.tgxcode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentygx.tgxcode.TgxApp
import com.twentygx.tgxcode.core.Tgx
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the connection service after a reboot or a reinstall, when background
 * mode is on, and makes sure the fallback install check is booked either way.
 *
 * MY_PACKAGE_REPLACED matters more than BOOT_COMPLETED here — this app gets
 * reinstalled over adb constantly, and updates itself from the installs queue, and a
 * background connection that silently stops surviving installs would be worse than
 * one that was never claimed to work.
 *
 * **Which start is allowed.** Both broadcasts exempt the app from the ban on starting
 * a foreground service from the background. At API 35+ `BOOT_COMPLETED` no longer
 * covers some types (`dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
 * `mediaProjection`, `microphone`), and `specialUse` is not one of them, so
 * [BridgeService] may start here. An OEM can still refuse; then the check runs now
 * instead of in fifteen minutes, which also settles a self-update the desk is waiting
 * to hear about.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext as TgxApp
        InstallCheckWorker.schedule(app)
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                val started = app.container.settings.stayConnected.first() && BridgeService.start(app)
                Tgx.d("${intent.action}: connection service ${if (started) "started" else "not started"}")
                if (!started || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    InstallCheckWorker.checkNow(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
