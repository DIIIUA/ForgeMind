package com.forgemind.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object ForgeColors {
    val BgDeep = Color(0xFF0B0F17)
    val SurfaceDark = Color(0xFF121826)
    val SurfaceElevated = Color(0xFF1A2234)
    val SurfaceCard = Color(0xFF1E273B)
    val SurfaceGlass = Color(0xFF162033)
    val AccentBlue = Color(0xFF4F8CFF)
    val AccentCyan = Color(0xFF38BDF8)
    val AccentEmerald = Color(0xFF10B981)
    val AccentAmber = Color(0xFFF59E0B)
    val DangerRed = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF94A3B8)
    val BorderSubtle = Color(0xFF2B3548)
    val BorderHighlight = Color(0xFF3B4D6E)
    val DiffAddBg = Color(0xFF122C24)
    val DiffAddText = Color(0xFF6EE7B7)
    val DiffDelBg = Color(0xFF331820)
    val DiffDelText = Color(0xFFFCA5A5)
    val TerminalBg = Color(0xFF0A0F18)
    val TerminalText = Color(0xFFDCE7F3)
}

private val ForgeDarkScheme = darkColorScheme(
    primary = ForgeColors.AccentBlue,
    onPrimary = Color.White,
    secondary = ForgeColors.AccentEmerald,
    onSecondary = Color.Black,
    tertiary = ForgeColors.AccentAmber,
    background = ForgeColors.BgDeep,
    onBackground = ForgeColors.TextPrimary,
    surface = ForgeColors.SurfaceDark,
    onSurface = ForgeColors.TextPrimary,
    surfaceVariant = ForgeColors.SurfaceElevated,
    onSurfaceVariant = ForgeColors.TextSecondary,
    outline = ForgeColors.BorderSubtle,
    error = ForgeColors.DangerRed,
    onError = Color.White
)

private val ForgeTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.5.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun ForgeMindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ForgeDarkScheme,
        typography = ForgeTypography,
        content = content
    )
}

val MonoFont: FontFamily = FontFamily.Monospace
