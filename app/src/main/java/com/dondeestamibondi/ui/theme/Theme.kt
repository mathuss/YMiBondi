package com.dondeestamibondi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF0E5A8A)
private val BrandDark = Color(0xFF59A5D8)

private val LightColors = lightColorScheme(
    primary = Brand,
    secondary = Color(0xFF2E7D32),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    secondary = Color(0xFF81C784),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
