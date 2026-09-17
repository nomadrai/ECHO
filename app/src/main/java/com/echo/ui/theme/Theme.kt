package com.echo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A fixed dark "instrument panel" palette rather than dynamic colour: ECHO is a
 * sensing tool, and the same colours must mean the same thing on every device
 * (cyan = live sensing, green = healthy, amber = degraded, red = failure).
 */
private val EchoColorScheme = darkColorScheme(
    primary = Color(0xFF4FD1FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF0E3A50),
    onPrimaryContainer = Color(0xFFBEEBFF),
    secondary = Color(0xFF7EE7C7),
    onSecondary = Color(0xFF00382B),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF121B2D),
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF1D283E),
    onSurfaceVariant = Color(0xFFB9C6DB),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
)

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EchoColorScheme, content = content)
}
