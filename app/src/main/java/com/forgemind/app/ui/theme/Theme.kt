package com.forgemind.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.forgemind.app.data.model.UiThemeSettings
import kotlin.math.roundToInt

object ForgeColors {
    var BgDeep by mutableStateOf(Color(0xFF0B0F17))
    var SurfaceDark by mutableStateOf(Color(0xFF121826))
    var SurfaceElevated by mutableStateOf(Color(0xFF1A2234))
    var SurfaceCard by mutableStateOf(Color(0xFF1E273B))
    var SurfaceGlass by mutableStateOf(Color(0xFF162033))
    var AccentBlue by mutableStateOf(Color(0xFF4F8CFF))
    var AccentCyan by mutableStateOf(Color(0xFF38BDF8))
    var AccentEmerald by mutableStateOf(Color(0xFF10B981))
    var AccentAmber by mutableStateOf(Color(0xFFF59E0B))
    var DangerRed by mutableStateOf(Color(0xFFEF4444))
    var TextPrimary by mutableStateOf(Color(0xFFEAF0F8))
    var TextSecondary by mutableStateOf(Color(0xFFA3B3C9))
    var BorderSubtle by mutableStateOf(Color(0xFF35445E))
    var BorderHighlight by mutableStateOf(Color(0xFF4F6791))
    val DiffAddBg = Color(0xFF122C24)
    val DiffAddText = Color(0xFF6EE7B7)
    val DiffDelBg = Color(0xFF331820)
    val DiffDelText = Color(0xFFFCA5A5)
    var UserBubbleBg by mutableStateOf(Color(0xFF204382).copy(alpha = 0.95f))
    var AiBubbleBg by mutableStateOf(Color(0xFF162033))
    var TerminalBg by mutableStateOf(Color(0xFF0A0F18))
    var TerminalOutputBoxBg by mutableStateOf(Color(0xFF0D1422))
    var TerminalText by mutableStateOf(Color(0xFFDCE7F3))
    var TerminalPrompt by mutableStateOf(Color(0xFF38BDF8))

    fun parseHexColor(hex: String, fallback: Color, alpha: Float = 1f): Color {
        val clean = hex.trim().removePrefix("#")
        val rgb = when (clean.length) {
            6 -> clean.toLongOrNull(16)
            8 -> clean.takeLast(6).toLongOrNull(16)
            else -> null
        } ?: return fallback.copy(alpha = alpha.coerceIn(0.12f, 1f))
        val r = ((rgb shr 16) and 0xFF).toInt()
        val g = ((rgb shr 8) and 0xFF).toInt()
        val b = (rgb and 0xFF).toInt()
        return Color(
            red = r / 255f,
            green = g / 255f,
            blue = b / 255f,
            alpha = alpha.coerceIn(0.12f, 1f)
        )
    }

    fun toHexRgb(color: Color): String {
        val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
        val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
        val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, b)
    }

    private fun shiftBrightness(base: Color, delta: Float, alpha: Float = base.alpha): Color = Color(
        red = (base.red + delta).coerceIn(0f, 1f),
        green = (base.green + delta).coerceIn(0f, 1f),
        blue = (base.blue + delta).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0.12f, 1f)
    )

    fun applyTheme(settings: UiThemeSettings) {
        val bg = parseHexColor(settings.bgDeepHex, Color(0xFF0B0F17), 1f)
        val surfAlpha = settings.surfaceAlpha.coerceIn(0.2f, 1f)
        val cardAlpha = settings.cardAlpha.coerceIn(0.2f, 1f)
        val surf = parseHexColor(settings.surfaceDarkHex, Color(0xFF121826), surfAlpha)
        val card = parseHexColor(settings.surfaceCardHex, Color(0xFF1E273B), cardAlpha)
        val accent = parseHexColor(
            settings.accentHex,
            Color(0xFF4F8CFF),
            settings.accentAlpha.coerceIn(0.35f, 1f)
        )
        val border = parseHexColor(
            settings.borderHex,
            Color(0xFF35445E),
            settings.borderAlpha.coerceIn(0.15f, 1f)
        )
        val userBubble = parseHexColor(
            settings.userBubbleHex,
            Color(0xFF204382),
            settings.userBubbleAlpha.coerceIn(0.2f, 1f)
        )
        val aiBubble = parseHexColor(
            settings.aiBubbleHex,
            Color(0xFF162033),
            settings.aiBubbleAlpha.coerceIn(0.2f, 1f)
        )
        val termBg = parseHexColor(
            settings.terminalBgHex,
            Color(0xFF0A0F18),
            settings.terminalBgAlpha.coerceIn(0.25f, 1f)
        )
        val termText = parseHexColor(settings.terminalTextHex, Color(0xFFDCE7F3), 1f)
        val termPrompt = parseHexColor(settings.terminalPromptHex, Color(0xFF38BDF8), 1f)

        BgDeep = bg
        SurfaceDark = surf
        SurfaceGlass = shiftBrightness(surf, 0.025f, surfAlpha)
        SurfaceCard = card
        SurfaceElevated = shiftBrightness(card, -0.02f, cardAlpha)
        AccentBlue = accent
        BorderSubtle = border
        BorderHighlight = shiftBrightness(
            border,
            0.12f,
            (settings.borderAlpha + 0.12f).coerceIn(0.25f, 1f)
        )
        UserBubbleBg = userBubble
        AiBubbleBg = aiBubble
        TerminalBg = termBg
        TerminalOutputBoxBg = shiftBrightness(termBg, 0.03f, settings.terminalBgAlpha.coerceIn(0.25f, 1f))
        TerminalText = termText
        TerminalPrompt = termPrompt
    }
}

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
    val onPrimaryColor = if (ForgeColors.AccentBlue.luminance() > 0.58f) Color(0xFF0B0F17) else Color.White
    val scheme = darkColorScheme(
        primary = ForgeColors.AccentBlue,
        onPrimary = onPrimaryColor,
        secondary = ForgeColors.AccentEmerald,
        onSecondary = Color.Black,
        tertiary = ForgeColors.AccentAmber,
        background = ForgeColors.BgDeep,
        onBackground = ForgeColors.TextPrimary,
        surface = ForgeColors.SurfaceDark,
        onSurface = ForgeColors.TextPrimary,
        surfaceVariant = ForgeColors.SurfaceElevated,
        onSurfaceVariant = ForgeColors.TextSecondary,
        outline = ForgeColors.BorderHighlight,
        outlineVariant = ForgeColors.BorderSubtle,
        error = ForgeColors.DangerRed,
        onError = Color.White
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = ForgeTypography,
        content = content
    )
}

val MonoFont: FontFamily = FontFamily.Monospace
