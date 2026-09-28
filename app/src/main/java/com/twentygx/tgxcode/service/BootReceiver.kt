package com.twentygx.tgxcode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentygx.tgxcode.TgxApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the connection service after a reboot or a reinstall, when background
 * mode is on.
 *
 * MY_PACKAGE_REPLACED matters more than BOOT_COMPLETED here — this app gets
 * reinstalled over adb constantly, and a background connection that silently stops
 * surviving installs would be worse than one that was never claimed to work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext as TgxApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                if (app.container.settings.stayConnected.first()) {
                    BridgeService.start(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
