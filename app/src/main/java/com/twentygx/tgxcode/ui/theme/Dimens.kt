package com.twentygx.tgxcode.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Radii from mobile.css: --r-xs .. --pill. */
object Radius {
    val Xs = 6.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
}

val Pill = RoundedCornerShape(percent = 50)

/**
 * The user message bubble's asymmetric corner — `border-radius: 16 16 6 16`. The
 * flat corner points at the sender, which is what makes a chat bubble read as one.
 */
val UserBubbleShape = RoundedCornerShape(
    topStart = Radius.Lg,
    topEnd = Radius.Lg,
    bottomEnd = Radius.Xs,
    bottomStart = Radius.Lg,
)

val TgxShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Xs),
    small = RoundedCornerShape(Radius.Sm),
    medium = RoundedCornerShape(Radius.Md),
    large = RoundedCornerShape(Radius.Lg),
    extraLarge = RoundedCornerShape(Radius.Xl),
)

/**
 * Fixed metrics. Note these are all *minimums* — because CSS px become sp, which
 * scale with the user's font setting, a fixed `height` would clip text for anyone
 * running large type. Every one of these is used with `heightIn(min = …)`.
 */
object Metrics {
    val BarHeight = 52.dp
    val TabsHeight = 56.dp

    /** The floor for anything tappable. A tool row is the one deliberate exception. */
    val TouchTarget = 44.dp
    val ToolRowHeight = 36.dp
    val SessionRowHeight = 56.dp

    /**
     * Ask buttons are 48, not 44. Approving a permission on a phone, one-handed, in
     * a hurry, is the thing this whole surface exists to do.
     */
    val AskButtonHeight = 48.dp
    val PrimaryButtonHeight = 50.dp
    val FieldHeight = 48.dp

    val ScreenPadding = 12.dp
    val CardPadding = 14.dp
    val ReasonBorder = 3.dp

    /** How close to the bottom still counts as "stuck to the bottom". */
    val StickToBottomSlop = 120.dp
}
