package com.twentygx.tgxcode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentygx.tgxcode.TgxApp
import kotlinx.coroutines.launch

/**
 * PackageInstaller's answer for a session [com.twentygx.tgxcode.data.install.PhoneInstaller]
 * committed. Our own extras ride along with the platform's, so the result can be
 * reported without remembering anything in memory — the process may be a fresh one.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val app = context.applicationContext as TgxApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.phoneInstalls.onResult(intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESULT = "com.twentygx.tgxcode.action.INSTALL_RESULT"
        const val EXTRA_INSTALL_ID = "install_id"
        const val EXTRA_APP = "install_app"
        const val EXTRA_PACKAGE = "install_package"
        const val EXTRA_VERSION = "install_version"
    }
}
