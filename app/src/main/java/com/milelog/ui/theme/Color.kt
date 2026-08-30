package com.milelog.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette. Aaron could not read the dark one in daylight.
val Ink = Color(0xFFF2F4F7)          // app background, light gray
val Card = Color(0xFFFFFFFF)         // raised card
val CardHigh = Color(0xFFE9EDF3)     // input / chip
val Line = Color(0xFFDCE2EA)         // hairline

// Same blue, one step deeper so it stays readable on white.
val Blue = Color(0xFF2563EB)
val BlueDim = Color(0xFF1D4ED8)
val Sky = Color(0xFF0EA5E9)

val TextHi = Color(0xFF111827)
val TextMid = Color(0xFF5B6674)
val TextLow = Color(0xFF737E8C)

val Money = Color(0xFF059669)        // revenue / positive
val Spend = Color(0xFFDC2626)        // expense / negative
val Warn = Color(0xFFB45309)

/** Slice colors for the mileage donut. */
val DonutWork = Blue
val DonutOther = Color(0xFFD97706)
val DonutUnclassified = Color(0xFFCBD5E1)
val DonutPersonal = Sky
