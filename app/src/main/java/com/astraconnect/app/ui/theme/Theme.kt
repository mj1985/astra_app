package com.astraconnect.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Lightning = Color(0xFFF7FF14)
val Ok = Color(0xFF2E9E5B)
val Warn = Color(0xFFE0A100)
val Bad = Color(0xFFD64545)

private val Dark = darkColorScheme(
    primary = Lightning,
    onPrimary = Color(0xFF1A1A00),
    primaryContainer = Color(0xFF3A3C00),
    onPrimaryContainer = Lightning,
    secondary = Color(0xFFC9CBB0),
    background = Color(0xFF121314),
    surface = Color(0xFF121314),
    surfaceContainer = Color(0xFF1E1F21),
    surfaceContainerHigh = Color(0xFF26282A),
    surfaceContainerLow = Color(0xFF191A1C),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B5E00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9F27A),
    onPrimaryContainer = Color(0xFF1A1C00),
    secondary = Color(0xFF5F604A),
    background = Color(0xFFF8F8F2),
    surface = Color(0xFFF8F8F2),
)

@Composable
fun AstraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
