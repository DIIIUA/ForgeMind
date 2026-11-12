package com.forgemind.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.ui.theme.ForgeColors

/**
 * Кнопка открытия отдельного всплывающего выпадающего списка моделей (`DropdownMenu` в Popup-окне).
 * Не делит жест прокрутки с `ModalBottomSheet` или прокручиваемым экраном настроек.
 */
@Composable
fun ModelSelectorButton(
    models: List<ModelInfo>,
    selectedModelId: String,
    scanning: Boolean,
    onSelectModel: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier,
    onRequestScan: (() -> Unit)? = null
) {
    var expanded by remember(models) { mutableStateOf(false) }
    var openAfterScan by remember { mutableStateOf(false) }
    var buttonWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(models, scanning) {
        if (openAfterScan && !scanning) {
            openAfterScan = false
            if (models.isNotEmpty()) {
                expanded = true
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                if (models.isNotEmpty()) {
                    expanded = true
                } else if (!scanning && onRequestScan != null) {
                    openAfterScan = true
                    onRequestScan()
                }
            },
            enabled = !scanning && (models.isNotEmpty() || onRequestScan != null),
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { buttonWidthPx = it.width },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        scanning -> "Загрузка списка моделей..."
                        models.isNotEmpty() -> {
                            val activeLabel = selectedModelId.ifBlank { models.first().id }
                            "Выбрать модель (${models.size}): $activeLabel"
                        }
                        else -> "Список моделей (нажмите, чтобы загрузить)"
                    },
                    fontSize = 12.5.sp,
                    color = ForgeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Открыть список моделей",
                        tint = ForgeColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        val menuWidth = with(density) { buttonWidthPx.toDp() }
        DropdownMenu(
            expanded = expanded && models.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .then(
                    if (buttonWidthPx > 0) Modifier.width(menuWidth) else Modifier.widthIn(min = 260.dp, max = 380.dp)
                )
                .heightIn(max = 360.dp)
        ) {
            models.forEachIndexed { index, model ->
                val isSelected = model.id == selectedModelId
                val meta = buildString {
                    if (model.contextLength != null) append("${model.contextLength / 1000}K ctx ")
                    if (!model.pricingHint.isNullOrBlank()) append(model.pricingHint)
                }.trim()
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = model.id,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) ForgeColors.AccentBlue else ForgeColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    fontSize = 10.5.sp,
                                    color = ForgeColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Выбрана",
                                tint = ForgeColors.AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelectModel(model)
                    }
                )
                if (index < models.lastIndex) {
                    HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.5f))
                }
            }
        }
    }
}
