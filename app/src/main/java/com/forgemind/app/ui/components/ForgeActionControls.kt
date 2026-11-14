package com.forgemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.ui.theme.ForgeColors

/**
 * High-contrast framed icon button that remains clearly visible across all dark surfaces,
 * with distinct active, normal, and disabled visual states.
 */
@Composable
fun ForgeIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = if (active) ForgeColors.AccentBlue else ForgeColors.TextPrimary,
    activeColor: Color = ForgeColors.AccentBlue,
    containerColor: Color = if (active) activeColor.copy(alpha = 0.18f) else ForgeColors.SurfaceElevated,
    borderColor: Color = if (active) activeColor.copy(alpha = 0.72f) else ForgeColors.BorderHighlight.copy(alpha = 0.75f),
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    cornerRadius: Dp = 10.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        color = if (enabled) containerColor else ForgeColors.SurfaceDark,
        shape = shape,
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) borderColor else ForgeColors.BorderSubtle.copy(alpha = 0.45f)
        ),
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.42f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else ForgeColors.TextSecondary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * High-visibility single-line pill button that never wraps Russian labels vertically.
 */
@Composable
fun ForgeActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    accentColor: Color = ForgeColors.AccentBlue,
    contentColor: Color = if (active) accentColor else ForgeColors.TextPrimary,
    containerColor: Color = if (active) accentColor.copy(alpha = 0.18f) else ForgeColors.SurfaceElevated,
    borderColor: Color = if (active) accentColor.copy(alpha = 0.75f) else ForgeColors.BorderHighlight.copy(alpha = 0.8f),
    fontSize: TextUnit = 11.5.sp,
    minHeight: Dp = 32.dp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 5.dp
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        color = if (enabled) containerColor else ForgeColors.SurfaceDark,
        shape = shape,
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) borderColor else ForgeColors.BorderSubtle.copy(alpha = 0.45f)
        ),
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.45f))
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
                    tint = if (enabled) accentColor else ForgeColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) contentColor else ForgeColors.TextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
