package com.forgemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType

/**
 * Borderless icon button. Depth comes from a tonal fill only when active; otherwise the icon sits on
 * the surface. Pass `size = 48.dp` for a full touch target in new layouts.
 */
@Composable
fun ForgeIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = if (active) ForgeColors.AccentText else ForgeColors.TextSecondary,
    activeColor: Color = ForgeColors.AccentText,
    containerColor: Color = if (active) activeColor.copy(alpha = 0.16f) else Color.Transparent,
    borderColor: Color = Color.Transparent,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    cornerRadius: Dp = size / 2
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor, shape)
            .then(if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .then(
                if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.alpha(0.42f)
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else ForgeColors.TextDisabled,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Tonal pill button that never wraps labels. `active` switches to the accent wash.
 */
@Composable
fun ForgeActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    accentColor: Color = ForgeColors.AccentText,
    contentColor: Color = if (active) accentColor else ForgeColors.TextPrimary,
    containerColor: Color = if (active) accentColor.copy(alpha = 0.16f) else ForgeColors.SurfaceElevated,
    borderColor: Color = Color.Transparent,
    fontSize: TextUnit = ForgeType.Meta,
    minHeight: Dp = 36.dp,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 6.dp
) {
    val shape = RoundedCornerShape(50)
    Surface(
        color = if (enabled) containerColor else ForgeColors.SurfaceCard,
        shape = shape,
        border = if (borderColor != Color.Transparent) BorderStroke(1.dp, borderColor) else null,
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.alpha(0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accentColor else ForgeColors.TextDisabled,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) contentColor else ForgeColors.TextDisabled,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Filled accent pill (Send, Allow, Retry, New chat): accent fill, white content, 44–48 dp tall. */
@Composable
fun ForgeFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = ForgeColors.AccentFill,
    contentColor: Color = Color.White,
    height: Dp = 44.dp
) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = height)
            .clip(shape)
            .background(if (enabled) containerColor else ForgeColors.SurfaceElevated, shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp)
    ) {
        val tint = if (enabled) contentColor else ForgeColors.TextDisabled
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, fontSize = ForgeType.Body, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

/** 6 dp status dot; `hollow` draws a ring for "off / not configured". */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, hollow: Boolean = false, size: Dp = 6.dp) {
    if (hollow) {
        Box(modifier = modifier.size(size + 1.dp).border(1.5.dp, ForgeColors.TextTertiary, CircleShape))
    } else {
        Box(modifier = modifier.size(size).background(color, CircleShape))
    }
}

/** Context ring for the chat header: accent below 60 %, amber below 80 %, red above. */
@Composable
fun ContextRing(ratio: Float, modifier: Modifier = Modifier, diameter: Dp = 26.dp) {
    val clamped = ratio.coerceIn(0f, 1f)
    val color = when {
        clamped >= 0.80f -> ForgeColors.DangerRed
        clamped >= 0.60f -> ForgeColors.AccentAmber
        else -> ForgeColors.AccentText
    }
    val track = ForgeColors.BorderSubtle
    Canvas(modifier = modifier.size(diameter)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (clamped > 0f) {
            drawArc(
                color, -90f, 360f * clamped.coerceAtLeast(0.02f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    }
}
