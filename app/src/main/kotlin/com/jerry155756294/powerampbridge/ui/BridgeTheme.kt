package com.jerry155756294.powerampbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
  primary = Color(0xFF2154E6),
  onPrimary = Color(0xFFFFFFFF),
  secondary = Color(0xFFFF7A00),
  background = Color(0xFFF5F7FB),
  surface = Color(0xFFFFFFFF),
  onSurface = Color(0xFF101828),
  outline = Color(0xFFD0D5DD)
)

private val DarkScheme = darkColorScheme(
  primary = Color(0xFF8FA8FF),
  onPrimary = Color(0xFF0B1D57),
  secondary = Color(0xFFFFB26B),
  background = Color(0xFF0F172A),
  surface = Color(0xFF111827),
  onSurface = Color(0xFFF8FAFC),
  outline = Color(0xFF334155)
)

@Composable
fun BridgeTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkScheme else LightScheme,
    content = content
  )
}
