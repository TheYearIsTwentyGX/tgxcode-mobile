package com.twentygx.tgxcode.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * mobile.css's px ladder, as sp.
 *
 * They map 1:1 and should — unlike CSS px, sp scales with the user's font setting,
 * which is correct on Android. The consequence is that every fixed height in
 * [Metrics] is a minimum rather than an exact size.
 *
 * The two platform settings below are not optional. Compose adds font padding and
 * trims line height by default; without turning both off, every line sits a couple
 * of dp lower than the web client and the two stop looking alike.
 */
private val NoExtraPadding = PlatformTextStyle(includeFontPadding = false)
private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun tgx(
    size: Int,
    lineHeight: Double = size * 1.5,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.Default,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
    fontStyle: FontStyle = FontStyle.Normal,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing,
    fontStyle = fontStyle,
    platformStyle = NoExtraPadding,
    lineHeightStyle = CenteredLineHeight,
)

// Fractional sizes in mobile.css (14.5px, 12.5px, 11.5px) need their own builder.
private fun tgxF(
    size: Double,
    lineHeight: Double = size * 1.5,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.Default,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
    fontStyle: FontStyle = FontStyle.Normal,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing,
    fontStyle = fontStyle,
    platformStyle = NoExtraPadding,
    lineHeightStyle = CenteredLineHeight,
)

val TgxTypography = Typography(
    titleMedium = tgx(16, 20.0, FontWeight.Medium),          // top bar title
    bodyLarge = tgx(15, 22.5),                               // base body, card title
    bodyMedium = tgxF(14.5, 23.2),                           // message bodies, row title
    bodySmall = tgx(13, 19.5),                               // card "why" line
    labelLarge = tgx(15, 20.0, FontWeight.Medium),           // button text
    labelMedium = tgxF(12.5, 18.0),                          // mode chip, card activity
    labelSmall = tgx(11, 14.0),                              // tab labels, tool status
)

/** Styles M3's Typography has no slot for. */
object TgxType {
    /** Row meta, tool bodies — `--mono` in mobile.css. */
    val mono12 = tgx(12, 18.6, family = FontFamily.Monospace)
    val mono125 = tgxF(12.5, 18.75, family = FontFamily.Monospace)

    /** The uppercase ask label: 11.5px/600, letter-spacing .06em. */
    val askKind = tgxF(11.5, 15.0, FontWeight.SemiBold, letterSpacing = 0.06.em)

    /** The yellow "waiting" count on the Sessions tab. */
    val badge = tgxF(10.5, 13.0, FontWeight.Bold)

    /** Thinking blocks: italic, faint, behind a left rule. */
    val thinking = tgxF(13.5, 20.25, fontStyle = FontStyle.Italic)

    /** The primary Start button. */
    val primaryButton = tgxF(15.5, 20.0, FontWeight.Medium)
}
