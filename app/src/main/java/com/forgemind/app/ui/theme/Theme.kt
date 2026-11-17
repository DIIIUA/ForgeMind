package com.forgemind.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.forgemind.app.R
import com.forgemind.app.data.model.UiThemeSettings
import kotlin.math.roundToInt

/**
 * Graphite design system (2026-11 redesign).
 *
 * Surfaces get lighter as they rise: BgDeep → SurfaceDark → SurfaceCard → SurfaceElevated.
 * One accent: [AccentFill] for filled controls, [AccentText] (= legacy [AccentBlue]) for text and icons.
 * Green, amber and red are reserved for statuses.
 */
object ForgeColors {
    var BgDeep by mutableStateOf(Color(0xFF0D0F13))
    var SurfaceDark by mutableStateOf(Color(0xFF15181E))
    var SurfaceCard by mutableStateOf(Color(0xFF1C2028))
    var SurfaceElevated by mutableStateOf(Color(0xFF242933))
    var SurfaceGlass by mutableStateOf(Color(0xFF181B22))

    /** Filled accent (send, allow, primary buttons) — white content on top. */
    var AccentFill by mutableStateOf(Color(0xFF2B66DE))
    /** Accent for text, icons, rings and selected states. */
    var AccentText by mutableStateOf(Color(0xFF7AA7FF))
    /** 16% accent wash for selected chips, nav indicator and icon tiles. */
    var AccentTint by mutableStateOf(Color(0xFF7AA7FF).copy(alpha = 0.16f))
    /** Legacy name used across the app for accent text/icons. */
    var AccentBlue by mutableStateOf(Color(0xFF7AA7FF))
    /** Legacy second accent; folded into the single accent. */
    var AccentCyan by mutableStateOf(Color(0xFF7AA7FF))

    // Status colors only.
    var AccentEmerald by mutableStateOf(Color(0xFF43D18C))
    var AccentAmber by mutableStateOf(Color(0xFFF2B33D))
    var DangerRed by mutableStateOf(Color(0xFFFF6B6B))

    var TextPrimary by mutableStateOf(Color(0xFFECEEF2))
    var TextSecondary by mutableStateOf(Color(0xFFA3AAB8))
    var TextTertiary by mutableStateOf(Color(0xFF858D9C))
    var TextDisabled by mutableStateOf(Color(0xFF6A7180))

    /** Only the composer and code blocks get a visible border. */
    var BorderSubtle by mutableStateOf(Color(0xFF2A2F39))
    var BorderHighlight by mutableStateOf(Color(0xFF3A404C))
    val Divider = Color.White.copy(alpha = 0.06f)

    val DiffAddBg = Color(0xFF43D18C).copy(alpha = 0.10f)
    val DiffAddText = Color(0xFF9BE8C3)
    val DiffDelBg = Color(0xFFFF6B6B).copy(alpha = 0.10f)
    val DiffDelText = Color(0xFFFFB3B3)

    var UserBubbleBg by mutableStateOf(Color(0xFF1E2A44))
    /** AI answers have no bubble; this tints tool groups and the thinking block. */
    var AiBubbleBg by mutableStateOf(Color(0xFF1C2028))
    var TerminalBg by mutableStateOf(Color(0xFF0B0D11))
    var TerminalOutputBoxBg by mutableStateOf(Color(0xFF101318))
    var TerminalText by mutableStateOf(Color(0xFFDCE2EA))
    var TerminalPrompt by mutableStateOf(Color(0xFF7AA7FF))

    val Scrim = Color(0xFF050609).copy(alpha = 0.62f)

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

    /** Readable accent for text on dark surfaces, derived from the fill accent while preserving user alpha. */
    fun accentTextFor(fill: Color): Color {
        val opaque = fill.copy(alpha = 1f)
        val readable = if (opaque.luminance() > 0.38f) opaque else lerp(opaque, Color.White, 0.42f)
        return readable.copy(alpha = fill.alpha)
    }

