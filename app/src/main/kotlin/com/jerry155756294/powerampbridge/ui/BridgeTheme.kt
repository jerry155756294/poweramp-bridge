package com.jerry155756294.powerampbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
  primary = Color(0xFF2154E6),
  onPrimary = Color(0xFFFFFFFF),
  secondary = Color(0xFFFF7A00),
  primaryContainer = Color(0xFFDCE6FF),
  onPrimaryContainer = Color(0xFF102A72),
  background = Color(0xFFF5F7FB),
  surface = Color(0xFFFFFFFF),
  surfaceVariant = Color(0xFFE9EEF8),
  onSurfaceVariant = Color(0xFF475467),
  onSurface = Color(0xFF101828),
  outline = Color(0xFFD0D5DD),
  outlineVariant = Color(0xFFDFE5F0)
)

private val DarkScheme = darkColorScheme(
  primary = Color(0xFF8FA8FF),
  onPrimary = Color(0xFF0B1D57),
  secondary = Color(0xFFFFB26B),
  primaryContainer = Color(0xFF1B2D6B),
  onPrimaryContainer = Color(0xFFDCE6FF),
  background = Color(0xFF0F172A),
  surface = Color(0xFF111827),
  surfaceVariant = Color(0xFF1E293B),
  onSurfaceVariant = Color(0xFFCBD5E1),
  onSurface = Color(0xFFF8FAFC),
  outline = Color(0xFF334155),
  outlineVariant = Color(0xFF475569)
)

private val BridgeShapes = Shapes(
  small = RoundedCornerShape(16.dp),
  medium = RoundedCornerShape(22.dp),
  large = RoundedCornerShape(28.dp),
  extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun BridgeTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkScheme else LightScheme,
    shapes = BridgeShapes,
    content = content
  )
}
