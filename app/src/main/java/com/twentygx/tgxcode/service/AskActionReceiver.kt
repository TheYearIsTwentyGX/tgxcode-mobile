package com.twentygx.tgxcode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentygx.tgxcode.TgxApp
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.PermissionRequestBody
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers a tool permission straight from the notification, without opening the app.
 *
 * A receiver rather than `PendingIntent.getForegroundService`: both are legal — user
 * interaction with a notification is a documented exemption from the background
 * foreground-service start restriction — but the receiver is one code path whether or
 * not background mode is on, and avoids the "start an FGS, now call
 * `startForeground()` within seconds" dance for what is a single 200ms POST.
 */
class AskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_SERVICE -> {
                val app = context.applicationContext as TgxApp
                app.container.appScope.launch {
                    app.container.settings.setStayConnected(false)
                }
                BridgeService.stop(context.applicationContext)
                return
            }

            ACTION_ANSWER -> answer(context, intent)
        }
    }

    private fun answer(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return

        val app = context.applicationContext as TgxApp
        val pending = goAsync()

        app.container.appScope.launch {
            try {
                // goAsync() allows roughly ten seconds before an ANR, so this call
                // uses the 8s client rather than the default one.
                val result = withTimeoutOrNull(9_000) {
                    app.container.api.permission(
                        sessionId,
                        PermissionRequestBody(requestId = requestId, decision = decision),
                        client = app.container.notificationClient,
                    )
                }
                when {
                    result == null -> {
                        Tgx.w("notification answer timed out")
                        AskNotifications.showFailed(
                            context, sessionId, "Timed out — tap to open",
                        )
                    }
                    result is ApiResult.Ok -> AskNotifications.cancel(context, sessionId)
                    // Already answered on the desktop or in another window. An
                    // ordinary outcome, not an error to shout about — just clear it.
                    result is ApiResult.Http && result.status == 409 ->
                        AskNotifications.cancel(context, sessionId)
                    result is ApiResult.Http && result.status == 404 ->
                        AskNotifications.showFailed(
                            context, sessionId, "That session is no longer running",
                        )
                    else -> AskNotifications.showFailed(
                        context, sessionId, "Couldn't answer — tap to open",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.twentygx.tgxcode.ANSWER"
        const val ACTION_STOP_SERVICE = "com.twentygx.tgxcode.STOP_SERVICE"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_DECISION = "decision"
    }
}
