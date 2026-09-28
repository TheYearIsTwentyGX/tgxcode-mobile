package com.twentygx.tgxcode.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every custom property from `claude-sessions/web/mobile.css`, read from source
 * rather than approximated. The phone client and this app should be
 * indistinguishable side by side — that is the point of a second native client
 * rather than a redesign.
 */

// Tonal surface ramp.
val Bg = Color(0xFF131314)          // --bg
val S1 = Color(0xFF1B1C1D)          // --s1   bars, composer, tool row, system chip
val S2 = Color(0xFF202124)          // --s2   cards, filter, form fields, code
val S3 = Color(0xFF282A2C)          // --s3   ask card, mode chip, prompt field
val S4 = Color(0xFF303134)          // --s4   disabled button
val S5 = Color(0xFF3C3E41)          // --s5   neutral ask button, toast

val Outline = Color(0xFF444746)     // --outline
val OutlineSoft = Color(0xFF2B2D2F) // --outline-soft

// Text ramp. M3 offers one muted slot; mobile.css uses two, so both survive in
// TgxColors below.
val TextHi = Color(0xFFE3E3E3)      // --text
val Text2 = Color(0xFFC4C7C5)       // --text-2
val Text3 = Color(0xFF9AA0A6)       // --text-3
val Text4 = Color(0xFF6F7579)       // --text-4

// Accents.
val Blue = Color(0xFFA8C7FA)        // --blue        tool names, active tab
val BlueDeep = Color(0xFF0B57D0)    // --blue-deep   affirmative buttons, send
val BlueCont = Color(0xFF1F3760)    // --blue-cont   the user message bubble
val BlueTint = Color(0xFF16233A)    // --blue-tint   remote banner, ask-dot halo

val Green = Color(0xFF6DD58C)       // --green   live / running
val Yellow = Color(0xFFFDD663)      // --yellow  busy, ask, the tab badge
val Red = Color(0xFFF28B82)         // --red     errors, deny, stop
val Purple = Color(0xFFD0BCFF)      // --purple
val Peach = Color(0xFFF8B995)       // --peach
