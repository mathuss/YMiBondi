package com.dondeestamibondi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF0E5A8A)
private val BrandDark = Color(0xFF59A5D8)

// Containers are tinted with the brand blue; with only primary/secondary overridden,
// Material3 fills chips, banners and the navigation bar with its default violet.
private val LightColors = lightColorScheme(
    primary = Brand,
    primaryContainer = Color(0xFFD3E4F5),
    onPrimaryContainer = Color(0xFF0B2F4A),
    secondary = Color(0xFF2E7D32),
    secondaryContainer = Color(0xFFD3E4F5),
    onSecondaryContainer = Color(0xFF0B2F4A),
    tertiary = Color(0xFF8A5A00),
    tertiaryContainer = Color(0xFFFFE7B3),
    onTertiaryContainer = Color(0xFF5B3A00),
    surfaceContainer = Color(0xFFEAF1F8),
    surfaceContainerHigh = Color(0xFFE0EAF3),
    surfaceContainerHighest = Color(0xFFD6E2EE),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    primaryContainer = Color(0xFF2B4A63),
    onPrimaryContainer = Color(0xFFD3E4F5),
    secondary = Color(0xFF81C784),
    secondaryContainer = Color(0xFF2B4A63),
    onSecondaryContainer = Color(0xFFD3E4F5),
    tertiary = Color(0xFFFFD27A),
    tertiaryContainer = Color(0xFF4D3700),
    onTertiaryContainer = Color(0xFFFFE7B3),
    surfaceContainer = Color(0xFF1D2733),
    surfaceContainerHigh = Color(0xFF25313E),
    surfaceContainerHighest = Color(0xFF2E3B49),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
