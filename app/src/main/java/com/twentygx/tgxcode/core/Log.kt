package com.twentygx.tgxcode.core

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.twentygx.tgxcode.BuildConfig

/**
 * One tag, so `adb logcat -s TGXCode:V` shows the whole app and nothing else.
 *
 * A long-lived socket that reconnects on its own is close to undebuggable without
 * this — "it says offline" is not a bug report you can act on.
 */
object Tgx {
    const val TAG = "TGXCode"

    /**
     * Every line, as it is logged, for a test to read. `android.util.Log` is a stub on
     * the JVM, so without this a test cannot see what would have reached logcat — which
     * is how `SudoLoggingTest` proves a password never does.
     */
    @VisibleForTesting
    @Volatile
    var tap: ((String) -> Unit)? = null

    fun d(message: String) {
        if (!BuildConfig.DEBUG) return
        tap?.invoke(message)
        Log.d(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        tap?.invoke(if (error != null) "$message\n${error.stackTraceToString()}" else message)
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
    }
}
