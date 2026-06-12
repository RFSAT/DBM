package com.rfsat.dms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// RFSAT / ENACT brand palette — matches ShimmerENACT styling
val EnactGreen        = Color(0xFF4DC494)
val EnactLime         = Color(0xFF96CC45)
val EnactDark         = Color(0xFF061E1A)
val EnactDarkMid      = Color(0xFF0C2E28)
val EnactSurface      = Color(0xFF103830)
val EnactSurfaceVar   = Color(0xFF174840)
val EnactOnSurface    = Color(0xFFF0F7EC)
val EnactOnSurfaceDim = Color(0xFFB0C9A8)
val EnactError        = Color(0xFFE57373)
val EnactWarning      = Color(0xFFFFCA28)

private val DarkColorScheme = darkColorScheme(
    primary          = EnactGreen,
    onPrimary        = EnactDark,
    primaryContainer = EnactDarkMid,
    secondary        = EnactLime,
    onSecondary      = EnactDark,
    background       = EnactDark,
    onBackground     = EnactOnSurface,
    surface          = EnactSurface,
    onSurface        = EnactOnSurface,
    surfaceVariant   = EnactSurfaceVar,
    onSurfaceVariant = EnactOnSurface,
    error            = EnactError,
    outline          = EnactGreen.copy(alpha = 0.4f)
)

@Composable
fun DbmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography(), content = content)
}