    fun applyTheme(settings: UiThemeSettings) {
        val bg = parseHexColor(settings.bgDeepHex, Color(0xFF0D0F13), 1f)
        val surfAlpha = settings.surfaceAlpha.coerceIn(0.2f, 1f)
        val cardAlpha = settings.cardAlpha.coerceIn(0.2f, 1f)
        val surf = parseHexColor(settings.surfaceDarkHex, Color(0xFF15181E), surfAlpha)
        val card = parseHexColor(settings.surfaceCardHex, Color(0xFF1C2028), cardAlpha)
        val accent = parseHexColor(
            settings.accentHex,
            Color(0xFF2B66DE),
            settings.accentAlpha.coerceIn(0.35f, 1f)
        )
        val border = parseHexColor(
            settings.borderHex,
            Color(0xFF2A2F39),
            settings.borderAlpha.coerceIn(0.15f, 1f)
        )
        val userBubble = parseHexColor(
            settings.userBubbleHex,
            Color(0xFF1E2A44),
            settings.userBubbleAlpha.coerceIn(0.2f, 1f)
        )
        val aiBubble = parseHexColor(
            settings.aiBubbleHex,
            Color(0xFF1C2028),
            settings.aiBubbleAlpha.coerceIn(0.2f, 1f)
        )
        val termBg = parseHexColor(
            settings.terminalBgHex,
            Color(0xFF0B0D11),
            settings.terminalBgAlpha.coerceIn(0.25f, 1f)
        )
        val termText = parseHexColor(settings.terminalTextHex, Color(0xFFDCE2EA), 1f)
        val termPrompt = parseHexColor(settings.terminalPromptHex, Color(0xFF7AA7FF), 1f)
        val accentText = accentTextFor(accent)

        BgDeep = bg
        SurfaceDark = surf
        SurfaceGlass = shiftBrightness(surf, 0.015f, surfAlpha)
        SurfaceCard = card
        SurfaceElevated = shiftBrightness(card, 0.03f, cardAlpha)
        AccentFill = accent
        AccentText = accentText
        AccentBlue = accentText
        AccentCyan = accentText
        AccentTint = accentText.copy(alpha = 0.16f)
        BorderSubtle = border
        BorderHighlight = shiftBrightness(
            border,
            0.06f,
            (settings.borderAlpha + 0.12f).coerceIn(0.25f, 1f)
        )
        UserBubbleBg = userBubble
        AiBubbleBg = aiBubble
        TerminalBg = termBg
        TerminalOutputBoxBg = shiftBrightness(termBg, 0.02f, settings.terminalBgAlpha.coerceIn(0.25f, 1f))
        TerminalText = termText
        TerminalPrompt = termPrompt
    }
}

/** Inter (variable) — UI text. */
@OptIn(ExperimentalTextApi::class)
val InterFont: FontFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

/** JetBrains Mono (variable) — code, tool names, numbers. */
@OptIn(ExperimentalTextApi::class)
val MonoFont: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.jetbrains_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

/**
 * Five sizes only: 12 (captions), 13 (meta, labels), 15 (body, buttons), 17 (titles), 24 (screen headings).
 */
object ForgeType {
    val Caption = 12.sp
    val Meta = 13.sp
    val Body = 15.sp
    val Title = 17.sp
    val Display = 24.sp
}

private fun inter(size: TextUnit, weight: FontWeight, lineHeight: TextUnit) =
    TextStyle(fontFamily = InterFont, fontSize = size, fontWeight = weight, lineHeight = lineHeight)

private val ForgeTypography = Typography(
    displaySmall = inter(ForgeType.Display, FontWeight.Bold, 30.sp),
    headlineLarge = inter(ForgeType.Display, FontWeight.Bold, 30.sp),
    headlineMedium = inter(ForgeType.Display, FontWeight.Bold, 30.sp),
    headlineSmall = inter(ForgeType.Display, FontWeight.Bold, 30.sp),
    titleLarge = inter(ForgeType.Title, FontWeight.SemiBold, 24.sp),
    titleMedium = inter(ForgeType.Title, FontWeight.SemiBold, 24.sp),
    titleSmall = inter(ForgeType.Body, FontWeight.SemiBold, 20.sp),
    bodyLarge = inter(ForgeType.Body, FontWeight.Normal, 23.sp),
    bodyMedium = inter(ForgeType.Body, FontWeight.Normal, 22.sp),
    bodySmall = inter(ForgeType.Meta, FontWeight.Normal, 18.sp),
    labelLarge = inter(ForgeType.Body, FontWeight.SemiBold, 20.sp),
    labelMedium = inter(ForgeType.Meta, FontWeight.Medium, 18.sp),
    labelSmall = inter(ForgeType.Caption, FontWeight.Medium, 16.sp)
)

@Composable
fun ForgeMindTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        // M3 convention for dark themes: light primary with dark content keeps text buttons readable.
        primary = ForgeColors.AccentText,
        onPrimary = Color(0xFF0B1630),
        primaryContainer = ForgeColors.AccentFill,
        onPrimaryContainer = Color.White,
        secondary = ForgeColors.AccentText,
        onSecondary = Color(0xFF0B1630),
        secondaryContainer = ForgeColors.AccentTint,
        onSecondaryContainer = ForgeColors.AccentText,
        tertiary = ForgeColors.AccentAmber,
        background = ForgeColors.BgDeep,
        onBackground = ForgeColors.TextPrimary,
        surface = ForgeColors.SurfaceDark,
        onSurface = ForgeColors.TextPrimary,
        surfaceVariant = ForgeColors.SurfaceElevated,
        onSurfaceVariant = ForgeColors.TextSecondary,
        surfaceContainerLowest = ForgeColors.BgDeep,
        surfaceContainerLow = ForgeColors.SurfaceDark,
        surfaceContainer = ForgeColors.SurfaceDark,
        surfaceContainerHigh = ForgeColors.SurfaceCard,
        surfaceContainerHighest = ForgeColors.SurfaceElevated,
        outline = ForgeColors.BorderHighlight,
        outlineVariant = ForgeColors.BorderSubtle,
        error = ForgeColors.DangerRed,
        onError = Color(0xFF2A0B0B),
        scrim = Color(0xFF050609)
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = ForgeTypography,
        content = content
    )
}
