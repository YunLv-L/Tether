package com.tether.controller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFFFFFFFF),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFF93C5FD),
    secondaryContainer = Color(0xFF2D1B69),
    error = Color(0xFFEF4444)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A5F),
    secondaryContainer = Color(0xFFEDE9FE),
    error = Color(0xFFEF4444)
)

@Composable
fun TetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}