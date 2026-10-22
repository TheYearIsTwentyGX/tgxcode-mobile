package com.twentygx.tgxcode.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.twentygx.tgxcode.TgxApp
import com.twentygx.tgxcode.core.Tgx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process up while a phone install is downloading or committing, so it
 * finishes with the app in the background and the screen off.
 *
 * Without it an install only got as far as the process did. With *Stay connected* off
 * nothing else holds the app in the foreground, and once its activity goes, Android
 * freezes the cached process within seconds and Doze cuts its network — the download
 * stops where it is, says nothing, and the bridge sees a claim that went quiet. With
 * [BridgeService] running the process was already protected; this costs nothing then.
 *
 * The work itself is [com.twentygx.tgxcode.data.install.PhoneInstaller]'s, on the
 * app-wide scope. This only holds the foreground state for it, shows its progress
 * notification, and stops once the queue has drained.
 *
 * **Why `dataSync`.** It is a download from a server, which is the type's definition,
 * and an install is minutes at most, nowhere near Android 15's six hours a day. It
 * cannot be started from the background, and [start] says so rather than throwing:
 * if [BridgeService] is already holding the process, that is enough.
 */
class InstallService : Service() {

    private var scope: CoroutineScope? = null

    /** The newest start, so a stop never swallows a start that came in after it. */
    @Volatile
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val installer = (application as TgxApp).container.phoneInstalls
        lastStartId = startId
        // Every startForegroundService needs its startForeground, so on every start.
        try {
            ServiceCompat.startForeground(
                this,
                InstallNotifications.PROGRESS_ID,
                InstallNotifications.progress(this, installer.work.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            Tgx.w("install service could not go foreground", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = serviceScope
            serviceScope.launch {
                installer.busy.collect { busy ->
                    if (busy) return@collect
                    Tgx.d("install service: queue drained")
                    ServiceCompat.stopForeground(this@InstallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
        }
        // Not sticky: a restarted process picks its own work back up from the claims.
        return START_NOT_STICKY
    }

    /** dataSync's daily allowance ran out (Android 15+). The work goes on unprotected. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Tgx.w("install service timed out — stopping")
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        /** False when Android refused, which from the background it may. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, InstallService::class.java))
            true
        } catch (t: Throwable) {
            Tgx.w("install service not started: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
