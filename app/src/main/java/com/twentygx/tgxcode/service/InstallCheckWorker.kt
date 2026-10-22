package com.twentygx.tgxcode.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.twentygx.tgxcode.TgxApp
import com.twentygx.tgxcode.core.Tgx
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The install path that does not depend on the socket.
 *
 * Everything else hears about an install over `/api/events`, which only works while
 * something holds it open and Android leaves that something alone. On a phone that
 * freezes or kills background apps, neither holds for long, and the queue sits
 * `pending` until the app is opened. This reads `GET /api/phone-installs` every 15
 * minutes (the shortest period Android allows) whatever the socket is doing, and
 * starts the work if anything is waiting for this phone.
 *
 * It also nudges the rest back into shape while it has the CPU: restarts the
 * *Stay connected* service if it should be running and is not, and drops a stream
 * that says it is live but has gone quiet.
 *
 * **Keeping the process for the install.** [InstallService] cannot be started from
 * the background at API 31+ without an exemption, and a job is not one. So when the
 * work is under way and the service was refused, the job itself waits for the queue to
 * drain: a running job holds a wake lock and keeps the process, which is all the
 * install needs. JobScheduler gives a job ten minutes, so it waits nine; anything
 * longer is resumed from the partial file next time.
 *
 * Two flavours, one class: the periodic check, and a one-time expedited "hold" that
 * [com.twentygx.tgxcode.data.install.PhoneInstaller] asks for when Android refuses
 * [InstallService] while an install is starting.
 */
class InstallCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TgxApp
        val container = app.container
        val holdOnly = inputData.getBoolean(KEY_HOLD, false)
        Tgx.d("install check: ${if (holdOnly) "holding for an install" else "running"}")

        if (!holdOnly) {
            container.background.ensureService("fallback check")
            container.background.pulseCheck()
            val found = container.phoneInstalls.backgroundCheck()
            Tgx.d("install check: ${found.summary}")
            container.background.recordCheck(found.summary)
        }

        val installer = container.phoneInstalls
        if (installer.busy.value) {
            // Better than this job if Android allows it: no ten-minute limit.
            InstallService.start(applicationContext)
            val drained = withTimeoutOrNull(HOLD_MILLIS) { installer.busy.first { !it } }
            if (drained == null) Tgx.w("install check: still busy after ${HOLD_MILLIS / 60_000} minutes — letting go")
        }
        return Result.success()
    }

    /** Expedited work runs as a foreground service below API 31, which needs this. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = InstallNotifications.progress(applicationContext, null)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(InstallNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(InstallNotifications.PROGRESS_ID, notification)
        }
    }

    companion object {
        private const val KEY_HOLD = "hold"
        private const val HOLD_MILLIS = 9 * 60_000L
        private const val PERIODIC = "install-check"
        private const val NOW = "install-check-now"
        private const val HOLD = "install-hold"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * The 15-minute check. Unique and KEEP, so calling it at every start, boot and
         * update never resets its clock.
         */
        fun schedule(context: Context) = runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<InstallCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(online)
                    .build(),
            )
        }.onFailure { Tgx.w("could not schedule the install check", it) }

        /** One check as soon as Android allows: after a boot or an update, or from Settings. */
        fun checkNow(context: Context) = runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<InstallCheckWorker>()
                    .setConstraints(online)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }.onFailure { Tgx.w("could not run the install check", it) }

        /** Keep the process for an install that [InstallService] could not. */
        fun hold(context: Context) = runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                HOLD,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<InstallCheckWorker>()
                    .setInputData(workDataOf(KEY_HOLD to true))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }.onFailure { Tgx.w("could not hold for the install", it) }
    }
}
