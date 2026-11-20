package com.nagi.dosecircle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006C50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF88F8CD),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6357),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6CDBB2),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFF88F8CD),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1F352A),
    background = Color(0xFF191C1A),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun DoseCircleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
