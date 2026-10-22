package com.twentygx.tgxcode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentygx.tgxcode.TgxApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The pulse alarm, about every ten minutes while *Stay connected* runs. It is the one
 * thing that wakes a sleeping phone whose stream died quietly — see
 * [com.twentygx.tgxcode.data.background.BackgroundHealth.pulseCheck]. The alarm's wake
 * lock lasts until [goAsync] is finished, which covers a reconnect and the catch-up
 * that follows it.
 */
class PulseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TgxApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                withTimeoutOrNull(BUDGET_MILLIS) { app.container.background.onPulse() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** Inside a background broadcast's minute, with room to spare. */
        const val BUDGET_MILLIS = 40_000L
    }
}
