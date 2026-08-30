package com.milelog.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette. Every colour that carries text or meaning is held at or above the
// 4.5:1 readable threshold on all three surfaces below, not just on white.
val Ink = Color(0xFFF2F4F7)          // app background, light gray
val Card = Color(0xFFFFFFFF)         // raised card
val CardHigh = Color(0xFFE9EDF3)     // input / chip
val Line = Color(0xFFC3CBD6)         // hairline, dark enough to actually see

val Blue = Color(0xFF2563EB)         // buttons and large figures
val BlueDim = Color(0xFF1D4ED8)      // small blue text, where Blue is a shade too light
val Sky = Color(0xFF0E7490)          // trip start marker

val TextHi = Color(0xFF111827)
val TextMid = Color(0xFF4A5464)
val TextLow = Color(0xFF5C6674)

val Money = Color(0xFF047857)        // revenue / positive
val Spend = Color(0xFFC62828)        // expense / negative
val Warn = Color(0xFF9A4A05)

/** The personal side of a swipe. Deliberately a different hue from Blue, not a shade. */
val Personal = Warn

/** Slice colors for the mileage donut, each readable against a white card. */
val DonutWork = Blue
val DonutOther = Color(0xFFB45309)
val DonutUnclassified = Color(0xFF64748B)
val DonutPersonal = Color(0xFF0E7490)
