package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentLavender,
    onPrimary = AccentDeepViolet,
    primaryContainer = FlatDarkSurface,
    onPrimaryContainer = FlatDarkTextPrimary,
    background = FlatDarkBg,
    onBackground = FlatDarkTextPrimary,
    surface = FlatDarkSurface,
    onSurface = FlatDarkTextPrimary,
    surfaceVariant = FlatDarkSurface,
    onSurfaceVariant = FlatDarkTextSecondary,
    outline = FlatDarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = FlatPrimary,
    onPrimary = Color.White,
    primaryContainer = FlatLightSurface,
    onPrimaryContainer = FlatLightTextPrimary,
    background = FlatLightBg,
    onBackground = FlatLightTextPrimary,
    surface = FlatLightSurface,
    onSurface = FlatLightTextPrimary,
    surfaceVariant = FlatLightSurface,
    onSurfaceVariant = FlatLightTextSecondary,
    outline = FlatLightBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
