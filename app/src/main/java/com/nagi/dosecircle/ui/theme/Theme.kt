package com.nagi.dosecircle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6554F0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E3FF),
    onPrimaryContainer = Color(0xFF24213A),
    secondary = Color(0xFF8175C9),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF24213A),
    background = Color(0xFFF1EEFF),
    onBackground = Color(0xFF24213A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF24213A),
    surfaceVariant = Color(0xFFF7F5FF),
    onSurfaceVariant = Color(0xFF655F85),
    outline = Color(0xFFE3DCFC),
    outlineVariant = Color(0xFFECE7FF),
    error = Color(0xFFD95368),
    onError = Color(0xFFFFFFFF)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6554F0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E3FF),
    onPrimaryContainer = Color(0xFF24213A),
    secondary = Color(0xFF8175C9),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF24213A),
    background = Color(0xFFF1EEFF),
    onBackground = Color(0xFF24213A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF24213A),
    surfaceVariant = Color(0xFFF7F5FF),
    onSurfaceVariant = Color(0xFF655F85),
    outline = Color(0xFFE3DCFC),
    outlineVariant = Color(0xFFECE7FF),
    error = Color(0xFFD95368),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun DoseCircleTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
