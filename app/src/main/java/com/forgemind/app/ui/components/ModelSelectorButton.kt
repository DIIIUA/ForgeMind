package com.forgemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forgemind.app.R
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont

@Composable
fun ModelSelectorButton(
    currentModelId: String,
    models: List<ModelInfo>,
    isScanning: Boolean,
    onScanIfNeeded: () -> Unit,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val activeLabel = remember(currentModelId, models) {
        val found = models.firstOrNull { it.id == currentModelId }
        val base = found?.name?.takeIf { it.isNotBlank() } ?: currentModelId
        base.substringAfterLast('/')
    }

    Box(modifier = modifier) {
        Surface(
            color = ForgeColors.SurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ForgeColors.BorderHighlight),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    if (models.isEmpty() && !isScanning) onScanIfNeeded()
                    expanded = true
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(min = 110.dp, max = 230.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = ForgeColors.AccentBlue,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = activeLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ForgeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = ForgeColors.AccentBlue
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(R.string.model_open_desc),
                        tint = ForgeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (expanded) {
            ModelPickerDialog(
                currentModelId = currentModelId,
                models = models,
                isScanning = isScanning,
                onDismiss = { expanded = false },
                onSelect = { id ->
                    onSelectModel(id)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun ModelPickerDialog(
    currentModelId: String,
    models: List<ModelInfo>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val categories = remember(models) {
        models.flatMap { m ->
            m.pricingHint.orEmpty()
                .split("·")
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "beta" && it != "offline" }
        }.distinct()
    }

    val filtered = remember(models, query, selectedCategory) {
        val q = query.trim().lowercase()
        models.filter { m ->
            val matchesCat = selectedCategory == null ||
                m.pricingHint.orEmpty().split("·").any { it.trim().equals(selectedCategory, ignoreCase = true) }
            val matchesQuery = q.isEmpty() ||
                m.id.lowercase().contains(q) ||
                m.name.lowercase().contains(q) ||
                m.pricingHint.orEmpty().lowercase().contains(q)
            matchesCat && matchesQuery
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ForgeColors.SurfaceCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(min = 260.dp, max = 560.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeColors.SurfaceDark)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isScanning && models.isEmpty()) {
                            stringResource(R.string.model_scanning)
                        } else {
                            stringResource(R.string.model_picker_title, models.size)
                        },
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TextPrimary
                    )
                    ForgeIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        size = 32.dp,
                        iconSize = 17.dp
                    )
                }
                HorizontalDivider(color = ForgeColors.BorderSubtle)

                // Search Box
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.model_search_hint), fontSize = 12.5.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                // Category filter chips when modalities exist (e.g. Venice text/image/video/music/tts/asr)
                if (categories.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("${stringResource(R.string.model_filter_all)} (${models.size})", fontSize = 11.sp) }
                        )
                        categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                label = { Text(cat, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.6f))

                when {
                    filtered.isEmpty() && isScanning -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.model_no_matches),
                                color = ForgeColors.TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            items(filtered, key = { it.id }) { m ->
                                val isSelected = m.id == currentModelId
                                val isFree = m.id.endsWith(":free") || m.pricingHint?.contains("FREE", ignoreCase = true) == true
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(m.id) }
                                        .background(if (isSelected) ForgeColors.AccentBlue.copy(alpha = 0.14f) else ForgeColors.SurfaceCard)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = m.name.ifBlank { m.id }.substringAfterLast('/'),
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isFree) ForgeColors.AccentEmerald else ForgeColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            m.pricingHint?.takeIf { it.isNotBlank() }?.let { hint ->
                                                Surface(
                                                    color = ForgeColors.SurfaceElevated,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = hint,
                                                        fontSize = 9.5.sp,
                                                        color = if (isFree) ForgeColors.AccentEmerald else ForgeColors.AccentCyan,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            m.contextLength?.let { ctx ->
                                                Text(
                                                    text = stringResource(R.string.model_ctx_k, ctx / 1000),
                                                    fontSize = 10.sp,
                                                    color = ForgeColors.TextSecondary
                                                )
                                            }
                                        }
                                        if (m.name != m.id) {
                                            Text(
                                                text = m.id,
                                                fontFamily = MonoFont,
                                                fontSize = 10.5.sp,
                                                color = ForgeColors.TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = stringResource(R.string.model_selected_desc),
                                            tint = ForgeColors.AccentBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        }
    }
}
