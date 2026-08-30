package com.milelog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FE),
    onPrimaryContainer = BlueDim,
    secondary = Sky,
    onSecondary = Color.White,
    background = Ink,
    onBackground = TextHi,
    surface = Card,
    onSurface = TextHi,
    surfaceVariant = CardHigh,
    onSurfaceVariant = TextMid,
    outline = Line,
    outlineVariant = Line,
    error = Spend,
    onError = Color.White
)

/** MileLog is light only, by choice — one palette, no dark variant. */
@Composable
fun MileLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = MileLogType, content = content)
}
