package com.twentygx.tgxcode.ui.common

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The Android answer to `prefers-reduced-motion`.
 *
 * Settings → Accessibility → **Remove animations** writes
 * `Settings.Global.ANIMATOR_DURATION_SCALE = 0`, and that is the only system-wide
 * signal there is — there is no `isReduceMotionEnabled` to ask.
 *
 * **Not left to Compose's own `MotionDurationScale`**, which the Android UI dispatcher
 * does derive from this setting. A *finite* animation under a zero scale snaps to its
 * target, which is right; an `infiniteRepeatable` under a zero scale is not documented
 * to stop, and an infinite animation completing instantly forever is a hot loop rather
 * than a still icon. So anything that repeats without end asks here first.
 *
 * Read once per composition rather than observed: a `ContentObserver` on Global
 * settings is a lot of machinery for a value that only changes when somebody visits
 * Accessibility settings, and coming back to the app recomposes this anyway.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
