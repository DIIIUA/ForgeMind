package com.forgemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.forgemind.app.data.model.ContextWindowResolver
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont

internal class PreparedModelItem(
    val model: ModelInfo,
    val shortTitle: String,
    val showSubtitleId: Boolean,
    val isFree: Boolean,
    val tagsLower: Set<String>,
    val searchBlob: String
)

internal class PreparedModelCatalog private constructor(
    val items: List<PreparedModelItem>,
    val byId: Map<String, ModelInfo>,
    val categories: List<String>,
    val hasAnyFree: Boolean
) {
    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    fun filter(
        query: String,
        selectedCategory: String? = null,
        onlyFree: Boolean = false,
        favoriteIdsForProvider: Set<String> = emptySet()
    ): List<PreparedModelItem> {
        val q = query.trim().lowercase()
        val catLower = selectedCategory?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        if (!onlyFree && catLower == null && q.isEmpty() && favoriteIdsForProvider.isEmpty()) {
            return items
        }

        if (favoriteIdsForProvider.isEmpty()) {
            val out = ArrayList<PreparedModelItem>(minOf(items.size, 256))
            for (item in items) {
                if (onlyFree && !item.isFree) continue
                if (catLower != null && catLower !in item.tagsLower) continue
                if (q.isNotEmpty() && !item.searchBlob.contains(q)) continue
                out.add(item)
            }
            return out
        }

        val favOut = ArrayList<PreparedModelItem>(favoriteIdsForProvider.size)
        val restOut = ArrayList<PreparedModelItem>(minOf(items.size, 256))
        for (item in items) {
            if (onlyFree && !item.isFree) continue
            if (catLower != null && catLower !in item.tagsLower) continue
            if (q.isNotEmpty() && !item.searchBlob.contains(q)) continue
            if (item.model.id in favoriteIdsForProvider) {
                favOut.add(item)
            } else {
                restOut.add(item)
            }
        }
        if (favOut.isEmpty()) return restOut
        if (restOut.isEmpty()) return favOut
        favOut.addAll(restOut)
        return favOut
    }

    companion object {
        private const val MAX_CATEGORY_CHIPS = 20
        private const val MAX_CATEGORY_TAG_LEN = 24
        private val IGNORED_CATEGORY_TAGS = setOf("beta", "offline", "free", "pinned")

        fun from(rawModels: List<ModelInfo>): PreparedModelCatalog {
            if (rawModels.isEmpty()) {
                return PreparedModelCatalog(
                    items = emptyList(),
                    byId = emptyMap(),
                    categories = emptyList(),
                    hasAnyFree = false
                )
            }
            val deduped = ModelScanner.deduplicateModels(rawModels)
            val prepared = ArrayList<PreparedModelItem>(deduped.size)
            val idMap = HashMap<String, ModelInfo>(deduped.size * 2)
            val tagCounts = LinkedHashMap<String, Pair<String, Int>>()
            var anyFree = false

            for (m in deduped) {
                idMap[m.id] = m
                val hint = m.pricingHint.orEmpty()
                val free = m.id.endsWith(":free") || hint.contains("FREE", ignoreCase = true)
                if (free) anyFree = true

                val tags = if (hint.isBlank()) {
                    emptySet()
                } else {
                    val set = HashSet<String>(4)
                    for (rawTag in hint.split("·")) {
                        val trimmed = rawTag.trim()
                        if (trimmed.isEmpty()) continue
                        val lower = trimmed.lowercase()
                        set.add(lower)
                        if (trimmed.length <= MAX_CATEGORY_TAG_LEN && lower !in IGNORED_CATEGORY_TAGS) {
                            val existing = tagCounts[lower]
                            if (existing == null) {
                                tagCounts[lower] = trimmed to 1
                            } else {
                                tagCounts[lower] = existing.first to (existing.second + 1)
                            }
                        }
                    }
                    set
                }

                val displayBase = m.name.ifBlank { m.id }
                val shortTitle = displayBase.substringAfterLast('/')
                val blob = buildString(m.id.length + m.name.length + hint.length + 2) {
                    append(m.id.lowercase())
                    append('\n')
                    append(m.name.lowercase())
                    if (hint.isNotEmpty()) {
                        append('\n')
                        append(hint.lowercase())
                    }
                }

                prepared.add(
                    PreparedModelItem(
                        model = m,
                        shortTitle = shortTitle,
                        showSubtitleId = m.name != m.id,
                        isFree = free,
                        tagsLower = tags,
                        searchBlob = blob
                    )
                )
            }

            val topCategories = tagCounts.values
                .sortedByDescending { it.second }
                .take(MAX_CATEGORY_CHIPS)
                .map { it.first }

            return PreparedModelCatalog(
                items = prepared,
                byId = idMap,
                categories = topCategories,
                hasAnyFree = anyFree
            )
        }
    }
}

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

    val catalog = remember(models) { PreparedModelCatalog.from(models) }

    val activeLabel = remember(currentModelId, catalog) {
        val cleanId = currentModelId.trim()
        val found = catalog.byId[cleanId]
        val base = found?.name?.takeIf { it.isNotBlank() } ?: cleanId
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
                    if (catalog.isEmpty && !isScanning) onScanIfNeeded()
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
                catalog = catalog,
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
    catalog: PreparedModelCatalog,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var onlyFree by remember { mutableStateOf(false) }

    val filtered = remember(catalog, query, selectedCategory, onlyFree) {
        catalog.filter(
            query = query,
            selectedCategory = selectedCategory,
            onlyFree = onlyFree
        )
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
                    val baseTitle = if (isScanning && catalog.isEmpty) {
                        stringResource(R.string.model_scanning)
                    } else {
                        stringResource(R.string.model_picker_title, catalog.size)
                    }
                    val fullTitle = if (!catalog.isEmpty && filtered.size != catalog.size) {
                        "$baseTitle (${filtered.size})"
                    } else {
                        baseTitle
                    }
                    Text(
                        text = fullTitle,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
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

                // Virtualized category + FREE filter chips
                if (catalog.categories.size > 1 || catalog.hasAnyFree) {
                    val allLabel = stringResource(R.string.model_filter_all)
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item(key = "__all__") {
                            FilterChip(
                                selected = selectedCategory == null && !onlyFree,
                                onClick = {
                                    selectedCategory = null
                                    onlyFree = false
                                },
                                label = { Text("$allLabel (${catalog.size})", fontSize = 11.sp) }
                            )
                        }
                        if (catalog.hasAnyFree) {
                            item(key = "__free__") {
                                FilterChip(
                                    selected = onlyFree,
                                    onClick = { onlyFree = !onlyFree },
                                    label = { Text("FREE", fontSize = 11.sp, color = ForgeColors.AccentEmerald) }
                                )
                            }
                        }
                        items(catalog.categories, key = { "cat_$it" }) { cat ->
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
                        val cleanCurrent = currentModelId.trim()
                        val selectedDesc = stringResource(R.string.model_selected_desc)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            items(
                                items = filtered,
                                key = { it.model.id },
                                contentType = { "picker_model_row" }
                            ) { item ->
                                val m = item.model
                                val isSelected = m.id == cleanCurrent
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
                                                text = item.shortTitle,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (item.isFree) ForgeColors.AccentEmerald else ForgeColors.TextPrimary,
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
                                                        color = if (item.isFree) ForgeColors.AccentEmerald else ForgeColors.AccentCyan,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            m.contextLength?.takeIf { it > 0 }?.let { ctx ->
                                                Text(
                                                    text = ContextWindowResolver.formatTokensShort(ctx),
                                                    fontSize = 10.sp,
                                                    color = ForgeColors.TextSecondary
                                                )
                                            }
                                        }
                                        if (item.showSubtitleId) {
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
                                            contentDescription = selectedDesc,
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
