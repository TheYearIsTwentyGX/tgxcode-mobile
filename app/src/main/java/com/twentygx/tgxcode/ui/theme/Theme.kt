package com.twentygx.tgxcode.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The tonal ramp in mobile.css lines up with M3's surface-container ladder almost
 * exactly, which is not a coincidence — both are Material 3. So most tokens land in
 * a real M3 slot and only a handful need somewhere else to live.
 */
private val TgxColorScheme = darkColorScheme(
    background = Bg,
    onBackground = TextHi,
    surface = Bg,
    onSurface = TextHi,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = S1,
    surfaceContainer = S2,
    surfaceContainerHigh = S3,
    surfaceContainerHighest = S4,
    surfaceVariant = S5,
    onSurfaceVariant = Text2,
    outline = Outline,
    outlineVariant = OutlineSoft,
    primary = Blue,
    onPrimary = Color(0xFF062E6F),
    secondaryContainer = BlueCont,
    onSecondaryContainer = TextHi,
    tertiary = Purple,
    error = Red,
    onError = Bg,
)

/**
 * Tokens with no honest M3 slot.
 *
 * [blueDeep] in particular is deliberately *not* forced into `primaryContainer`:
 * M3 containers are low-contrast by convention, so `onPrimaryContainer` would fight
 * the white text mobile.css puts on `--blue-deep`. A filled button that names its
 * own colours is more honest than one that abuses a slot.
 */
data class TgxColors(
    /**
     * --blue. Also the `primary` slot, but named here too: an open pull request is
     * blue because the PR colour table says so, not because it is the app's accent,
     * and a reader of `prTint` should not have to know they are the same value.
     */
    val blue: Color = Blue,
    val blueDeep: Color = BlueDeep,
    val blueTint: Color = BlueTint,
    val blueCont: Color = BlueCont,
    val yellow: Color = Yellow,
    val green: Color = Green,
    val red: Color = Red,
    val peach: Color = Peach,
    /**
     * --purple. Merged pull requests, and nothing else on this surface.
     *
     * It is the `tertiary` slot as well, but reading `colorScheme.tertiary` to mean
     * "merged" is the slot abuse [blueDeep] above argues against — M3's conventions
     * for a tertiary have nothing to do with what this colour is for.
     */
    val purple: Color = Purple,
    /** --text-3. M3 gives one muted slot; mobile.css uses two. */
    val textMuted: Color = Text3,
    /** --text-4. */
    val textFaint: Color = Text4,
    val s1: Color = S1,
    val s2: Color = S2,
    val s3: Color = S3,
    val s4: Color = S4,
    val s5: Color = S5,
    val outlineSoft: Color = OutlineSoft,
)

val LocalTgxColors = staticCompositionLocalOf { TgxColors() }

/** Shorthand: `tgx.yellow` reads better than `LocalTgxColors.current.yellow`. */
val tgx: TgxColors
    @Composable get() = LocalTgxColors.current

@Composable
fun TgxCodeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTgxColors provides TgxColors()) {
        MaterialTheme(
            colorScheme = TgxColorScheme,
            typography = TgxTypography,
            shapes = TgxShapes,
            content = content,
        )
    }
}
