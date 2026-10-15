package com.dji.dronecontrol.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00ADB5),
    secondary = Color(0xFF393E46),
    tertiary = Color(0xFFFF5722),
    background = Color(0xFF1E2022),
    surface = Color(0xFF222831),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFEEEEEE),
    onSurface = Color(0xFFEEEEEE),
    error = Color(0xFFE53935)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007A87),
    secondary = Color(0xFF4A5568),
    tertiary = Color(0xFFE65100),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A202C),
    onSurface = Color(0xFF1A202C),
    error = Color(0xFFD32F2F)
)

@Composable
fun DroneControlTheme(
    darkTheme: Boolean = true, // RC Pro high-visibility dark UI by default
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
