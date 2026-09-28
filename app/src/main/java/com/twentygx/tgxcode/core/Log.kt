package com.twentygx.tgxcode.core

import android.util.Log
import com.twentygx.tgxcode.BuildConfig

/**
 * One tag, so `adb logcat -s TGXCode:V` shows the whole app and nothing else.
 *
 * A long-lived socket that reconnects on its own is close to undebuggable without
 * this — "it says offline" is not a bug report you can act on.
 */
object Tgx {
    const val TAG = "TGXCode"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
    }
}
