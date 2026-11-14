package com.forgemind.app.ui.screens.settings

import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.forgemind.app.R
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ContextWindowResolver
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchEngineType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.UiThemeSettings
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.data.model.WireProtocol
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.i18n.AppLanguage
import com.forgemind.app.llm.ConnectionTestResult
import com.forgemind.app.llm.DiscoveredLanAiServer
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.mcp.ForgeMcpHostServer
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ForgeActionPill
import com.forgemind.app.ui.components.ForgeIconButton
import com.forgemind.app.ui.components.ModelSelectorButton
import com.forgemind.app.ui.components.copyToClipboard
import com.forgemind.app.ui.formatSafTreeLabelLocalized
import com.forgemind.app.ui.localizedLabel
import com.forgemind.app.ui.localizedName
import com.forgemind.app.ui.localizedSubtitle
import com.forgemind.app.ui.resolvedDisplayName
import com.forgemind.app.ui.resolvedSubtitle
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.math.roundToInt

private enum class SettingsTab(@StringRes val labelRes: Int) {
    PROVIDERS(R.string.settings_tab_providers),
    ENVIRONMENTS(R.string.settings_tab_envs),
    THEME(R.string.settings_tab_theme),
    VOICE(R.string.settings_tab_voice),
    MCP(R.string.settings_tab_mcp),
    AGENT_WEB(R.string.settings_tab_agent_web)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
    onPickSafFolder: () -> Unit,
    onLanguageChanged: () -> Unit = {}
) {
    var activeTab by remember { mutableStateOf(SettingsTab.PROVIDERS) }
    val appLanguageCode by vm.appLanguage.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }
    val activeLang = AppLanguage.fromCode(appLanguageCode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.BgDeep)
    ) {
        Surface(color = ForgeColors.SurfaceDark) {
            Column(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SettingsTab.entries.forEach { tab ->
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = activeTab == tab,
                                borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.7f),
                                selectedBorderColor = ForgeColors.AccentBlue
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ForgeColors.SurfaceElevated,
                                labelColor = ForgeColors.TextPrimary,
                                selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.2f),
                                selectedLabelColor = ForgeColors.AccentBlue
                            ),
                            label = {
                                Text(
                                    stringResource(tab.labelRes),
                                    fontSize = 11.5.sp,
                                    fontWeight = if (activeTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }
                HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.45f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLanguageDialog = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            tint = ForgeColors.AccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_lang_title),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = ForgeColors.TextPrimary
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (activeLang == AppLanguage.SYSTEM) {
                                stringResource(R.string.settings_lang_system)
                            } else {
                                activeLang.displayName
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForgeColors.AccentBlue
                        )
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = ForgeColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(stringResource(R.string.settings_lang_dialog_title), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.settings_lang_subtitle),
                            fontSize = 11.5.sp,
                            color = ForgeColors.TextSecondary
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(AppLanguage.entries, key = { it.code }) { lang ->
                                val isSelected = lang == activeLang
                                Surface(
                                    color = if (isSelected) ForgeColors.AccentBlue.copy(alpha = 0.2f) else ForgeColors.SurfaceCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (isSelected) BorderStroke(1.dp, ForgeColors.AccentBlue) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vm.setAppLanguage(lang.code)
                                            showLanguageDialog = false
                                            onLanguageChanged()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (lang == AppLanguage.SYSTEM) {
                                                stringResource(R.string.settings_lang_system)
                                            } else {
                                                lang.displayName
                                            },
                                            fontSize = 13.5.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = ForgeColors.TextPrimary
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = ForgeColors.AccentBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            )
        }

        AnimatedContent(
            targetState = activeTab,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val shift = if (forward) 18 else -18
                (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { shift })
                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -shift })
            },
            label = "settingsTab"
        ) { tab ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    SettingsTab.PROVIDERS -> ProvidersSettingsPane(vm)
                    SettingsTab.ENVIRONMENTS -> EnvironmentsSettingsPane(vm, onPickSafFolder)
                    SettingsTab.THEME -> ThemeSettingsPane(vm)
                    SettingsTab.VOICE -> VoiceSettingsPane(vm)
                    SettingsTab.MCP -> McpSettingsPane(vm)
                    SettingsTab.AGENT_WEB -> AgentAndWebSettingsPane(vm, onExportZip, onImportZip)
                }
            }
        }
    }
}

private data class NewProviderPreset(
    val chipLabel: String,
    val defaultName: String,
    val baseUrl: String,
    val modelId: String,
    val wireProtocol: WireProtocol,
    val authMode: ProviderAuthMode,
    val contextWindow: Int = 32_768,
    val rawEndpointUrl: Boolean = false,
    val customAuthHeader: String = "X-API-Key",
    val customQueryParam: String = "key"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProvidersSettingsPane(vm: MainViewModel) {
    val context = LocalContext.current
    val providerConfigs by vm.providerConfigs.collectAsState()
    val hiddenBuiltInProviders by vm.hiddenBuiltInProviders.collectAsState()
    val providerHealth by vm.providerHealth.collectAsState()
    val scannedModels by vm.scannedModels.collectAsState()
    val activeProviderId by vm.activeProviderId.collectAsState()

    var selectedProvId by remember { mutableStateOf(activeProviderId) }
    var categoryFilter by remember { mutableStateOf<ProviderCategory?>(null) }
    var showLanScannerDialog by remember { mutableStateOf(false) }
    var createDialogCategory by remember { mutableStateOf<ProviderCategory?>(null) }
    var showDeleteProviderDialog by remember { mutableStateOf(false) }
    var showResetDefaultsDialog by remember { mutableStateOf(false) }
    var wipeKeysChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.probeProvidersHealth()
    }

    val orderedProviders = remember(providerConfigs, hiddenBuiltInProviders) {
        SettingsRepository.orderedProviderConfigs(providerConfigs, hiddenBuiltInProviders)
    }
    LaunchedEffect(orderedProviders, activeProviderId) {
        if (orderedProviders.isNotEmpty() && orderedProviders.none { it.providerId == selectedProvId }) {
            selectedProvId = orderedProviders.firstOrNull { it.providerId == activeProviderId }?.providerId
                ?: orderedProviders.first().providerId
        }
    }

    val filteredProviders = remember(orderedProviders, categoryFilter) {
        if (categoryFilter == null) orderedProviders
        else orderedProviders.filter { it.effectiveCategory() == categoryFilter }
    }

    val saved = providerConfigs[selectedProvId] ?: ProviderConfig(selectedProvId)
    val pType = ProviderType.fromId(selectedProvId)
    val effCategory = saved.effectiveCategory()
    val isBuiltInCloud = saved.isBuiltInProvider() && effCategory == ProviderCategory.CLOUD

    var customName by remember(selectedProvId, saved) { mutableStateOf(saved.customDisplayName) }
    var customSubtitle by remember(selectedProvId, saved) { mutableStateOf(saved.customSubtitle) }
    var wireProto by remember(selectedProvId, saved) { mutableStateOf(saved.effectiveWireProtocol()) }
    var hasProtoOverride by remember(selectedProvId, saved) { mutableStateOf(saved.wireProtocolOverride != null) }
    var authMode by remember(selectedProvId, saved) { mutableStateOf(saved.authMode) }
    var customAuthHeader by remember(selectedProvId, saved) { mutableStateOf(saved.customAuthHeader) }
    var customAuthPrefix by remember(selectedProvId, saved) { mutableStateOf(saved.customAuthPrefix) }
    var customQueryParam by remember(selectedProvId, saved) { mutableStateOf(saved.customQueryParam) }
    var keysRaw by remember(selectedProvId, saved) { mutableStateOf(saved.apiKeysRaw) }
    var baseUrl by remember(selectedProvId, saved) { mutableStateOf(saved.baseUrl) }
    var rawEndpointUrl by remember(selectedProvId, saved) { mutableStateOf(saved.rawEndpointUrl) }
    var modelsEndpointUrl by remember(selectedProvId, saved) { mutableStateOf(saved.modelsEndpointUrl) }
    var modelId by remember(selectedProvId, saved) { mutableStateOf(saved.modelId) }
    var savedModelsList by remember(selectedProvId, saved) { mutableStateOf(saved.savedModels) }
    var extraBodyJson by remember(selectedProvId, saved) { mutableStateOf(saved.extraBodyJson) }
    var headersJson by remember(selectedProvId, saved) { mutableStateOf(saved.customHeadersJson) }
    var contextWin by remember(selectedProvId, saved) { mutableStateOf(saved.contextWindow.toString()) }
    var autoContextWin by remember(selectedProvId, saved) { mutableStateOf(saved.autoContextWindow) }
    var modelCtxOverrides by remember(selectedProvId, saved) { mutableStateOf(saved.modelContextOverrides) }
    var maxTokensText by remember(selectedProvId, saved) { mutableStateOf(saved.maxTokens.toString()) }
    var timeoutSecText by remember(selectedProvId, saved) { mutableStateOf(saved.readTimeoutSec.toString()) }
    var allowInsecureSsl by remember(selectedProvId, saved) { mutableStateOf(saved.allowInsecureSsl) }
    var temperature by remember(selectedProvId, saved) { mutableFloatStateOf(saved.temperature) }
    var enableThinking by remember(selectedProvId, saved) { mutableStateOf(saved.enableThinking) }
    var autoRotate by remember(selectedProvId, saved) { mutableStateOf(saved.autoRotateKeys) }

    var showAdvanced by remember(selectedProvId) {
        mutableStateOf(
            !isBuiltInCloud ||
                saved.wireProtocolOverride != null ||
                saved.authMode != ProviderAuthMode.AUTO ||
                saved.rawEndpointUrl ||
                saved.modelsEndpointUrl.isNotBlank() ||
                (saved.extraBodyJson.isNotBlank() && saved.extraBodyJson != "{}") ||
                saved.allowInsecureSsl
        )
    }
    var scanStatus by remember(selectedProvId) { mutableStateOf<String?>(null) }
    var isScanning by remember(selectedProvId) { mutableStateOf(false) }
    var isTestingConn by remember(selectedProvId) { mutableStateOf(false) }
    var connTestResult by remember(selectedProvId) { mutableStateOf<ConnectionTestResult?>(null) }

    fun buildDraftConfig(): ProviderConfig {
        val resolvedProtoOverride = if (!saved.isBuiltInProvider() || hasProtoOverride || wireProto != pType.wireProtocol) {
            wireProto
        } else {
            null
        }
        return saved.copy(
            providerId = selectedProvId,
            apiKeysRaw = keysRaw,
            baseUrl = baseUrl.trim().ifBlank { pType.defaultBaseUrl },
            modelId = modelId.trim().ifBlank { pType.defaultModel },
            customHeadersJson = headersJson.trim().ifBlank { "{}" },
            contextWindow = (contextWin.toIntOrNull() ?: pType.defaultContextWindow).coerceAtLeast(1024),
            maxTokens = (maxTokensText.toIntOrNull() ?: saved.maxTokens).coerceIn(128, 128_000),
            readTimeoutSec = (timeoutSecText.toIntOrNull() ?: saved.readTimeoutSec).coerceIn(10, 900),
            temperature = temperature,
            enableThinking = enableThinking,
            autoRotateKeys = autoRotate,
            customDisplayName = customName.trim(),
            customSubtitle = customSubtitle.trim(),
            wireProtocolOverride = resolvedProtoOverride,
            authMode = authMode,
            customAuthHeader = customAuthHeader.trim().ifBlank { "X-API-Key" },
            customAuthPrefix = customAuthPrefix,
            customQueryParam = customQueryParam.trim().ifBlank { "key" },
            rawEndpointUrl = rawEndpointUrl,
            modelsEndpointUrl = modelsEndpointUrl.trim(),
            extraBodyJson = extraBodyJson.trim().ifBlank { "{}" },
            allowInsecureSsl = allowInsecureSsl,
            savedModels = savedModelsList,
            autoContextWindow = autoContextWin,
            modelContextOverrides = modelCtxOverrides
        )
    }

    // 1. Top Quick Action Bar: LAN Scanner + Add LAN + Add Custom External + Reset Default AI List
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ForgeActionPill(
            text = stringResource(R.string.prov_btn_scan_lan),
            icon = Icons.Rounded.Router,
            accentColor = ForgeColors.AccentEmerald,
            active = true,
            onClick = { showLanScannerDialog = true }
        )
        ForgeActionPill(
            text = stringResource(R.string.prov_btn_add_lan),
            icon = Icons.Rounded.Add,
            accentColor = ForgeColors.AccentCyan,
            onClick = { createDialogCategory = ProviderCategory.LOCAL_LAN }
        )
        ForgeActionPill(
            text = stringResource(R.string.prov_btn_add_custom),
            icon = Icons.Rounded.Tune,
            accentColor = ForgeColors.AccentAmber,
            onClick = { createDialogCategory = ProviderCategory.CUSTOM }
        )
        ForgeActionPill(
            text = stringResource(R.string.prov_reset_defaults_btn),
            icon = Icons.Rounded.Refresh,
            accentColor = ForgeColors.AccentBlue,
            onClick = {
                wipeKeysChecked = false
                showResetDefaultsDialog = true
            }
        )
    }

    // 2. Category Filter Bar (All / Cloud / Local LAN / Custom)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val catItems = listOf<Pair<ProviderCategory?, String>>(
            null to stringResource(R.string.prov_cat_all),
            ProviderCategory.CLOUD to stringResource(R.string.prov_cat_cloud),
            ProviderCategory.LOCAL_LAN to stringResource(R.string.prov_cat_lan),
            ProviderCategory.CUSTOM to stringResource(R.string.prov_cat_custom)
        )
        catItems.forEach { (cat, label) ->
            val sel = categoryFilter == cat
            ForgeActionPill(
                text = label,
                active = sel,
                accentColor = ForgeColors.AccentBlue,
                minHeight = 30.dp,
                fontSize = 11.5.sp,
                horizontalPadding = 10.dp,
                verticalPadding = 4.dp,
                onClick = {
                    categoryFilter = cat
                    val nextList = if (cat == null) orderedProviders else orderedProviders.filter { it.effectiveCategory() == cat }
                    if (nextList.isNotEmpty() && nextList.none { it.providerId == selectedProvId }) {
                        selectedProvId = nextList.first().providerId
                    }
                }
            )
        }
    }

    // 3. Provider Selector Chips (Built-in + User-created LAN & Custom — all visible via FlowRow)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        filteredProviders.forEach { cfg ->
            val isSelected = selectedProvId == cfg.providerId
            val isActiveInChat = activeProviderId == cfg.providerId
            val showDot = cfg.shouldShowStatusDot(activeProviderId)
            val health = providerHealth[cfg.providerId]
            val dotColor = when (health) {
                true -> ForgeColors.AccentEmerald
                false -> ForgeColors.DangerRed
                null -> ForgeColors.AccentAmber
            }
            FilterChip(
                selected = isSelected,
                onClick = { selectedProvId = cfg.providerId },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = if (isActiveInChat) dotColor.copy(alpha = 0.75f) else ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                    selectedBorderColor = ForgeColors.AccentBlue
                ),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ForgeColors.SurfaceCard,
                    labelColor = ForgeColors.TextPrimary,
                    selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.22f),
                    selectedLabelColor = ForgeColors.TextPrimary
                ),
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (showDot) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(dotColor, CircleShape)
                            )
                        }
                        Text(
                            text = cfg.resolvedDisplayName(context),
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected || isActiveInChat) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            )
        }
    }

    // 4. Selected Provider Summary Card + Active / Duplicate / Delete Controls
    val selectedHealth: Boolean? = when {
        providerHealth.containsKey(selectedProvId) -> providerHealth[selectedProvId]
        saved.shouldShowStatusDot(activeProviderId) -> null
        else -> false
    }
    val selectedStatusColor = when (selectedHealth) {
        true -> ForgeColors.AccentEmerald
        false -> ForgeColors.DangerRed
        null -> ForgeColors.AccentAmber
    }
    val selectedStatusLabelRes = when (selectedHealth) {
        true -> R.string.prov_status_online
        false -> R.string.prov_status_offline
        null -> R.string.prov_status_checking
    }

    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (selectedProvId == activeProviderId) selectedStatusColor.copy(alpha = 0.65f)
            else ForgeColors.BorderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = saved.resolvedDisplayName(context),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.TextPrimary
                    )
                    Text(
                        text = saved.resolvedSubtitle(context),
                        fontSize = 11.5.sp,
                        color = ForgeColors.TextSecondary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = selectedStatusColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, selectedStatusColor.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.probeProvidersHealth(selectedProvId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(selectedStatusColor, CircleShape)
                            )
                            Text(
                                text = stringResource(selectedStatusLabelRes),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = selectedStatusColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    val catAccent = when (effCategory) {
                        ProviderCategory.LOCAL_LAN -> ForgeColors.AccentEmerald
                        ProviderCategory.CUSTOM -> ForgeColors.AccentAmber
                        ProviderCategory.CLOUD -> ForgeColors.AccentBlue
                    }
                    Surface(
                        color = catAccent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, catAccent.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = effCategory.localizedLabel(context),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = catAccent,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (selectedProvId == activeProviderId) {
                    Surface(
                        color = ForgeColors.AccentEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ForgeColors.AccentEmerald.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = ForgeColors.AccentEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.prov_active_badge),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ForgeColors.AccentEmerald,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else {
                    ForgeActionPill(
                        text = stringResource(R.string.prov_set_active_btn),
                        icon = Icons.Rounded.CheckCircle,
                        accentColor = ForgeColors.AccentEmerald,
                        onClick = {
                            vm.saveProviderConfig(buildDraftConfig(), makeActive = true)
                        }
                    )
                }

                ForgeActionPill(
                    text = stringResource(R.string.prov_duplicate_btn),
                    icon = Icons.Rounded.ContentCopy,
                    accentColor = ForgeColors.AccentBlue,
                    onClick = {
                        val prefix = if (effCategory == ProviderCategory.LOCAL_LAN) "lan" else "custom"
                        val copyId = "${prefix}_${UUID.randomUUID().toString().take(8)}"
                        val baseName = saved.resolvedDisplayName(context)
                        val duplicated = buildDraftConfig().copy(
                            providerId = copyId,
                            customDisplayName = "$baseName (Copy)",
                            isUserCreated = true,
                            categoryOverride = if (effCategory == ProviderCategory.LOCAL_LAN) ProviderCategory.LOCAL_LAN else ProviderCategory.CUSTOM,
                            wireProtocolOverride = wireProto
                        )
                        vm.saveProviderConfig(duplicated, makeActive = false)
                        selectedProvId = copyId
                    }
                )

                if (orderedProviders.size > 1) {
                    ForgeActionPill(
                        text = stringResource(R.string.prov_delete_btn),
                        icon = Icons.Rounded.Delete,
                        accentColor = ForgeColors.DangerRed,
                        contentColor = ForgeColors.DangerRed,
                        borderColor = ForgeColors.DangerRed.copy(alpha = 0.6f),
                        onClick = { showDeleteProviderDialog = true }
                    )
                }
            }
        }
    }

    // 5. Connection Name (always shown for LAN / Custom / User-created)
    if (!isBuiltInCloud) {
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text(stringResource(R.string.prov_custom_name_label)) },
            placeholder = { Text(pType.localizedName(context)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 6. Wire Protocol & Auth Mode (directly visible for LAN & Custom; inside expandable Advanced for built-in Cloud)
    @Composable
    fun ProtocolAndAuthFields() {
        if (isBuiltInCloud) {
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text(stringResource(R.string.prov_custom_name_label)) },
                placeholder = { Text(pType.localizedName(context)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = stringResource(R.string.prov_wire_protocol_label),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = ForgeColors.TextPrimary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WireProtocol.entries.forEach { wp ->
                val sel = wireProto == wp
                FilterChip(
                    selected = sel,
                    onClick = {
                        wireProto = wp
                        hasProtoOverride = true
                    },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = sel,
                        borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                        selectedBorderColor = ForgeColors.AccentBlue
                    ),
                    label = { Text(wp.localizedLabel(context), fontSize = 11.sp) }
                )
            }
        }

        Text(
            text = stringResource(R.string.prov_auth_mode_label),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = ForgeColors.TextPrimary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ProviderAuthMode.entries.forEach { mode ->
                val sel = authMode == mode
                FilterChip(
                    selected = sel,
                    onClick = { authMode = mode },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = sel,
                        borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                        selectedBorderColor = ForgeColors.AccentBlue
                    ),
                    label = { Text(mode.localizedLabel(context), fontSize = 11.sp) }
                )
            }
        }

        if (authMode == ProviderAuthMode.CUSTOM_HEADER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customAuthHeader,
                    onValueChange = { customAuthHeader = it },
                    label = { Text(stringResource(R.string.prov_custom_header_name)) },
                    placeholder = { Text("api-key") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = customAuthPrefix,
                    onValueChange = { customAuthPrefix = it },
                    label = { Text(stringResource(R.string.prov_custom_header_prefix)) },
                    placeholder = { Text("Bearer ") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else if (authMode == ProviderAuthMode.QUERY_KEY) {
            OutlinedTextField(
                value = customQueryParam,
                onValueChange = { customQueryParam = it },
                label = { Text(stringResource(R.string.prov_custom_query_param)) },
                placeholder = { Text("key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (!isBuiltInCloud) {
        ProtocolAndAuthFields()
    }

    // 7. Base URL + LAN quick port presets + Raw Endpoint toggle
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text(if (rawEndpointUrl) "Endpoint URL (Exact)" else "Base URL") },
        placeholder = { Text(pType.defaultBaseUrl) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    if (effCategory == ProviderCategory.LOCAL_LAN) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val lanQuickUrls = listOf(
                "Ollama (Termux)" to ("http://127.0.0.1:11434" to WireProtocol.OLLAMA_NATIVE),
                "Ollama (Wi-Fi)" to ("http://192.168.1.100:11434" to WireProtocol.OLLAMA_NATIVE),
                "LM Studio :1234" to ("http://192.168.1.100:1234/v1" to WireProtocol.OPENAI_COMPAT),
                "llama.cpp :8080" to ("http://192.168.1.100:8080/v1" to WireProtocol.OPENAI_COMPAT),
                "KoboldCpp :5001" to ("http://192.168.1.100:5001/v1" to WireProtocol.OPENAI_COMPAT),
                "vLLM :8000" to ("http://192.168.1.100:8000/v1" to WireProtocol.OPENAI_COMPAT)
            )
            lanQuickUrls.forEach { (chipLabel, pair) ->
                val (presetUrl, presetProto) = pair
                FilterChip(
                    selected = baseUrl.trim().equals(presetUrl, ignoreCase = true),
                    onClick = {
                        baseUrl = presetUrl
                        wireProto = presetProto
                        hasProtoOverride = true
                    },
                    label = { Text(chipLabel, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                )
            }
        }
    }

    if (!isBuiltInCloud) {
        SwitchRow(
            title = stringResource(R.string.prov_raw_url_toggle),
            checked = rawEndpointUrl,
            subtitle = stringResource(R.string.prov_raw_url_sub),
            onCheckedChange = { rawEndpointUrl = it }
        )
    }

    // 8. API Keys (hidden when AuthMode == NONE so keyless LAN setups stay clean)
    val hideKeyBox = authMode == ProviderAuthMode.NONE ||
        (effCategory == ProviderCategory.LOCAL_LAN && authMode == ProviderAuthMode.AUTO && keysRaw.isBlank())
    if (!hideKeyBox) {
        OutlinedTextField(
            value = keysRaw,
            onValueChange = { keysRaw = it },
            label = { Text(stringResource(R.string.settings_api_keys_label)) },
            minLines = if (effCategory == ProviderCategory.LOCAL_LAN) 1 else 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
    } else if (effCategory == ProviderCategory.LOCAL_LAN && authMode == ProviderAuthMode.AUTO) {
        ForgeActionPill(
            text = "+ API Key / Bearer (опционально)",
            accentColor = ForgeColors.AccentBlue,
            onClick = { authMode = ProviderAuthMode.BEARER }
        )
    }

    // 9. Model ID + Live Scan + Saved Model Chips
    val triggerModelScan = {
        val draft = buildDraftConfig()
        isScanning = true
        vm.scanModelsForProvider(draft) {
            isScanning = false
            scanStatus = it
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            label = { Text("Model ID") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        ForgeActionPill(
            text = if (isScanning) stringResource(R.string.settings_scanning_short) else stringResource(R.string.settings_scan_short),
            icon = Icons.Rounded.Refresh,
            enabled = !isScanning,
            minHeight = 44.dp,
            onClick = triggerModelScan
        )
        ForgeActionPill(
            text = stringResource(R.string.prov_add_saved_model_btn),
            accentColor = ForgeColors.AccentEmerald,
            enabled = modelId.trim().isNotEmpty(),
            minHeight = 44.dp,
            onClick = {
                val trimmed = modelId.trim()
                if (trimmed.isNotEmpty() && savedModelsList.none { it.equals(trimmed, ignoreCase = true) }) {
                    savedModelsList = listOf(trimmed) + savedModelsList
                }
            }
        )
    }

    val mergedModels = remember(scannedModels[selectedProvId], savedModelsList) {
        ModelScanner.mergeSavedModels(
            scannedModels[selectedProvId].orEmpty(),
            saved.copy(savedModels = savedModelsList)
        )
    }
    val mergedCtxById = remember(mergedModels) {
        val map = HashMap<String, Int>(mergedModels.size * 2)
        for (m in mergedModels) {
            val ctx = m.contextLength
            if (ctx != null && ctx > 0) map[m.id] = ctx
        }
        map
    }

    if (savedModelsList.isNotEmpty()) {
        var showAllPinned by remember(selectedProvId) { mutableStateOf(false) }
        val visiblePinned = remember(savedModelsList, showAllPinned) {
            val distinctPinned = savedModelsList.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (showAllPinned) distinctPinned else distinctPinned.take(24)
        }
        Text(
            text = stringResource(R.string.prov_saved_models_label),
            fontSize = 11.5.sp,
            color = ForgeColors.TextSecondary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            visiblePinned.forEach { pinned ->
                val isCurrent = pinned == modelId.trim()
                Surface(
                    color = if (isCurrent) ForgeColors.AccentBlue.copy(alpha = 0.22f) else ForgeColors.SurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isCurrent) ForgeColors.AccentBlue else ForgeColors.BorderHighlight.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            modelId = pinned
                            val manual = modelCtxOverrides[pinned]
                            if (manual != null) {
                                contextWin = manual.toString()
                            } else if (autoContextWin) {
                                val scanned = mergedCtxById[pinned]
                                val inferred = scanned ?: ContextWindowResolver.inferContextWindow(
                                    modelId = pinned,
                                    providerType = pType,
                                    category = effCategory
                                )
                                contextWin = inferred.toString()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = pinned,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                            color = if (isCurrent) ForgeColors.AccentBlue else ForgeColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = ForgeColors.TextSecondary,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .clickable {
                                    savedModelsList = savedModelsList.filterNot { it == pinned }
                                }
                        )
                    }
                }
            }
            if (!showAllPinned && savedModelsList.size > visiblePinned.size) {
                ForgeActionPill(
                    text = "+${savedModelsList.size - visiblePinned.size}",
                    fontSize = 10.5.sp,
                    minHeight = 26.dp,
                    horizontalPadding = 8.dp,
                    verticalPadding = 3.dp,
                    onClick = { showAllPinned = true }
                )
            }
        }
    }

    scanStatus?.let { msg ->
        val isErr = msg.startsWith("Ошибка", ignoreCase = true) || msg.startsWith("Error", ignoreCase = true)
        Text(
            msg,
            fontSize = 11.5.sp,
            color = if (isErr) ForgeColors.DangerRed else ForgeColors.AccentEmerald
        )
    }

    ModelSelectorButton(
        models = mergedModels,
        currentModelId = modelId,
        isScanning = isScanning,
        onScanIfNeeded = triggerModelScan,
        onSelectModel = { selectedId ->
            modelId = selectedId
            val manual = modelCtxOverrides[selectedId]
            if (manual != null) {
                contextWin = manual.toString()
            } else if (autoContextWin) {
                val scanned = mergedCtxById[selectedId]
                val inferred = scanned ?: ContextWindowResolver.inferContextWindow(
                    modelId = selectedId,
                    providerType = pType,
                    category = effCategory
                )
                contextWin = inferred.toString()
            }
        }
    )

    // 10. Context Window (Auto-detect by Model + Quick Presets + Manual Input) & Max Output Tokens
    val activeCleanModel = modelId.trim().ifBlank { pType.defaultModel }
    val isCurrentModelAutoCtx = autoContextWin && !modelCtxOverrides.containsKey(activeCleanModel)
    SwitchRow(
        title = stringResource(R.string.ctx_window_auto_toggle),
        checked = isCurrentModelAutoCtx,
        onCheckedChange = { enabled ->
            if (enabled) {
                autoContextWin = true
                modelCtxOverrides = modelCtxOverrides - activeCleanModel
                val scanned = mergedCtxById[activeCleanModel]
                val inferred = scanned ?: ContextWindowResolver.inferContextWindow(
                    modelId = activeCleanModel,
                    providerType = pType,
                    category = effCategory
                )
                contextWin = inferred.toString()
            } else {
                val curTokens = (contextWin.toIntOrNull() ?: pType.defaultContextWindow).coerceAtLeast(1024)
                modelCtxOverrides = modelCtxOverrides + (activeCleanModel to curTokens)
            }
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = isCurrentModelAutoCtx,
            onClick = {
                autoContextWin = true
                modelCtxOverrides = modelCtxOverrides - activeCleanModel
                val scanned = mergedCtxById[activeCleanModel]
                val inferred = scanned ?: ContextWindowResolver.inferContextWindow(
                    modelId = activeCleanModel,
                    providerType = pType,
                    category = effCategory
                )
                contextWin = inferred.toString()
            },
            label = { Text(stringResource(R.string.ctx_window_auto), fontSize = 11.sp) }
        )
        listOf(8_192, 16_384, 32_768, 65_536, 131_072, 262_144, 1_048_576).forEach { preset ->
            val curVal = contextWin.toIntOrNull()
            val selectedPreset = !isCurrentModelAutoCtx && curVal == preset
            FilterChip(
                selected = selectedPreset,
                onClick = {
                    contextWin = preset.toString()
                    modelCtxOverrides = modelCtxOverrides + (activeCleanModel to preset)
                },
                label = { Text(ContextWindowResolver.formatTokensShort(preset), fontSize = 11.sp) }
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = contextWin,
            onValueChange = { raw ->
                val digits = raw.filter { c -> c.isDigit() }
                contextWin = digits
                val parsed = digits.toIntOrNull()
                if (parsed != null && parsed >= 1024) {
                    modelCtxOverrides = modelCtxOverrides + (activeCleanModel to parsed)
                }
            },
            label = { Text(stringResource(R.string.settings_context_window)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = maxTokensText,
            onValueChange = { maxTokensText = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.prov_max_tokens_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }

    // 11. Advanced Custom External / Protocol / JSON / SSL Section
    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = ForgeColors.AccentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.prov_advanced_toggle),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TextPrimary
                    )
                }
                Icon(
                    if (showAdvanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = ForgeColors.TextSecondary
                )
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = ForgeColors.BorderSubtle)
                    if (isBuiltInCloud) {
                        ProtocolAndAuthFields()
                        SwitchRow(
                            title = stringResource(R.string.prov_raw_url_toggle),
                            checked = rawEndpointUrl,
                            subtitle = stringResource(R.string.prov_raw_url_sub),
                            onCheckedChange = { rawEndpointUrl = it }
                        )
                    }

                    OutlinedTextField(
                        value = modelsEndpointUrl,
                        onValueChange = { modelsEndpointUrl = it },
                        label = { Text(stringResource(R.string.prov_models_url_label)) },
                        placeholder = { Text("https://api.example.com/v1/models") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = extraBodyJson,
                        onValueChange = { extraBodyJson = it },
                        label = { Text(stringResource(R.string.prov_extra_body_label)) },
                        placeholder = { Text("{\"chat_template_kwargs\":{\"enable_thinking\":false}}") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val extraSnippets = listOf(
                            "Clear {}" to "{}",
                            "Qwen no-think" to "{\"chat_template_kwargs\":{\"enable_thinking\":false}}",
                            "OpenRouter fallbacks" to "{\"provider\":{\"allow_fallbacks\":true}}",
                            "Reasoning high" to "{\"reasoning_effort\":\"high\"}"
                        )
                        extraSnippets.forEach { (label, snippet) ->
                            ForgeActionPill(
                                text = label,
                                fontSize = 10.5.sp,
                                minHeight = 28.dp,
                                horizontalPadding = 8.dp,
                                verticalPadding = 3.dp,
                                onClick = { extraBodyJson = snippet }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = headersJson,
                        onValueChange = { headersJson = it },
                        label = { Text(stringResource(R.string.settings_custom_headers_json)) },
                        placeholder = { Text("{\"HTTP-Referer\":\"https://forgemind.local\"}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = timeoutSecText,
                        onValueChange = { timeoutSecText = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.prov_timeout_sec_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SwitchRow(
                        title = stringResource(R.string.prov_insecure_ssl_toggle),
                        checked = allowInsecureSsl,
                        onCheckedChange = { allowInsecureSsl = it }
                    )
                }
            }
        }
    }

    Text(
        stringResource(R.string.settings_temperature_fmt, String.format(java.util.Locale.US, "%.2f", temperature)),
        fontSize = 12.5.sp,
        color = ForgeColors.TextPrimary
    )
    Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f)

    SwitchRow(stringResource(R.string.settings_enable_thinking), enableThinking) { enableThinking = it }
    if (!hideKeyBox) {
        SwitchRow(stringResource(R.string.settings_auto_rotate_keys), autoRotate) { autoRotate = it }
    }

    // 12. Live Connection Test + Save / Save & Activate Buttons
    OutlinedButton(
        enabled = !isTestingConn,
        border = BorderStroke(1.dp, ForgeColors.AccentAmber.copy(alpha = 0.75f)),
        onClick = {
            val draft = buildDraftConfig()
            isTestingConn = true
            connTestResult = null
            vm.testProviderConnection(draft) { res ->
                isTestingConn = false
                connTestResult = res
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isTestingConn) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = ForgeColors.AccentAmber)
        } else {
            Icon(Icons.Rounded.Bolt, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(17.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isTestingConn) stringResource(R.string.prov_testing_connection) else stringResource(R.string.prov_test_connection_btn),
            color = ForgeColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }

    connTestResult?.let { res ->
        val accent = if (res.success) ForgeColors.AccentEmerald else ForgeColors.DangerRed
        Surface(
            color = accent.copy(alpha = 0.14f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (res.success) {
                    stringResource(
                        R.string.prov_test_ok_fmt,
                        res.latencyMs,
                        res.replyPreview.ifBlank { "OK" }
                    )
                } else {
                    stringResource(R.string.prov_test_err_fmt, res.errorMessage)
                },
                fontSize = 12.sp,
                color = ForgeColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                vm.saveProviderConfig(buildDraftConfig(), makeActive = true)
            },
            modifier = Modifier.weight(1.35f)
        ) {
            Text(
                stringResource(R.string.prov_save_and_activate),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            border = BorderStroke(1.dp, ForgeColors.BorderHighlight),
            onClick = {
                vm.saveProviderConfig(buildDraftConfig(), makeActive = false)
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(
                stringResource(R.string.action_save),
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    // Dialog A: 1-Tap Local Network AI Scanner
    if (showLanScannerDialog) {
        LanAiScannerDialog(
            vm = vm,
            onDismiss = { showLanScannerDialog = false },
            onConnected = { createdCfg ->
                categoryFilter = null
                selectedProvId = createdCfg.providerId
                showLanScannerDialog = false
            }
        )
    }

    // Dialog B: Add Local LAN or Custom External Provider Wizard
    createDialogCategory?.let { targetCat ->
        AddCustomOrLanProviderDialog(
            category = targetCat,
            onDismiss = { createDialogCategory = null },
            onCreate = { createdCfg, makeActive ->
                vm.saveProviderConfig(createdCfg, makeActive = makeActive)
                categoryFilter = null
                selectedProvId = createdCfg.providerId
                createDialogCategory = null
            }
        )
    }

    // Dialog C: Confirm Deleting Any Provider (Built-in or Custom/LAN)
    if (showDeleteProviderDialog) {
        val providerDisplayName = saved.resolvedDisplayName(context)
        AlertDialog(
            onDismissRequest = { showDeleteProviderDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.prov_delete_confirm_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.prov_delete_confirm_body_fmt, providerDisplayName),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = ForgeColors.TextPrimary
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProviderDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForgeColors.DangerRed,
                        contentColor = Color.White
                    ),
                    onClick = {
                        val idToDelete = selectedProvId
                        val nextId = orderedProviders.firstOrNull { it.providerId != idToDelete }?.providerId
                        vm.removeProvider(idToDelete)
                        if (nextId != null) {
                            selectedProvId = nextId
                        }
                        showDeleteProviderDialog = false
                    }
                ) {
                    Text(stringResource(R.string.prov_delete_btn))
                }
            }
        )
    }

    // Dialog D: Reset Default Built-in AI Providers List with Optional Wipe Keys Checkbox
    if (showResetDefaultsDialog) {
        AlertDialog(
            onDismissRequest = { showResetDefaultsDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.prov_reset_confirm_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.prov_reset_confirm_body),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = ForgeColors.TextPrimary
                    )
                    Surface(
                        color = ForgeColors.SurfaceCard,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { wipeKeysChecked = !wipeKeysChecked }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Checkbox(
                                checked = wipeKeysChecked,
                                onCheckedChange = { wipeKeysChecked = it }
                            )
                            Text(
                                text = stringResource(R.string.prov_reset_wipe_keys_label),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = if (wipeKeysChecked) ForgeColors.DangerRed else ForgeColors.TextPrimary
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDefaultsDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.resetBuiltInProviders(clearApiKeys = wipeKeysChecked)
                        showResetDefaultsDialog = false
                    }
                ) {
                    Text(stringResource(R.string.prov_reset_confirm_btn))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanAiScannerDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onConnected: (ProviderConfig) -> Unit
) {
    var customHost by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(true) }
    var probingSpecific by remember { mutableStateOf(false) }
    var connectingDirect by remember { mutableStateOf(false) }
    var requestSeq by remember { mutableStateOf(0) }
    var discovered by remember { mutableStateOf<List<DiscoveredLanAiServer>>(emptyList()) }

    fun triggerFullScan() {
        val seq = ++requestSeq
        probingSpecific = false
        scanning = true
        vm.scanLanAiServers(customHost) { found ->
            if (seq == requestSeq) {
                discovered = found
                scanning = false
            }
        }
    }

    fun triggerProbeSpecificHost() {
        val target = customHost.trim()
        if (target.isEmpty()) {
            triggerFullScan()
            return
        }
        val seq = ++requestSeq
        scanning = false
        probingSpecific = true
        vm.probeSpecificLanHost(target) { found ->
            if (seq == requestSeq) {
                discovered = found
                probingSpecific = false
            }
        }
    }

    fun triggerDirectConnect() {
        val target = customHost.trim()
        if (target.isEmpty() || connectingDirect) return
        val seq = ++requestSeq
        scanning = false
        probingSpecific = false
        connectingDirect = true
        vm.connectDirectLanInput(target) { cfg ->
            if (seq == requestSeq) {
                connectingDirect = false
                if (cfg != null) {
                    onConnected(cfg)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        triggerFullScan()
    }

    val hasHostInput = customHost.trim().isNotEmpty()
    val busy = scanning || probingSpecific || connectingDirect

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.lan_scanner_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.lan_scanner_desc),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = ForgeColors.TextSecondary
                )

                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = { Text(stringResource(R.string.lan_scanner_host_hint), fontSize = 11.5.sp, maxLines = 1) },
                    placeholder = { Text("192.168.1.50:1234 / 100.64.0.2", fontSize = 12.sp) },
                    trailingIcon = {
                        if (customHost.isNotEmpty()) {
                            IconButton(onClick = { customHost = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { triggerProbeSpecificHost() }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ForgeActionPill(
                        text = stringResource(R.string.lan_scanner_probe_host_btn),
                        icon = Icons.Rounded.Search,
                        accentColor = ForgeColors.AccentCyan,
                        active = hasHostInput && !probingSpecific,
                        enabled = hasHostInput && !probingSpecific && !connectingDirect,
                        minHeight = 38.dp,
                        modifier = Modifier.weight(1f),
                        onClick = { triggerProbeSpecificHost() }
                    )
                    ForgeActionPill(
                        text = stringResource(R.string.lan_scanner_connect_direct_btn),
                        icon = Icons.Rounded.Bolt,
                        accentColor = ForgeColors.AccentAmber,
                        active = hasHostInput && !connectingDirect,
                        enabled = hasHostInput && !connectingDirect,
                        minHeight = 38.dp,
                        modifier = Modifier.weight(1f),
                        onClick = { triggerDirectConnect() }
                    )
                }

                ForgeActionPill(
                    text = stringResource(R.string.lan_scanner_scan_subnet_btn),
                    icon = Icons.Rounded.Refresh,
                    accentColor = ForgeColors.AccentEmerald,
                    active = !busy,
                    enabled = !scanning && !connectingDirect,
                    minHeight = 38.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { triggerFullScan() }
                )

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = if (probingSpecific || connectingDirect) {
                                stringResource(R.string.lan_scanner_probing_host)
                            } else {
                                stringResource(R.string.lan_scanner_scanning)
                            },
                            fontSize = 12.5.sp,
                            color = ForgeColors.AccentCyan
                        )
                    }
                } else if (discovered.isEmpty()) {
                    Surface(
                        color = ForgeColors.SurfaceCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.lan_scanner_empty),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = ForgeColors.TextSecondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.lan_scanner_found_fmt, discovered.size),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.AccentEmerald
                    )
                    discovered.forEach { srv ->
                        Surface(
                            color = ForgeColors.SurfaceCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ForgeColors.AccentEmerald.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = srv.engineLabel,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ForgeColors.TextPrimary
                                        )
                                        Text(
                                            text = "${srv.baseUrl} • ${srv.latencyMs} ms",
                                            fontFamily = MonoFont,
                                            fontSize = 11.sp,
                                            color = ForgeColors.AccentCyan
                                        )
                                    }
                                    ForgeActionPill(
                                        text = stringResource(R.string.lan_scanner_connect_btn),
                                        accentColor = ForgeColors.AccentEmerald,
                                        active = true,
                                        onClick = {
                                            val cfg = vm.connectDiscoveredLanServer(srv)
                                            onConnected(cfg)
                                        }
                                    )
                                }
                                if (srv.models.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        srv.models.take(8).forEach { mName ->
                                            val ctxBadge = srv.modelContextLengths[mName]
                                                ?.takeIf { it >= 1024 }
                                                ?.let { " · ${ContextWindowResolver.formatTokensShort(it)}" }
                                                .orEmpty()
                                            ForgeActionPill(
                                                text = "$mName$ctxBadge",
                                                fontSize = 10.5.sp,
                                                minHeight = 26.dp,
                                                horizontalPadding = 8.dp,
                                                verticalPadding = 3.dp,
                                                onClick = {
                                                    val cfg = vm.connectDiscoveredLanServer(srv, selectedModel = mName)
                                                    onConnected(cfg)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCustomOrLanProviderDialog(
    category: ProviderCategory,
    onDismiss: () -> Unit,
    onCreate: (ProviderConfig, Boolean) -> Unit
) {
    val context = LocalContext.current
    val isLan = category == ProviderCategory.LOCAL_LAN

    val presets = remember(isLan) {
        if (isLan) {
            listOf(
                NewProviderPreset(
                    chipLabel = "Ollama (Wi-Fi :11434)",
                    defaultName = "Ollama LAN",
                    baseUrl = "http://192.168.1.100:11434",
                    modelId = "qwen2.5-coder:7b",
                    wireProtocol = WireProtocol.OLLAMA_NATIVE,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 32_768
                ),
                NewProviderPreset(
                    chipLabel = "Ollama (Termux 127.0.0.1)",
                    defaultName = "Ollama Termux",
                    baseUrl = "http://127.0.0.1:11434",
                    modelId = "qwen2.5-coder:7b",
                    wireProtocol = WireProtocol.OLLAMA_NATIVE,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 32_768
                ),
                NewProviderPreset(
                    chipLabel = "LM Studio (:1234)",
                    defaultName = "LM Studio PC",
                    baseUrl = "http://192.168.1.100:1234/v1",
                    modelId = "local-model",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 32_768
                ),
                NewProviderPreset(
                    chipLabel = "llama.cpp (:8080)",
                    defaultName = "llama.cpp Server",
                    baseUrl = "http://192.168.1.100:8080/v1",
                    modelId = "local-model",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 32_768
                ),
                NewProviderPreset(
                    chipLabel = "KoboldCpp (:5001)",
                    defaultName = "KoboldCpp LAN",
                    baseUrl = "http://192.168.1.100:5001/v1",
                    modelId = "koboldcpp",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 32_768
                ),
                NewProviderPreset(
                    chipLabel = "vLLM / SGLang (:8000)",
                    defaultName = "vLLM GPU Server",
                    baseUrl = "http://192.168.1.100:8000/v1",
                    modelId = "local-model",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 65_536
                ),
                NewProviderPreset(
                    chipLabel = "Jan (:1337)",
                    defaultName = "Jan Local AI",
                    baseUrl = "http://192.168.1.100:1337/v1",
                    modelId = "local-model",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.NONE,
                    contextWindow = 32_768
                )
            )
        } else {
            listOf(
                NewProviderPreset(
                    chipLabel = "OpenAI-Compatible (/v1)",
                    defaultName = "Custom OpenAI API",
                    baseUrl = "https://api.example.com/v1",
                    modelId = "gpt-4o",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.BEARER,
                    contextWindow = 128_000
                ),
                NewProviderPreset(
                    chipLabel = "OpenAI Responses (/v1/responses)",
                    defaultName = "Custom Responses API",
                    baseUrl = "https://api.example.com/v1",
                    modelId = "gpt-5.4",
                    wireProtocol = WireProtocol.OPENAI_RESPONSES,
                    authMode = ProviderAuthMode.BEARER,
                    contextWindow = 200_000
                ),
                NewProviderPreset(
                    chipLabel = "Anthropic Proxy (/v1/messages)",
                    defaultName = "Custom Claude Gateway",
                    baseUrl = "https://api.example.com",
                    modelId = "claude-sonnet-4-6",
                    wireProtocol = WireProtocol.ANTHROPIC,
                    authMode = ProviderAuthMode.X_API_KEY,
                    contextWindow = 200_000
                ),
                NewProviderPreset(
                    chipLabel = "Gemini Gateway (/v1beta)",
                    defaultName = "Custom Gemini Proxy",
                    baseUrl = "https://api.example.com",
                    modelId = "gemini-2.5-pro",
                    wireProtocol = WireProtocol.GEMINI,
                    authMode = ProviderAuthMode.QUERY_KEY,
                    contextWindow = 1_000_000
                ),
                NewProviderPreset(
                    chipLabel = "Azure / Exact Endpoint URL",
                    defaultName = "Azure / Raw Endpoint",
                    baseUrl = "https://YOUR-RESOURCE.openai.azure.com/openai/deployments/MODEL/chat/completions?api-version=2024-10-21",
                    modelId = "gpt-4o",
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    authMode = ProviderAuthMode.CUSTOM_HEADER,
                    contextWindow = 128_000,
                    rawEndpointUrl = true,
                    customAuthHeader = "api-key"
                )
            )
        }
    }

    val initial = presets.first()
    var name by remember(isLan) { mutableStateOf(initial.defaultName) }
    var baseUrl by remember(isLan) { mutableStateOf(initial.baseUrl) }
    var modelId by remember(isLan) { mutableStateOf(initial.modelId) }
    var wireProto by remember(isLan) { mutableStateOf(initial.wireProtocol) }
    var authMode by remember(isLan) { mutableStateOf(initial.authMode) }
    var apiKey by remember(isLan) { mutableStateOf("") }
    var rawEndpoint by remember(isLan) { mutableStateOf(initial.rawEndpointUrl) }
    var customHeader by remember(isLan) { mutableStateOf(initial.customAuthHeader) }
    var contextWindow by remember(isLan) { mutableStateOf(initial.contextWindow.toString()) }

    fun buildCreatedConfig(): ProviderConfig {
        val prefix = if (isLan) "lan" else "custom"
        val id = "${prefix}_${UUID.randomUUID().toString().take(8)}"
        val trimmedModel = modelId.trim().ifBlank { "default" }
        return ProviderConfig(
            providerId = id,
            customDisplayName = name.trim().ifBlank { initial.defaultName },
            baseUrl = baseUrl.trim().ifBlank { initial.baseUrl },
            modelId = trimmedModel,
            apiKeysRaw = apiKey.trim(),
            contextWindow = (contextWindow.toIntOrNull() ?: initial.contextWindow).coerceAtLeast(1024),
            isUserCreated = true,
            categoryOverride = category,
            wireProtocolOverride = wireProto,
            authMode = authMode,
            customAuthHeader = customHeader.trim().ifBlank { "X-API-Key" },
            rawEndpointUrl = rawEndpoint,
            savedModels = listOf(trimmedModel)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (isLan) R.string.add_prov_lan_title else R.string.add_prov_custom_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.add_prov_preset_label),
                    fontSize = 11.5.sp,
                    color = ForgeColors.TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { p ->
                        FilterChip(
                            selected = baseUrl == p.baseUrl && wireProto == p.wireProtocol,
                            onClick = {
                                name = p.defaultName
                                baseUrl = p.baseUrl
                                modelId = p.modelId
                                wireProto = p.wireProtocol
                                authMode = p.authMode
                                rawEndpoint = p.rawEndpointUrl
                                customHeader = p.customAuthHeader
                                contextWindow = p.contextWindow.toString()
                            },
                            label = { Text(p.chipLabel, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.prov_custom_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(if (rawEndpoint) "Endpoint URL (Exact)" else "Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    stringResource(R.string.prov_wire_protocol_label),
                    fontSize = 11.5.sp,
                    color = ForgeColors.TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WireProtocol.entries.forEach { wp ->
                        FilterChip(
                            selected = wireProto == wp,
                            onClick = { wireProto = wp },
                            label = { Text(wp.localizedLabel(context), fontSize = 10.5.sp) }
                        )
                    }
                }

                Text(
                    stringResource(R.string.prov_auth_mode_label),
                    fontSize = 11.5.sp,
                    color = ForgeColors.TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProviderAuthMode.entries.forEach { m ->
                        FilterChip(
                            selected = authMode == m,
                            onClick = { authMode = m },
                            label = { Text(m.localizedLabel(context), fontSize = 10.5.sp) }
                        )
                    }
                }

                if (authMode == ProviderAuthMode.CUSTOM_HEADER) {
                    OutlinedTextField(
                        value = customHeader,
                        onValueChange = { customHeader = it },
                        label = { Text(stringResource(R.string.prov_custom_header_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (authMode != ProviderAuthMode.NONE) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.settings_api_keys_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SwitchRow(
                    title = stringResource(R.string.prov_raw_url_toggle),
                    checked = rawEndpoint,
                    onCheckedChange = { rawEndpoint = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(buildCreatedConfig(), true) }
            ) {
                Text(stringResource(R.string.prov_save_and_activate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettingsPane(vm: MainViewModel) {
    val context = LocalContext.current
    val saved by vm.voiceSettings.collectAsState()
    val voices by vm.xttsVoices.collectAsState()
    val defaultVoice by vm.xttsDefaultVoice.collectAsState()
    val edgeVoices by vm.edgeTtsVoices.collectAsState()

    var sttProvider by remember(saved) { mutableStateOf(saved.sttProvider) }
    var sttBaseUrl by remember(saved) { mutableStateOf(saved.sttBaseUrl) }
    var sttApiKey by remember(saved) { mutableStateOf(saved.sttApiKey) }
    var sttModel by remember(saved) { mutableStateOf(saved.sttModel) }
    var sttLanguage by remember(saved) { mutableStateOf(saved.sttLanguage) }
    var autoSendVoice by remember(saved) { mutableStateOf(saved.autoSendAfterVoice) }
    var sttFallback by remember(saved) { mutableStateOf(saved.sttAutoFallback) }

    var ttsProvider by remember(saved) { mutableStateOf(saved.ttsProvider) }
    var edgeTtsVoice by remember(saved) { mutableStateOf(saved.edgeTtsVoice.ifBlank { "auto" }) }
    var edgeTtsPitchHz by remember(saved) { mutableFloatStateOf(saved.edgeTtsPitchHz.toFloat()) }
    var edgeVoicesStatus by remember { mutableStateOf<String?>(null) }
    var showEdgeVoicePicker by remember { mutableStateOf(false) }
    var edgeVoiceQuery by remember { mutableStateOf("") }

    var xttsBaseUrl by remember(saved) { mutableStateOf(saved.xttsBaseUrl) }
    var xttsVoice by remember(saved) {
        mutableStateOf(
            saved.xttsVoice.takeUnless { it.equals("__default__", ignoreCase = true) } ?: ""
        )
    }
    var xttsExternalUrl by remember(saved) { mutableStateOf(saved.xttsExternalUrl) }
    var xttsExternalVoice by remember(saved) {
        mutableStateOf(
            saved.xttsExternalVoice.takeUnless { it.equals("__default__", ignoreCase = true) } ?: ""
        )
    }
    var xttsPitch by remember(saved) { mutableFloatStateOf(saved.xttsPitch) }
    var xttsKeepTempo by remember(saved) { mutableStateOf(saved.xttsKeepTempo) }
    var ttsSpeed by remember(saved) { mutableFloatStateOf(saved.ttsSpeed) }
    var xttsCfId by remember(saved) { mutableStateOf(saved.xttsCfClientId) }
    var xttsCfSecret by remember(saved) { mutableStateOf(saved.xttsCfClientSecret) }
    var xttsBearer by remember(saved) { mutableStateOf(saved.xttsBearerToken) }
    var xttsExternalBearer by remember(saved) { mutableStateOf(saved.xttsExternalBearerToken) }
    var openAiTtsUrl by remember(saved) { mutableStateOf(saved.openAiTtsUrl) }
    var openAiTtsKey by remember(saved) { mutableStateOf(saved.openAiTtsKey) }
    var openAiTtsModel by remember(saved) { mutableStateOf(saved.openAiTtsModel) }
    var openAiTtsVoice by remember(saved) { mutableStateOf(saved.openAiTtsVoice) }
    var autoSpeak by remember(saved) { mutableStateOf(saved.autoSpeakReplies) }
    var skipCode by remember(saved) { mutableStateOf(saved.skipCodeInTts) }
    var xttsTestStatus by remember { mutableStateOf<String?>(null) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var voiceQuery by remember { mutableStateOf("") }

    fun currentDraft() = saved.copy(
        sttProvider = sttProvider,
        sttBaseUrl = sttBaseUrl,
        sttApiKey = sttApiKey,
        sttModel = sttModel,
        sttLanguage = sttLanguage,
        autoSendAfterVoice = autoSendVoice,
        sttAutoFallback = sttFallback,
        ttsProvider = ttsProvider,
        edgeTtsVoice = edgeTtsVoice.ifBlank { "auto" },
        edgeTtsPitchHz = kotlin.math.round(edgeTtsPitchHz).toInt().coerceIn(-50, 50),
        xttsBaseUrl = xttsBaseUrl,
        xttsVoice = xttsVoice,
        xttsExternalUrl = xttsExternalUrl,
        xttsExternalVoice = xttsExternalVoice,
        xttsPitch = xttsPitch,
        xttsKeepTempo = xttsKeepTempo,
        ttsSpeed = ttsSpeed,
        xttsCfClientId = xttsCfId,
        xttsCfClientSecret = xttsCfSecret,
        xttsBearerToken = xttsBearer,
        xttsExternalBearerToken = xttsExternalBearer,
        openAiTtsUrl = openAiTtsUrl,
        openAiTtsKey = openAiTtsKey,
        openAiTtsModel = openAiTtsModel,
        openAiTtsVoice = openAiTtsVoice,
        autoSpeakReplies = autoSpeak,
        skipCodeInTts = skipCode
    )

    SectionQuickGuideCard(
        title = stringResource(R.string.guide_voice_title),
        subtitle = stringResource(R.string.guide_voice_subtitle),
        body = stringResource(R.string.guide_voice_body)
    )

    Text(stringResource(R.string.settings_stt_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentBlue)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SttProviderType.entries.forEach { s ->
            FilterChip(
                selected = sttProvider == s,
                onClick = { sttProvider = s },
                label = { Text(s.localizedLabel(context).substringBefore(" ("), fontSize = 11.5.sp) }
            )
        }
    }

    if (sttProvider != SttProviderType.ANDROID_SPEECH) {
        OutlinedTextField(
            value = sttApiKey,
            onValueChange = { sttApiKey = it },
            label = { Text(stringResource(R.string.settings_stt_api_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = sttBaseUrl,
            onValueChange = { sttBaseUrl = it },
            label = { Text("STT Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sttModel,
                onValueChange = { sttModel = it },
                label = { Text(stringResource(R.string.settings_stt_model)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = sttLanguage,
                onValueChange = { sttLanguage = it },
                label = { Text(stringResource(R.string.settings_language_label)) },
                singleLine = true,
                modifier = Modifier.width(86.dp)
            )
        }
    }
    SwitchRow(stringResource(R.string.settings_auto_send_voice), autoSendVoice) { autoSendVoice = it }
    SwitchRow(
        stringResource(R.string.settings_stt_fallback),
        sttFallback
    ) { sttFallback = it }

    HorizontalDivider(color = ForgeColors.BorderSubtle)

    Text(stringResource(R.string.settings_tts_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentEmerald)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TtsProviderType.entries.forEach { t ->
            FilterChip(
                selected = ttsProvider == t,
                onClick = {
                    ttsProvider = t
                    xttsTestStatus = null
                },
                label = { Text(t.localizedLabel(context).substringBefore(" ("), fontSize = 11.5.sp) }
            )
        }
    }

    val ttsSamplePhrase = stringResource(R.string.settings_tts_sample_phrase)

    if (ttsProvider == TtsProviderType.MICROSOFT_EDGE) {
        Surface(
            color = ForgeColors.AccentEmerald.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ForgeColors.AccentEmerald.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.edge_tts_free_info),
                fontSize = 12.sp,
                color = ForgeColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }

        val quickPresets = remember {
            listOf(
                "auto" to "✨ Auto (RU/EN)",
                "ru-RU-SvetlanaNeural" to "🇷🇺 Светлана",
                "ru-RU-DmitryNeural" to "🇷🇺 Дмитрий",
                "en-US-AvaMultilingualNeural" to "🌐 Ava Multi",
                "en-US-AndrewMultilingualNeural" to "🌐 Andrew Multi",
                "uk-UA-PolinaNeural" to "🇺🇦 Поліна"
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickPresets.forEach { (voiceId, chipLabel) ->
                FilterChip(
                    selected = edgeTtsVoice.equals(voiceId, ignoreCase = true),
                    onClick = { edgeTtsVoice = voiceId },
                    label = { Text(chipLabel, fontSize = 11.5.sp) }
                )
            }
        }

        OutlinedTextField(
            value = edgeTtsVoice,
            onValueChange = { edgeTtsVoice = it },
            label = { Text(stringResource(R.string.edge_tts_voice_label)) },
            placeholder = { Text("auto / ru-RU-SvetlanaNeural") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { showEdgeVoicePicker = true }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_pick_voice_desc))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ForgeActionPill(
                text = stringResource(R.string.edge_tts_load_all_btn, edgeVoices.size),
                icon = Icons.Rounded.Refresh,
                onClick = {
                    vm.loadEdgeTtsVoices(forceRefresh = true) { msg ->
                        edgeVoicesStatus = msg
                        showEdgeVoicePicker = true
                    }
                }
            )
            ForgeActionPill(
                text = stringResource(R.string.action_test),
                icon = Icons.Rounded.PlayArrow,
                accentColor = ForgeColors.AccentEmerald,
                onClick = {
                    val draft = currentDraft()
                    vm.saveVoiceSettings(draft)
                    vm.speakMessage("test_edge_tts", ttsSamplePhrase)
                }
            )
        }

        edgeVoicesStatus?.let { st ->
            Text(
                text = st,
                fontSize = 12.sp,
                color = if (st.contains("err", ignoreCase = true) || st.contains("ошиб", ignoreCase = true)) {
                    ForgeColors.DangerRed
                } else {
                    ForgeColors.AccentEmerald
                }
            )
        }

        Text(
            stringResource(R.string.edge_tts_pitch_fmt, kotlin.math.round(edgeTtsPitchHz).toInt()),
            fontSize = 12.5.sp,
            color = ForgeColors.TextPrimary
        )
        Slider(
            value = edgeTtsPitchHz,
            onValueChange = { edgeTtsPitchHz = kotlin.math.round(it) },
            valueRange = -30f..30f
        )

        if (showEdgeVoicePicker) {
            val filteredEdgeVoices = remember(edgeVoices, edgeVoiceQuery) {
                val q = edgeVoiceQuery.trim()
                if (q.isBlank()) {
                    edgeVoices
                } else {
                    edgeVoices.filter { v ->
                        v.shortName.contains(q, ignoreCase = true) ||
                            v.friendlyName.contains(q, ignoreCase = true) ||
                            v.locale.contains(q, ignoreCase = true)
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { showEdgeVoicePicker = false },
                title = { Text(stringResource(R.string.edge_tts_picker_title, filteredEdgeVoices.size)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = edgeVoiceQuery,
                            onValueChange = { edgeVoiceQuery = it },
                            placeholder = { Text(stringResource(R.string.settings_search_voice)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(filteredEdgeVoices, key = { idx, v -> "$idx:${v.shortName}" }) { _, v ->
                                val isSelected = v.shortName.equals(edgeTtsVoice, ignoreCase = true)
                                Surface(
                                    color = if (isSelected) ForgeColors.AccentEmerald.copy(alpha = 0.2f) else ForgeColors.SurfaceCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (isSelected) BorderStroke(1.dp, ForgeColors.AccentEmerald) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            edgeTtsVoice = v.shortName
                                            showEdgeVoicePicker = false
                                            vm.saveVoiceSettings(currentDraft().copy(edgeTtsVoice = v.shortName))
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = v.displayLabel(),
                                            color = ForgeColors.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (v.shortName != "auto") {
                                            Text(
                                                text = "${v.locale} • ${v.friendlyName}",
                                                color = ForgeColors.TextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                vm.loadEdgeTtsVoices(forceRefresh = true) { msg ->
                                    edgeVoicesStatus = msg
                                }
                            }
                        ) {
                            Text(stringResource(R.string.edge_tts_refresh_short))
                        }
                        TextButton(onClick = { showEdgeVoicePicker = false }) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            )
        }
    }

    if (ttsProvider == TtsProviderType.OPENAI_TTS) {
        OutlinedTextField(
            value = openAiTtsUrl,
            onValueChange = { openAiTtsUrl = it },
            label = { Text("OpenAI TTS Endpoint URL") },
            placeholder = { Text("https://api.openai.com/v1/audio/speech") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = openAiTtsKey,
            onValueChange = { openAiTtsKey = it },
            label = { Text("OpenAI TTS API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = openAiTtsModel,
                onValueChange = { openAiTtsModel = it },
                label = { Text("Model") },
                placeholder = { Text("gpt-4o-mini-tts") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = openAiTtsVoice,
                onValueChange = { openAiTtsVoice = it },
                label = { Text("Voice") },
                placeholder = { Text("nova") },
                singleLine = true,
                modifier = Modifier.width(120.dp)
            )
        }
        OutlinedButton(
            onClick = {
                val draft = currentDraft()
                vm.saveVoiceSettings(draft)
                vm.speakMessage("test_openai_tts", ttsSamplePhrase)
            }
        ) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.action_test), fontSize = 12.sp)
        }
    }

    if (ttsProvider == TtsProviderType.ANDROID_TTS) {
        OutlinedButton(
            onClick = {
                val draft = currentDraft()
                vm.saveVoiceSettings(draft)
                vm.speakMessage("test_android_tts", ttsSamplePhrase)
            }
        ) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.action_test), fontSize = 12.sp)
        }
    }

    if (ttsProvider == TtsProviderType.XTTS_V2 || ttsProvider == TtsProviderType.XTTS_EXTERNAL) {
        val isExternal = ttsProvider == TtsProviderType.XTTS_EXTERNAL
        val activeUrl = if (isExternal) xttsExternalUrl else xttsBaseUrl
        val activeVoice = if (isExternal) xttsExternalVoice else xttsVoice
        val activeBearer = if (isExternal) xttsExternalBearer else xttsBearer

        fun setActiveUrl(v: String) {
            if (isExternal) xttsExternalUrl = v else xttsBaseUrl = v
        }
        fun setActiveVoice(v: String) {
            if (isExternal) xttsExternalVoice = v else xttsVoice = v
        }
        fun setActiveBearer(v: String) {
            if (isExternal) xttsExternalBearer = v else xttsBearer = v
        }

        LaunchedEffect(ttsProvider, saved.xttsBaseUrl, saved.xttsExternalUrl) {
            val url = (if (isExternal) saved.xttsExternalUrl else saved.xttsBaseUrl).trim()
            if (url.startsWith("http") && voices.isEmpty()) {
                vm.testXttsAndLoadVoices(currentDraft()) { status ->
                    xttsTestStatus = status.message
                    val def = status.defaultVoice ?: status.voices.firstOrNull()
                    if (def != null && (activeVoice.isBlank() || activeVoice.equals("__default__", ignoreCase = true))) {
                        setActiveVoice(def)
                    }
                }
            }
        }

        OutlinedTextField(
            value = activeUrl,
            onValueChange = { setActiveUrl(it) },
            label = {
                Text(
                    if (isExternal) stringResource(R.string.settings_xtts_ext_url)
                    else stringResource(R.string.settings_xtts_loc_url)
                )
            },
            placeholder = {
                Text(if (isExternal) "https://....trycloudflare.com" else "http://192.168.1.100:8020")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = activeVoice,
            onValueChange = { setActiveVoice(it) },
            label = {
                Text(
                    if (isExternal) stringResource(R.string.settings_xtts_voice_ext)
                    else stringResource(R.string.settings_xtts_voice_loc)
                )
            },
            placeholder = { Text(defaultVoice ?: stringResource(R.string.settings_voice_loading)) },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (voices.isEmpty()) {
                            vm.testXttsAndLoadVoices(currentDraft()) { status ->
                                xttsTestStatus = status.message
                                val def = status.defaultVoice ?: status.voices.firstOrNull()
                                if (def != null && (activeVoice.isBlank() || activeVoice.equals("__default__", ignoreCase = true))) {
                                    setActiveVoice(def)
                                }
                                if (status.voices.isNotEmpty()) showVoicePicker = true
                            }
                        } else {
                            showVoicePicker = true
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_pick_voice_desc))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        val testPhrase = stringResource(R.string.settings_tts_sample_phrase)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ForgeActionPill(
                text = stringResource(R.string.settings_check_xtts_fmt, voices.size),
                icon = Icons.Rounded.Refresh,
                onClick = {
                    val draft = currentDraft()
                    vm.testXttsAndLoadVoices(draft) { status ->
                        xttsTestStatus = status.message
                        val def = status.defaultVoice ?: status.voices.firstOrNull()
                        if (def != null && (activeVoice.isBlank() || activeVoice.equals("__default__", ignoreCase = true))) {
                            setActiveVoice(def)
                        }
                    }
                }
            )
            ForgeActionPill(
                text = stringResource(R.string.settings_list_short),
                onClick = {
                    if (voices.isEmpty()) {
                        vm.testXttsAndLoadVoices(currentDraft()) { status ->
                            xttsTestStatus = status.message
                            if (status.voices.isNotEmpty()) showVoicePicker = true
                        }
                    } else {
                        showVoicePicker = true
                    }
                }
            )
            ForgeActionPill(
                text = stringResource(R.string.action_test),
                icon = Icons.Rounded.PlayArrow,
                accentColor = ForgeColors.AccentEmerald,
                onClick = {
                    val draft = currentDraft()
                    vm.saveVoiceSettings(draft)
                    vm.speakMessage("test_xtts", testPhrase)
                }
            )
        }

        if (showVoicePicker) {
            val filteredVoices = remember(voices, voiceQuery) {
                val q = voiceQuery.trim()
                voices.filter { v ->
                    !v.equals("__default__", ignoreCase = true) &&
                        (q.isBlank() || v.contains(q, ignoreCase = true))
                }.distinct()
            }
            AlertDialog(
                onDismissRequest = { showVoicePicker = false },
                title = { Text(stringResource(R.string.settings_xtts_voices_title, filteredVoices.size)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = voiceQuery,
                            onValueChange = { voiceQuery = it },
                            placeholder = { Text(stringResource(R.string.settings_search_voice)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(filteredVoices, key = { idx, vName -> "$idx:$vName" }) { _, vName ->
                                val isSelected = vName.equals(activeVoice, ignoreCase = true)
                                val isDefault = vName.equals(defaultVoice, ignoreCase = true)
                                Surface(
                                    color = if (isSelected) ForgeColors.AccentBlue.copy(alpha = 0.2f) else ForgeColors.SurfaceCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (isSelected) BorderStroke(1.dp, ForgeColors.AccentBlue) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            setActiveVoice(vName)
                                            showVoicePicker = false
                                            val updated = if (isExternal) {
                                                currentDraft().copy(xttsExternalVoice = vName)
                                            } else {
                                                currentDraft().copy(xttsVoice = vName)
                                            }
                                            vm.saveVoiceSettings(updated)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = vName,
                                            color = ForgeColors.TextPrimary,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isDefault) {
                                            Text(
                                                text = stringResource(R.string.label_default_tag),
                                                color = ForgeColors.AccentEmerald,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVoicePicker = false }) { Text(stringResource(R.string.action_close)) }
                }
            )
        }
        xttsTestStatus?.let { st ->
            Text(
                st,
                fontSize = 12.sp,
                color = if (st.contains("готов", ignoreCase = true) || st.contains("ready", ignoreCase = true) || !st.contains("err", ignoreCase = true) && !st.contains("ошиб", ignoreCase = true)) {
                    ForgeColors.AccentEmerald
                } else {
                    ForgeColors.DangerRed
                }
            )
        }

        Text(stringResource(R.string.settings_pitch_fmt, xttsPitch.toInt()), fontSize = 12.5.sp, color = ForgeColors.TextPrimary)
        Slider(value = xttsPitch, onValueChange = { xttsPitch = it }, valueRange = -6f..12f)
        SwitchRow(stringResource(R.string.settings_keep_tempo), xttsKeepTempo) { xttsKeepTempo = it }

        if (isExternal) {
            OutlinedTextField(
                value = xttsCfId,
                onValueChange = { xttsCfId = it },
                label = { Text(stringResource(R.string.settings_cf_id_tts)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = xttsCfSecret,
                onValueChange = { xttsCfSecret = it },
                label = { Text("CF-Access-Client-Secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = activeBearer,
            onValueChange = { setActiveBearer(it) },
            label = { Text(stringResource(R.string.settings_bearer_opt)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Text(
        stringResource(R.string.settings_speech_rate_fmt, String.format(java.util.Locale.US, "%.2f", ttsSpeed)),
        fontSize = 12.5.sp,
        color = ForgeColors.TextPrimary
    )
    Slider(
        value = ttsSpeed,
        onValueChange = { raw ->
            val snapped = ((raw * 20f + 0.5f).toInt() / 20f).coerceIn(0.6f, 2.0f)
            ttsSpeed = snapped
            vm.updateLiveTtsSpeed(snapped)
        },
        valueRange = 0.6f..2.0f
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(0.85f, 1.0f, 1.2f, 1.4f, 1.6f, 2.0f).forEach { preset ->
            val selected = kotlin.math.abs(ttsSpeed - preset) < 0.03f
            val label = if ((preset * 100f + 0.5f).toInt() % 10 == 0) {
                String.format(java.util.Locale.US, "%.1fx", preset)
            } else {
                String.format(java.util.Locale.US, "%.2fx", preset)
            }
            FilterChip(
                selected = selected,
                onClick = {
                    ttsSpeed = preset
                    vm.updateLiveTtsSpeed(preset)
                },
                label = { Text(label, fontSize = 11.5.sp) }
            )
        }
    }

    SwitchRow(stringResource(R.string.settings_auto_speak), autoSpeak) { autoSpeak = it }
    SwitchRow(stringResource(R.string.settings_skip_code_tts), skipCode) { skipCode = it }

    Button(
        onClick = { vm.saveVoiceSettings(currentDraft()) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.settings_save_voice))
    }
}

private enum class McpAuthMode(@StringRes val labelRes: Int?, val rawLabel: String = "") {
    NONE(R.string.auth_none),
    BEARER(null, "Bearer"),
    BASIC(R.string.auth_basic),
    API_KEY(null, "API Key")
}

@Composable
private fun McpAuthMode.localizedAuthLabel(): String =
    labelRes?.let { stringResource(it) } ?: rawLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuiltInMcpHostCard(vm: MainViewModel) {
    val context = LocalContext.current
    val hostCfg by vm.mcpHostSettings.collectAsState()
    val runtime by vm.mcpHostRuntime.collectAsState()
    var portDraft by remember(hostCfg.port) { mutableStateOf(hostCfg.port.toString()) }

    val effectivePort = if (runtime.boundPort > 0) runtime.boundPort else hostCfg.port
    val isRunning = runtime.isRunning && hostCfg.enabled
    val hasError = hostCfg.enabled && !runtime.lastError.isNullOrBlank()
    val copiedToast = stringResource(R.string.toast_copied_clipboard)

    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            when {
                hasError -> ForgeColors.DangerRed.copy(alpha = 0.65f)
                isRunning -> ForgeColors.AccentEmerald.copy(alpha = 0.6f)
                else -> ForgeColors.BorderSubtle
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.mcp_host_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ForgeColors.TextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 10.dp)
                )
                val pillColor = when {
                    hasError -> ForgeColors.DangerRed
                    isRunning -> ForgeColors.AccentEmerald
                    else -> ForgeColors.AccentAmber
                }
                Surface(
                    color = pillColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isRunning) {
                            stringResource(R.string.mcp_host_status_active_fmt, effectivePort)
                        } else {
                            stringResource(R.string.mcp_host_status_off)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1,
                        color = pillColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                stringResource(R.string.mcp_host_desc),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = ForgeColors.TextSecondary
            )

            SwitchRow(
                title = stringResource(R.string.mcp_host_enable_switch),
                checked = hostCfg.enabled,
                onCheckedChange = { enabled ->
                    val parsedPort = portDraft.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: hostCfg.port
                    vm.updateMcpHostSettings(hostCfg.copy(enabled = enabled, port = parsedPort))
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = portDraft,
                    onValueChange = { next -> portDraft = next.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.mcp_host_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val parsed = portDraft.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
                        portDraft = parsed.toString()
                        vm.updateMcpHostSettings(hostCfg.copy(port = parsed), showSavedToast = true)
                    }
                ) {
                    Text(stringResource(R.string.action_apply), fontSize = 12.sp)
                }
            }

            SwitchRow(
                title = stringResource(R.string.mcp_host_expose_shell),
                checked = hostCfg.exposeShellAndWriteTools,
                subtitle = stringResource(R.string.mcp_host_expose_shell_sub),
                onCheckedChange = { vm.updateMcpHostSettings(hostCfg.copy(exposeShellAndWriteTools = it)) }
            )

            SwitchRow(
                title = stringResource(R.string.mcp_host_visual_touch),
                checked = hostCfg.visualTouchFeedback,
                subtitle = stringResource(R.string.mcp_host_visual_touch_sub),
                onCheckedChange = { vm.updateMcpHostSettings(hostCfg.copy(visualTouchFeedback = it)) }
            )

            SwitchRow(
                title = stringResource(R.string.mcp_host_require_auth),
                checked = hostCfg.requireAuth,
                subtitle = stringResource(R.string.mcp_host_require_auth_sub),
                onCheckedChange = { vm.updateMcpHostSettings(hostCfg.copy(requireAuth = it)) }
            )

            AnimatedVisibility(visible = hostCfg.requireAuth) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = hostCfg.authToken,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.mcp_host_token_label)) },
                        modifier = Modifier.weight(1f)
                    )
                    ForgeIconButton(
                        icon = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = ForgeColors.AccentBlue,
                        size = 40.dp,
                        onClick = {
                            copyToClipboard(context, "MCP Token", hostCfg.authToken)
                            vm.showToast(copiedToast)
                        }
                    )
                    ForgeActionPill(
                        text = stringResource(R.string.mcp_host_regen_token),
                        icon = Icons.Rounded.Refresh,
                        minHeight = 40.dp,
                        onClick = { vm.regenerateMcpHostToken() }
                    )
                }
            }

            if (!runtime.lastError.isNullOrBlank() && hostCfg.enabled) {
                Text(
                    stringResource(R.string.mcp_err_fmt, runtime.lastError.orEmpty()),
                    fontSize = 11.5.sp,
                    color = ForgeColors.DangerRed
                )
            } else if (isRunning) {
                Text(
                    stringResource(R.string.mcp_host_stats_fmt, runtime.requestsHandled),
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    color = ForgeColors.AccentEmerald
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val mcpUrl = "http://127.0.0.1:$effectivePort/mcp"
                val jsonConfig = ForgeMcpHostServer.buildClientJsonConfig(
                    port = effectivePort,
                    requireAuth = hostCfg.requireAuth,
                    token = hostCfg.authToken
                )
                val adbCmd = "adb forward tcp:$effectivePort tcp:$effectivePort"
                val cliCmd = "fm-mcp dom"

                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "MCP URL", mcpUrl)
                        vm.showToast(copiedToast)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mcp_host_copy_url), fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "MCP JSON", jsonConfig)
                        vm.showToast(copiedToast)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mcp_host_copy_json), fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "ADB Forward", adbCmd)
                        vm.showToast(copiedToast)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mcp_host_copy_adb), fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "fm-mcp", cliCmd)
                        vm.showToast(copiedToast)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mcp_host_copy_cli), fontSize = 11.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpSettingsPane(vm: MainViewModel) {
    val servers by vm.mcpServers.collectAsState()
    val sshProfiles by vm.sshProfiles.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerEntity?>(null) }

    BuiltInMcpHostCard(vm)

    HorizontalDivider(color = ForgeColors.BorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.settings_mcp_connected_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
        Button(onClick = {
            editingServer = null
            showEditor = true
        }) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_add))
        }
    }

    Text(
        stringResource(R.string.settings_mcp_transports_hint),
        fontSize = 12.sp,
        color = ForgeColors.TextSecondary
    )

    SectionQuickGuideCard(
        title = stringResource(R.string.guide_mcp_title),
        subtitle = stringResource(R.string.guide_mcp_subtitle),
        body = stringResource(R.string.guide_mcp_body),
        defaultExpanded = servers.isEmpty()
    )

    Text(
        stringResource(R.string.guide_mcp_templates_label),
        fontSize = 11.5.sp,
        color = ForgeColors.TextSecondary
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterChip(
            selected = false,
            onClick = {
                editingServer = McpServerEntity(
                    id = "",
                    name = "Termux Filesystem MCP",
                    transport = McpTransport.SSH_STDIO.name,
                    endpoint = "npx -y @modelcontextprotocol/server-filesystem ~"
                )
                showEditor = true
            },
            label = { Text(stringResource(R.string.guide_mcp_tpl_fs), fontSize = 11.sp) }
        )
        FilterChip(
            selected = false,
            onClick = {
                editingServer = McpServerEntity(
                    id = "",
                    name = "MCP Fetch (Python)",
                    transport = McpTransport.SSH_STDIO.name,
                    endpoint = "python3 -m mcp_server_fetch"
                )
                showEditor = true
            },
            label = { Text(stringResource(R.string.guide_mcp_tpl_fetch), fontSize = 11.sp) }
        )
        FilterChip(
            selected = false,
            onClick = {
                editingServer = McpServerEntity(
                    id = "",
                    name = "Remote MCP Gateway",
                    transport = McpTransport.STREAMABLE_HTTP.name,
                    endpoint = "https://mcp.example.com/mcp"
                )
                showEditor = true
            },
            label = { Text(stringResource(R.string.guide_mcp_tpl_http), fontSize = 11.sp) }
        )
    }

    servers.forEach { srv ->
        val toolsCount = remember(srv.cachedToolsJson) {
            runCatching { LlmHttpFactory.json.parseToJsonElement(srv.cachedToolsJson).jsonArray.size }.getOrDefault(0)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(srv.name, fontWeight = FontWeight.SemiBold, color = ForgeColors.TextPrimary)
                        Text("${srv.transport} • ${srv.endpoint}", fontFamily = MonoFont, fontSize = 11.sp, color = ForgeColors.TextSecondary)
                        val instrNote = if (srv.instructions.isNotBlank()) {
                            stringResource(R.string.mcp_instructions_fmt, srv.instructions.length)
                        } else ""
                        val statusText = when {
                            !srv.enabled -> stringResource(R.string.mcp_state_disabled_fmt, toolsCount, instrNote)
                            !srv.lastError.isNullOrBlank() -> stringResource(R.string.mcp_state_error_fmt, toolsCount, instrNote)
                            srv.lastConnectedAt != null -> stringResource(R.string.mcp_state_connected_fmt, toolsCount, instrNote)
                            else -> stringResource(R.string.mcp_state_pending_fmt, toolsCount, instrNote)
                        }
                        val statusColor = when {
                            !srv.enabled -> ForgeColors.TextSecondary
                            !srv.lastError.isNullOrBlank() -> ForgeColors.DangerRed
                            srv.lastConnectedAt != null -> ForgeColors.AccentEmerald
                            else -> ForgeColors.AccentAmber
                        }
                        Text(
                            statusText,
                            fontSize = 11.sp,
                            color = statusColor
                        )
                    }
                    Switch(checked = srv.enabled, onCheckedChange = { vm.toggleMcpServer(srv, it) })
                }
                if (srv.enabled && !srv.lastError.isNullOrBlank()) {
                    Text(stringResource(R.string.mcp_err_fmt, srv.lastError.orEmpty()), fontSize = 11.5.sp, color = ForgeColors.DangerRed)
                } else if (!srv.enabled && !srv.lastError.isNullOrBlank()) {
                    Text(stringResource(R.string.mcp_prev_err_fmt, srv.lastError.orEmpty()), fontSize = 11.sp, color = ForgeColors.TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ForgeActionPill(
                        text = stringResource(R.string.action_check),
                        onClick = { vm.testMcpServer(srv.id) }
                    )
                    ForgeActionPill(
                        text = stringResource(R.string.action_edit),
                        icon = Icons.Rounded.Edit,
                        onClick = {
                            editingServer = srv
                            showEditor = true
                        }
                    )
                    ForgeIconButton(
                        icon = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = ForgeColors.DangerRed,
                        borderColor = ForgeColors.DangerRed.copy(alpha = 0.55f),
                        size = 34.dp,
                        iconSize = 17.dp,
                        onClick = { vm.deleteMcpServer(srv.id) }
                    )
                }
            }
        }
    }

    if (showEditor) {
        McpServerEditorDialog(
            initial = editingServer,
            sshProfiles = sshProfiles,
            onDismiss = { showEditor = false },
            onSave = { id, name, transport, endpoint, headersJson ->
                vm.upsertMcpServer(id, name, transport, endpoint, headersJson)
                showEditor = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerEditorDialog(
    initial: McpServerEntity?,
    sshProfiles: List<SshProfileEntity>,
    onDismiss: () -> Unit,
    onSave: (id: String?, name: String, transport: McpTransport, endpoint: String, headersJson: String) -> Unit
) {
    val parsedHdrs = remember(initial?.headersJson) {
        runCatching {
            LlmHttpFactory.json.parseToJsonElement(initial?.headersJson?.ifBlank { "{}" } ?: "{}").jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
    }

    val initAuthHeader = parsedHdrs.entries.firstOrNull { it.key.equals("Authorization", true) }?.value?.jsonPrimitive?.contentOrNull.orEmpty()
    val initCfId = parsedHdrs.entries.firstOrNull { it.key.equals("CF-Access-Client-Id", true) }?.value?.jsonPrimitive?.contentOrNull.orEmpty()
    val initCfSecret = parsedHdrs.entries.firstOrNull { it.key.equals("CF-Access-Client-Secret", true) }?.value?.jsonPrimitive?.contentOrNull.orEmpty()
    val initSshSrv = parsedHdrs["_ssh_server"]?.jsonPrimitive?.contentOrNull.orEmpty()

    val knownKeysLower = setOf("authorization", "cf-access-client-id", "cf-access-client-secret", "_ssh_server")
    val initExtraEntries = parsedHdrs.entries.filter { it.key.lowercase() !in knownKeysLower }

    var initMode = McpAuthMode.NONE
    var initBearer = ""
    var initBasicUser = ""
    var initBasicPass = ""
    var initApiKeyHeader = "X-API-Key"
    var initApiKeyVal = ""

    when {
        initAuthHeader.startsWith("Bearer ", ignoreCase = true) -> {
            initMode = McpAuthMode.BEARER
            initBearer = initAuthHeader.substring(7).trim()
        }
        initAuthHeader.startsWith("Basic ", ignoreCase = true) -> {
            initMode = McpAuthMode.BASIC
            val decoded = runCatching {
                String(Base64.decode(initAuthHeader.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            initBasicUser = decoded.substringBefore(':')
            initBasicPass = decoded.substringAfter(':', "")
        }
        initExtraEntries.size == 1 && (initExtraEntries.first().key.contains("key", true) || initExtraEntries.first().key.contains("token", true)) -> {
            initMode = McpAuthMode.API_KEY
            initApiKeyHeader = initExtraEntries.first().key
            initApiKeyVal = initExtraEntries.first().value.jsonPrimitive.contentOrNull.orEmpty()
        }
    }

    val remainingExtras = initExtraEntries
        .filterNot { initMode == McpAuthMode.API_KEY && it.key == initApiKeyHeader }
    val initExtraJson = if (remainingExtras.isEmpty()) "{}" else buildJsonObject {
        remainingExtras.forEach { (k, v) -> put(k, v) }
    }.toString()

    val isNewDraft = initial == null || initial.id.isBlank()
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var endpoint by remember(initial) { mutableStateOf(initial?.endpoint ?: "") }
    var transport by remember(initial) {
        mutableStateOf(
            runCatching { McpTransport.valueOf(initial?.transport ?: "") }.getOrDefault(McpTransport.STREAMABLE_HTTP)
        )
    }
    var authMode by remember(initial) { mutableStateOf(initMode) }
    var bearerToken by remember(initial) { mutableStateOf(initBearer) }
    var basicUser by remember(initial) { mutableStateOf(initBasicUser) }
    var basicPass by remember(initial) { mutableStateOf(initBasicPass) }
    var apiKeyHeader by remember(initial) { mutableStateOf(initApiKeyHeader) }
    var apiKeyValue by remember(initial) { mutableStateOf(initApiKeyVal) }
    var useCfAccess by remember(initial) { mutableStateOf(initCfId.isNotBlank() || initCfSecret.isNotBlank()) }
    var cfId by remember(initial) { mutableStateOf(initCfId) }
    var cfSecret by remember(initial) { mutableStateOf(initCfSecret) }
    var sshServerId by remember(initial) { mutableStateOf(initSshSrv) }
    var extraHeadersJson by remember(initial) { mutableStateOf(initExtraJson) }

    fun buildCombinedHeadersJson(): String = buildJsonObject {
        val extras = runCatching {
            LlmHttpFactory.json.parseToJsonElement(extraHeadersJson.ifBlank { "{}" }).jsonObject
        }.getOrNull()
        extras?.forEach { (k, v) ->
            if (k.isNotBlank()) put(k.trim(), v)
        }
        when (authMode) {
            McpAuthMode.NONE -> Unit
            McpAuthMode.BEARER -> if (bearerToken.isNotBlank()) {
                put("Authorization", JsonPrimitive("Bearer ${bearerToken.trim()}"))
            }
            McpAuthMode.BASIC -> if (basicUser.isNotBlank() || basicPass.isNotBlank()) {
                val rawCreds = "${basicUser.trim()}:$basicPass"
                val b64 = Base64.encodeToString(rawCreds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                put("Authorization", JsonPrimitive("Basic $b64"))
            }
            McpAuthMode.API_KEY -> if (apiKeyHeader.isNotBlank() && apiKeyValue.isNotBlank()) {
                put(apiKeyHeader.trim(), JsonPrimitive(apiKeyValue.trim()))
            }
        }
        if (useCfAccess) {
            if (cfId.isNotBlank()) put("CF-Access-Client-Id", JsonPrimitive(cfId.trim()))
            if (cfSecret.isNotBlank()) put("CF-Access-Client-Secret", JsonPrimitive(cfSecret.trim()))
        }
        if (transport == McpTransport.SSH_STDIO && sshServerId.isNotBlank()) {
            put("_ssh_server", JsonPrimitive(sshServerId))
        }
    }.toString()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNewDraft) stringResource(R.string.mcp_add_title) else stringResource(R.string.mcp_edit_title, initial?.name.orEmpty()),
                fontSize = 16.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.mcp_transport_label), fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val trLabels = mapOf(
                        McpTransport.STREAMABLE_HTTP to "Streamable HTTP",
                        McpTransport.HTTP_SSE to "HTTP + SSE",
                        McpTransport.SSH_STDIO to "SSH Stdio"
                    )
                    McpTransport.entries.forEach { tr ->
                        FilterChip(
                            selected = transport == tr,
                            onClick = { transport = tr },
                            label = { Text(trLabels[tr] ?: tr.name, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = {
                        Text(
                            when (transport) {
                                McpTransport.SSH_STDIO -> stringResource(R.string.mcp_cmd_stdio_hint)
                                McpTransport.HTTP_SSE -> stringResource(R.string.mcp_url_sse_hint)
                                McpTransport.STREAMABLE_HTTP -> stringResource(R.string.mcp_url_http_hint)
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (transport == McpTransport.SSH_STDIO) {
                    Text(stringResource(R.string.mcp_target_ssh), fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = sshServerId.isBlank(),
                            onClick = { sshServerId = "" },
                            label = { Text(stringResource(R.string.mcp_primary_ssh), fontSize = 11.sp) }
                        )
                        sshProfiles.forEach { p ->
                            FilterChip(
                                selected = sshServerId == p.id,
                                onClick = { sshServerId = p.id },
                                label = { Text(p.name, fontSize = 11.sp) }
                            )
                        }
                    }
                } else {
                    HorizontalDivider(color = ForgeColors.BorderSubtle)
                    Text(stringResource(R.string.mcp_auth_label), fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        McpAuthMode.entries.forEach { m ->
                            FilterChip(
                                selected = authMode == m,
                                onClick = { authMode = m },
                                label = { Text(m.localizedAuthLabel(), fontSize = 11.sp) }
                            )
                        }
                    }

                    when (authMode) {
                        McpAuthMode.NONE -> Unit
                        McpAuthMode.BEARER -> {
                            OutlinedTextField(
                                value = bearerToken,
                                onValueChange = { bearerToken = it },
                                label = { Text("Bearer Token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        McpAuthMode.BASIC -> {
                            OutlinedTextField(
                                value = basicUser,
                                onValueChange = { basicUser = it },
                                label = { Text(stringResource(R.string.mcp_username_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = basicPass,
                                onValueChange = { basicPass = it },
                                label = { Text(stringResource(R.string.mcp_password_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        McpAuthMode.API_KEY -> {
                            OutlinedTextField(
                                value = apiKeyHeader,
                                onValueChange = { apiKeyHeader = it },
                                label = { Text(stringResource(R.string.mcp_header_name_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = apiKeyValue,
                                onValueChange = { apiKeyValue = it },
                                label = { Text(stringResource(R.string.mcp_token_value_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    SwitchRow("Cloudflare Access (Service Token)", useCfAccess) { useCfAccess = it }
                    if (useCfAccess) {
                        OutlinedTextField(
                            value = cfId,
                            onValueChange = { cfId = it },
                            label = { Text("CF-Access-Client-Id") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = cfSecret,
                            onValueChange = { cfSecret = it },
                            label = { Text("CF-Access-Client-Secret") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = extraHeadersJson,
                        onValueChange = { extraHeadersJson = it },
                        label = { Text(stringResource(R.string.mcp_extra_headers)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && endpoint.isNotBlank()) {
                        onSave(initial?.id?.takeIf { it.isNotBlank() }, name, transport, endpoint, buildCombinedHeadersJson())
                    }
                }
            ) {
                Text(if (isNewDraft) stringResource(R.string.action_connect) else stringResource(R.string.mcp_save_and_check))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvironmentsSettingsPane(
    vm: MainViewModel,
    onPickSafFolder: () -> Unit
) {
    val context = LocalContext.current
    val safTreeUri by vm.safTreeUri.collectAsState()
    val profiles by vm.sshProfiles.collectAsState()
    val mcpServers by vm.mcpServers.collectAsState()

    val defaultProfile = profiles.firstOrNull { it.isDefault }
        ?: profiles.firstOrNull()
        ?: TermuxBridge.defaultTermuxProfile()

    var editingId by remember { mutableStateOf(defaultProfile.id) }
    var isCreatingNew by remember { mutableStateOf(false) }

    val selectedProfile = remember(profiles, editingId, isCreatingNew) {
        if (isCreatingNew) {
            SshProfileEntity(
                id = editingId,
                name = context.getString(R.string.ssh_default_port_name, 2222),
                host = "127.0.0.1",
                port = 2222,
                username = "",
                password = "",
                defaultWorkDir = ""
            )
        } else {
            profiles.firstOrNull { it.id == editingId } ?: defaultProfile
        }
    }

    var name by remember(selectedProfile) { mutableStateOf(selectedProfile.name) }
    var host by remember(selectedProfile) { mutableStateOf(selectedProfile.host) }
    var port by remember(selectedProfile) { mutableStateOf(selectedProfile.port.toString()) }
    var username by remember(selectedProfile) { mutableStateOf(selectedProfile.username) }
    var password by remember(selectedProfile) { mutableStateOf(selectedProfile.password) }
    var privateKeyPem by remember(selectedProfile) { mutableStateOf(selectedProfile.privateKeyPem) }
    var passphrase by remember(selectedProfile) { mutableStateOf(selectedProfile.keyPassphrase) }
    var workDir by remember(selectedProfile) { mutableStateOf(selectedProfile.defaultWorkDir) }
    var enabled by remember(selectedProfile) { mutableStateOf(selectedProfile.enabled) }
    var useCloudflareAccess by remember(selectedProfile) { mutableStateOf(selectedProfile.useCloudflareAccess) }
    var cfAccessClientId by remember(selectedProfile) { mutableStateOf(selectedProfile.cfAccessClientId) }
    var cfAccessClientSecret by remember(selectedProfile) { mutableStateOf(selectedProfile.cfAccessClientSecret) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val mcpCfCreds = remember(mcpServers, host) {
        com.forgemind.app.bridge.CloudflareAccessSshSupport.extractFromMcpServers(mcpServers, host)
    }

    fun fillCfTokensFromMcp(overwrite: Boolean) {
        val creds = com.forgemind.app.bridge.CloudflareAccessSshSupport.extractFromMcpServers(mcpServers, host) ?: return
        if (overwrite || cfAccessClientId.isBlank()) cfAccessClientId = creds.clientId
        if (overwrite || cfAccessClientSecret.isBlank()) cfAccessClientSecret = creds.clientSecret
    }

    fun draft() = selectedProfile.copy(
        id = if (isCreatingNew) editingId else selectedProfile.id.ifBlank { UUID.randomUUID().toString() },
        name = name.trim().ifBlank { context.getString(R.string.ssh_default_port_name, port.toIntOrNull() ?: 2222) },
        host = host.trim(),
        port = port.toIntOrNull()?.coerceIn(1, 65535) ?: if (useCloudflareAccess) 443 else 2222,
        username = username.trim(),
        password = password,
        privateKeyPem = privateKeyPem,
        keyPassphrase = passphrase,
        defaultWorkDir = workDir.trim(),
        enabled = enabled,
        useCloudflareAccess = useCloudflareAccess,
        cfAccessClientId = cfAccessClientId.trim(),
        cfAccessClientSecret = cfAccessClientSecret.trim()
    )

    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.env_all_active_note),
            fontSize = 12.sp,
            color = ForgeColors.TextSecondary,
            modifier = Modifier.padding(12.dp)
        )
    }

    SectionQuickGuideCard(
        title = stringResource(R.string.guide_env_title),
        subtitle = stringResource(R.string.guide_env_subtitle),
        body = stringResource(R.string.guide_env_body),
        copyButtonLabel = stringResource(R.string.guide_copy_termux_cmd),
        copyValue = "pkg update -y && pkg install -y openssh && passwd && sshd -p 2222",
        onCopied = { vm.showToast(context.getString(R.string.copied_to_clipboard)) }
    )

    // ---- Card 1: Android SAF Folder for AI ----
    Card(
        colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.FolderOpen, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.saf_card_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
            }
            Text(
                if (safTreeUri.isNullOrBlank()) {
                    stringResource(R.string.saf_current_none)
                } else {
                    stringResource(R.string.saf_current_fmt, formatSafTreeLabelLocalized(context, safTreeUri))
                },
                fontSize = 12.5.sp,
                color = if (safTreeUri.isNullOrBlank()) ForgeColors.TextSecondary else ForgeColors.AccentEmerald
            )
            if (!safTreeUri.isNullOrBlank()) {
                Text(
                    safTreeUri!!,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    color = ForgeColors.TextSecondary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPickSafFolder,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (safTreeUri.isNullOrBlank()) stringResource(R.string.saf_pick_cta) else stringResource(R.string.saf_change_cta),
                        fontSize = 12.sp
                    )
                }
                if (!safTreeUri.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { vm.clearSafFolderUri() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.action_reset), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    HorizontalDivider(color = ForgeColors.BorderSubtle)

    // ---- Card 2: SSH Servers list + Add button ----
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.ssh_section_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
        Button(
            onClick = {
                editingId = UUID.randomUUID().toString()
                isCreatingNew = true
                testResult = null
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.ssh_add_server), fontSize = 12.sp)
        }
    }

    val hostUnset = stringResource(R.string.ssh_host_unset)
    profiles.forEach { p ->
        val isEditing = !isCreatingNew && p.id == selectedProfile.id
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isEditing) ForgeColors.SurfaceElevated else ForgeColors.SurfaceCard
            ),
            border = if (p.isDefault) BorderStroke(1.dp, ForgeColors.AccentEmerald.copy(alpha = 0.7f)) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    isCreatingNew = false
                    editingId = p.id
                    testResult = null
                }
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (p.isDefault) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = ForgeColors.AccentEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Column {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = ForgeColors.TextPrimary)
                            if (p.isDefault) {
                                Text(stringResource(R.string.ssh_primary_default), fontSize = 10.5.sp, color = ForgeColors.AccentEmerald)
                            } else if (p.enabled) {
                                Text(stringResource(R.string.ssh_enabled_for_ai), fontSize = 10.5.sp, color = ForgeColors.AccentBlue)
                            }
                        }
                    }
                    Switch(checked = p.enabled, onCheckedChange = { vm.toggleSshProfileEnabled(p, it) })
                }
                val userLabel = p.username.ifBlank { stringResource(R.string.ssh_user_unset) }
                val wdLabel = if (p.defaultWorkDir.isNotBlank()) " • ${p.defaultWorkDir}" else ""
                Text(
                    "$userLabel@${p.host.ifBlank { hostUnset }}:${p.port}$wdLabel",
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!p.isDefault) {
                        ForgeActionPill(
                            text = stringResource(R.string.ssh_primary_btn),
                            accentColor = ForgeColors.AccentEmerald,
                            onClick = { vm.activateSshProfile(p.id) }
                        )
                    }
                    ForgeActionPill(
                        text = if (isEditing) stringResource(R.string.ssh_editing_btn) else stringResource(R.string.action_configure),
                        active = isEditing,
                        onClick = {
                            isCreatingNew = false
                            editingId = p.id
                            testResult = null
                        }
                    )
                    if (profiles.size > 1) {
                        ForgeIconButton(
                            icon = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = ForgeColors.DangerRed,
                            borderColor = ForgeColors.DangerRed.copy(alpha = 0.55f),
                            size = 32.dp,
                            iconSize = 16.dp,
                            onClick = { vm.deleteSshProfile(p.id) }
                        )
                    }
                }
            }
        }
    }

    // ---- Card 3: Manual SSH Server Editor ----
    Card(
        colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (isCreatingNew) stringResource(R.string.ssh_new_title) else stringResource(R.string.ssh_edit_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ForgeColors.AccentBlue
            )

            SwitchRow(stringResource(R.string.ssh_use_in_chat), enabled) { enabled = it }

            val extCfName = stringResource(R.string.ssh_preset_ext_cf)
            val extPortName = stringResource(R.string.ssh_preset_ext_port)
            val localTermuxName = stringResource(R.string.ssh_preset_local_termux)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = useCloudflareAccess,
                    onClick = {
                        useCloudflareAccess = true
                        port = "443"
                        if (host.isBlank() || host == "127.0.0.1") {
                            host = ""
                        }
                        if (name.startsWith("Termux") || name.startsWith("SSH") || name.isBlank()) {
                            name = extCfName
                        }
                        if (workDir == "/data/data/com.termux/files/home") workDir = ""
                        fillCfTokensFromMcp(overwrite = false)
                    },
                    label = { Text("Cloudflare SSH (:443)", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = !useCloudflareAccess && (name.contains("внешн", ignoreCase = true) || name.contains("External", ignoreCase = true) || (!name.contains("termux", true) && port == "2222")),
                    onClick = {
                        useCloudflareAccess = false
                        port = "2222"
                        if (name.startsWith("Termux") || name.startsWith("SSH") || name.isBlank()) {
                            name = extPortName
                        }
                        if (workDir == "/data/data/com.termux/files/home") workDir = ""
                    },
                    label = { Text(extPortName, fontSize = 11.sp) }
                )
                FilterChip(
                    selected = !useCloudflareAccess && name.contains("termux", ignoreCase = true) && port == "2222",
                    onClick = {
                        useCloudflareAccess = false
                        host = host.ifBlank { "127.0.0.1" }
                        port = "2222"
                        if (name.startsWith("SSH") || name.isBlank()) name = localTermuxName
                        if (workDir.isBlank()) workDir = "/data/data/com.termux/files/home"
                    },
                    label = { Text("Termux :2222", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = !useCloudflareAccess && port == "8022",
                    onClick = {
                        useCloudflareAccess = false
                        host = host.ifBlank { "127.0.0.1" }
                        port = "8022"
                        if (name.startsWith("SSH") || name.isBlank()) name = "Termux (:8022)"
                        if (workDir.isBlank()) workDir = "/data/data/com.termux/files/home"
                    },
                    label = { Text("Termux :8022", fontSize = 11.sp) }
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.mcp_server_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = {
                        Text(
                            if (useCloudflareAccess) stringResource(R.string.ssh_cf_host_hint)
                            else stringResource(R.string.ssh_host_hint)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(92.dp)
                )
            }
            SwitchRow(stringResource(R.string.ssh_cf_tunnel_toggle), useCloudflareAccess) { checked ->
                useCloudflareAccess = checked
                if (checked) {
                    if (port.isBlank() || port == "2222" || port == "8022") port = "443"
                    fillCfTokensFromMcp(overwrite = false)
                }
            }
            if (useCloudflareAccess) {
                OutlinedButton(
                    onClick = { fillCfTokensFromMcp(overwrite = true) },
                    enabled = mcpCfCreds != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (mcpCfCreds != null) {
                            stringResource(R.string.ssh_fill_cf_from_mcp, mcpCfCreds.sourceServerName)
                        } else {
                            stringResource(R.string.ssh_no_cf_in_mcp)
                        },
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = cfAccessClientId,
                    onValueChange = { cfAccessClientId = it },
                    label = {
                        Text(
                            if (mcpCfCreds != null) {
                                stringResource(R.string.ssh_cf_id_label)
                            } else {
                                "CF-Access-Client-Id"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cfAccessClientSecret,
                    onValueChange = { cfAccessClientSecret = it },
                    label = {
                        Text(
                            if (mcpCfCreds != null) {
                                stringResource(R.string.ssh_cf_secret_label)
                            } else {
                                "CF-Access-Client-Secret"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.ssh_cf_explain),
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.ssh_username_req)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.ssh_password_opt)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = privateKeyPem,
                onValueChange = { privateKeyPem = it },
                label = { Text(stringResource(R.string.ssh_pem_key_label)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.ssh_passphrase_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = workDir,
                onValueChange = { workDir = it },
                label = { Text(stringResource(R.string.ssh_workdir_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ForgeActionPill(
                    text = stringResource(R.string.action_test),
                    accentColor = ForgeColors.AccentCyan,
                    minHeight = 38.dp,
                    onClick = {
                        testResult = context.getString(R.string.ssh_connecting)
                        vm.testSshConnection(draft()) { testResult = it }
                    }
                )
                Button(
                    onClick = {
                        val saved = draft()
                        vm.saveSshProfile(saved, makeDefault = false)
                        isCreatingNew = false
                        editingId = saved.id
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.action_save), fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                ForgeActionPill(
                    text = stringResource(R.string.ssh_primary_btn),
                    accentColor = ForgeColors.AccentEmerald,
                    minHeight = 38.dp,
                    onClick = {
                        val saved = draft()
                        vm.saveAndActivateSshProfile(saved)
                        isCreatingNew = false
                        editingId = saved.id
                    }
                )
            }

            testResult?.let { res ->
                Text(
                    res,
                    fontFamily = MonoFont,
                    fontSize = 11.5.sp,
                    color = when {
                        res.startsWith("Подключено") || res.startsWith("Connected") -> ForgeColors.AccentEmerald
                        res.startsWith("Ошибка") || res.startsWith("Error") -> ForgeColors.DangerRed
                        else -> ForgeColors.AccentAmber
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentAndWebSettingsPane(
    vm: MainViewModel,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit
) {
    val context = LocalContext.current
    val searchSaved by vm.searchSettings.collectAsState()
    val agentSaved by vm.agentSettings.collectAsState()

    var engine by remember(searchSaved) { mutableStateOf(searchSaved.primaryEngine) }
    var tavilyKey by remember(searchSaved) { mutableStateOf(searchSaved.tavilyApiKey) }
    var braveKey by remember(searchSaved) { mutableStateOf(searchSaved.braveApiKey) }
    var googleKey by remember(searchSaved) { mutableStateOf(searchSaved.googleCseKey) }
    var googleCx by remember(searchSaved) { mutableStateOf(searchSaved.googleCseCx) }
    var searxUrl by remember(searchSaved) { mutableStateOf(searchSaved.searxngUrl) }
    var jsScrape by remember(searchSaved) { mutableStateOf(searchSaved.enableJsScrape) }

    var enableTools by remember(agentSaved) { mutableStateOf(agentSaved.enableTools) }
    var autoApproveSafe by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveSafe) }
    var autoApproveAll by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveAll) }
    var autoApproveRead by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveRead) }
    var autoApproveFileWrite by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveFileWrite) }
    var autoApproveFileDelete by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveFileDelete) }
    var autoApproveShell by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveShell) }
    var autoApproveBrowser by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveBrowser) }
    var autoApproveBrowserJs by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveBrowserJs) }
    var autoApproveMcp by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveMcp) }
    var autoApproveChatManage by remember(agentSaved) { mutableStateOf(agentSaved.autoApproveChatManage) }
    var blockedToolsText by remember(agentSaved) { mutableStateOf(agentSaved.blockedTools.joinToString(", ")) }
    var allowedToolsText by remember(agentSaved) { mutableStateOf(agentSaved.allowedTools.joinToString(", ")) }
    var parallelCalls by remember(agentSaved) { mutableStateOf(agentSaved.parallelToolCalls) }
    var autoCompact by remember(agentSaved) { mutableStateOf(agentSaved.autoCompactContext) }
    var compactPct by remember(agentSaved) { mutableStateOf(agentSaved.compactThresholdPercent.toString()) }
    var thinkEveryCalls by remember(agentSaved) { mutableStateOf(agentSaved.thinkEveryToolCalls.toString()) }
    var maxTurnsText by remember(agentSaved) { mutableStateOf(agentSaved.maxTurns.toString()) }
    var autoSnapshot by remember(agentSaved) { mutableStateOf(agentSaved.autoSnapshotBeforeEdit) }
    var globalPrompt by remember(agentSaved) { mutableStateOf(agentSaved.globalSystemPrompt) }

    Text(stringResource(R.string.settings_web_search_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentBlue)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SearchEngineType.entries.forEach { e ->
            FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e.localizedLabel(context), fontSize = 11.5.sp) })
        }
    }

    when (engine) {
        SearchEngineType.TAVILY -> OutlinedTextField(value = tavilyKey, onValueChange = { tavilyKey = it }, label = { Text("Tavily API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SearchEngineType.BRAVE -> OutlinedTextField(value = braveKey, onValueChange = { braveKey = it }, label = { Text("Brave Search API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SearchEngineType.GOOGLE_CSE -> {
            OutlinedTextField(value = googleKey, onValueChange = { googleKey = it }, label = { Text("Google CSE API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = googleCx, onValueChange = { googleCx = it }, label = { Text("Search Engine ID (cx)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        SearchEngineType.SEARXNG -> OutlinedTextField(value = searxUrl, onValueChange = { searxUrl = it }, label = { Text("SearXNG Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SearchEngineType.DUCKDUCKGO -> Text(stringResource(R.string.settings_ddg_note), fontSize = 12.sp, color = ForgeColors.TextSecondary)
    }
    SwitchRow(stringResource(R.string.settings_headless_js), jsScrape) { jsScrape = it }

    Button(onClick = {
        vm.saveSearchSettings(
            SearchSettings(engine, tavilyKey, braveKey, googleKey, googleCx, searxUrl, jsScrape)
        )
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_save_search)) }

    HorizontalDivider(color = ForgeColors.BorderSubtle)

    Text(stringResource(R.string.settings_agent_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentEmerald)

    val a11yConnected by com.forgemind.app.accessibility.ForgeAccessibilityService.isConnected.collectAsState()
    Card(
        colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard),
        border = BorderStroke(
            1.dp,
            if (a11yConnected) ForgeColors.AccentEmerald.copy(alpha = 0.6f) else ForgeColors.BorderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.a11y_card_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ForgeColors.TextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 10.dp)
                )
                Surface(
                    color = (if (a11yConnected) ForgeColors.AccentEmerald else ForgeColors.AccentAmber).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        if (a11yConnected) stringResource(R.string.a11y_status_active) else stringResource(R.string.a11y_status_off),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1,
                        color = if (a11yConnected) ForgeColors.AccentEmerald else ForgeColors.AccentAmber,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.a11y_card_desc),
                fontSize = 11.5.sp,
                color = ForgeColors.TextSecondary
            )
            OutlinedButton(
                onClick = { com.forgemind.app.accessibility.ForgeAccessibilityService.openAccessibilitySettings(context) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.a11y_open_settings_btn), fontSize = 12.sp)
            }
        }
    }

    SwitchRow(stringResource(R.string.settings_enable_tools), enableTools) { enableTools = it }
    SwitchRow(stringResource(R.string.settings_auto_safe), autoApproveSafe, stringResource(R.string.settings_auto_safe_sub)) { autoApproveSafe = it }
    SwitchRow(stringResource(R.string.settings_auto_all), autoApproveAll, stringResource(R.string.settings_auto_all_sub)) { autoApproveAll = it }

    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.settings_granular_header), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ForgeColors.AccentBlue)
    SwitchRow(stringResource(R.string.settings_perm_read), autoApproveRead, "file_read, search_files, web_search, git_status, env_info") { autoApproveRead = it }
    SwitchRow(stringResource(R.string.settings_perm_write), autoApproveFileWrite, "file_write, file_edit, file_move, archive_*") { autoApproveFileWrite = it }
    SwitchRow(stringResource(R.string.settings_perm_delete), autoApproveFileDelete, stringResource(R.string.settings_perm_delete_sub)) { autoApproveFileDelete = it }
    SwitchRow(stringResource(R.string.settings_perm_shell), autoApproveShell, stringResource(R.string.settings_perm_shell_sub)) { autoApproveShell = it }
    SwitchRow(stringResource(R.string.settings_perm_browser), autoApproveBrowser, "browser_agent_navigate, click, type, screenshot") { autoApproveBrowser = it }
    SwitchRow(stringResource(R.string.settings_perm_browser_js), autoApproveBrowserJs, stringResource(R.string.settings_perm_browser_js_sub)) { autoApproveBrowserJs = it }
    SwitchRow(stringResource(R.string.settings_perm_mcp), autoApproveMcp, stringResource(R.string.settings_perm_mcp_sub)) { autoApproveMcp = it }
    SwitchRow(stringResource(R.string.settings_perm_chat), autoApproveChatManage, "chat_manage, export, voice_speak, preview_server") { autoApproveChatManage = it }

    OutlinedTextField(
        value = blockedToolsText,
        onValueChange = { blockedToolsText = it },
        label = { Text(stringResource(R.string.settings_blocklist_label)) },
        placeholder = { Text("file_delete, run_command, browser_agent_eval_js") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = allowedToolsText,
        onValueChange = { allowedToolsText = it },
        label = { Text(stringResource(R.string.settings_allowlist_label)) },
        placeholder = { Text("git_status, file_info, env_info") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    SwitchRow(stringResource(R.string.settings_parallel_calls), parallelCalls) { parallelCalls = it }
    SwitchRow(stringResource(R.string.settings_auto_compact), autoCompact) { autoCompact = it }
    SwitchRow(stringResource(R.string.settings_auto_snapshot), autoSnapshot) { autoSnapshot = it }

    OutlinedTextField(
        value = thinkEveryCalls,
        onValueChange = { thinkEveryCalls = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.settings_reflection_interval)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = maxTurnsText,
        onValueChange = { maxTurnsText = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.settings_max_turns)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = compactPct,
        onValueChange = { compactPct = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.settings_compact_threshold)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = globalPrompt,
        onValueChange = { globalPrompt = it },
        label = { Text(stringResource(R.string.settings_global_sys_prompt)) },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )

    Button(onClick = {
        val blockedList = blockedToolsText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val allowedList = allowedToolsText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        vm.saveAgentSettings(
            agentSaved.copy(
                enableTools = enableTools,
                autoApproveSafe = autoApproveSafe,
                autoApproveAll = autoApproveAll,
                maxTurns = (maxTurnsText.toIntOrNull() ?: 100).coerceIn(1, 500),
                thinkEveryToolCalls = (thinkEveryCalls.toIntOrNull() ?: 25).coerceIn(0, 200),
                parallelToolCalls = parallelCalls,
                autoCompactContext = autoCompact,
                compactThresholdPercent = (compactPct.toIntOrNull() ?: 78).coerceIn(40, 95),
                autoSnapshotBeforeEdit = autoSnapshot,
                globalSystemPrompt = globalPrompt,
                autoApproveRead = autoApproveRead,
                autoApproveFileWrite = autoApproveFileWrite,
                autoApproveFileDelete = autoApproveFileDelete,
                autoApproveShell = autoApproveShell,
                autoApproveBrowser = autoApproveBrowser,
                autoApproveBrowserJs = autoApproveBrowserJs,
                autoApproveMcp = autoApproveMcp,
                autoApproveChatManage = autoApproveChatManage,
                blockedTools = blockedList,
                allowedTools = allowedList
            )
        )
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_save_agent)) }

    HorizontalDivider(color = ForgeColors.BorderSubtle)
    Text(stringResource(R.string.settings_zip_archive_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ForgeColors.TextPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onExportZip,
            border = BorderStroke(1.dp, ForgeColors.BorderHighlight),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.settings_export_zip), color = ForgeColors.TextPrimary, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = onImportZip,
            border = BorderStroke(1.dp, ForgeColors.BorderHighlight),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.settings_import_zip), color = ForgeColors.TextPrimary, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 13.sp, color = ForgeColors.TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 11.sp, color = ForgeColors.TextSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionQuickGuideCard(
    title: String,
    subtitle: String,
    body: String,
    defaultExpanded: Boolean = false,
    copyButtonLabel: String? = null,
    copyValue: String? = null,
    onCopied: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(defaultExpanded) }

    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ForgeColors.AccentBlue.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = null,
                        tint = ForgeColors.AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ForgeColors.TextPrimary)
                        Text(subtitle, fontSize = 11.sp, color = ForgeColors.TextSecondary)
                    }
                }
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.guide_tap_hint_hide else R.string.guide_tap_hint_show
                    ),
                    tint = ForgeColors.TextSecondary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = ForgeColors.BorderSubtle)
                    Text(
                        text = body,
                        fontSize = 11.5.sp,
                        lineHeight = 16.5.sp,
                        color = ForgeColors.TextPrimary
                    )
                    if (!copyButtonLabel.isNullOrBlank() && !copyValue.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, title, copyValue)
                                onCopied?.invoke()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(copyButtonLabel, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
    }
}

private data class ThemeSwatchSpec(
    val name: String,
    val hex: String
)

private val MAJOR_THEME_SWATCHES = listOf(
    ThemeSwatchSpec("Crimson", "#EF4444"),
    ThemeSwatchSpec("Rose", "#EC4899"),
    ThemeSwatchSpec("Orange", "#F97316"),
    ThemeSwatchSpec("Amber", "#F59E0B"),
    ThemeSwatchSpec("Gold", "#EAB308"),
    ThemeSwatchSpec("Emerald", "#10B981"),
    ThemeSwatchSpec("Mint", "#14B8A6"),
    ThemeSwatchSpec("Cyan", "#06B6D4"),
    ThemeSwatchSpec("Sky", "#38BDF8"),
    ThemeSwatchSpec("Royal Blue", "#4F8CFF"),
    ThemeSwatchSpec("Indigo", "#6366F1"),
    ThemeSwatchSpec("Violet", "#8B5CF6"),
    ThemeSwatchSpec("Purple", "#A855F7"),
    ThemeSwatchSpec("Pure Black", "#000000"),
    ThemeSwatchSpec("Deep Obsidian", "#0B0F17"),
    ThemeSwatchSpec("Midnight", "#121826"),
    ThemeSwatchSpec("Slate", "#1E273B"),
    ThemeSwatchSpec("Frost", "#E2E8F0")
)

private data class UiThemePresetSpec(
    val id: String,
    val title: String,
    val bgDeepHex: String,
    val surfaceDarkHex: String,
    val surfaceCardHex: String,
    val userBubbleHex: String,
    val aiBubbleHex: String,
    val accentHex: String,
    val borderHex: String
)

private val UI_THEME_PRESETS = listOf(
    UiThemePresetSpec(
        id = "forge_default",
        title = "Classic Forge",
        bgDeepHex = "#0B0F17",
        surfaceDarkHex = "#121826",
        surfaceCardHex = "#1E273B",
        userBubbleHex = "#204382",
        aiBubbleHex = "#162033",
        accentHex = "#4F8CFF",
        borderHex = "#35445E"
    ),
    UiThemePresetSpec(
        id = "oled_black",
        title = "OLED Pure Black",
        bgDeepHex = "#000000",
        surfaceDarkHex = "#070A0F",
        surfaceCardHex = "#101622",
        userBubbleHex = "#1B3A70",
        aiBubbleHex = "#0C111B",
        accentHex = "#38BDF8",
        borderHex = "#243044"
    ),
    UiThemePresetSpec(
        id = "emerald_matrix",
        title = "Emerald Matrix",
        bgDeepHex = "#040D08",
        surfaceDarkHex = "#081710",
        surfaceCardHex = "#0F241A",
        userBubbleHex = "#0E442D",
        aiBubbleHex = "#0A1B13",
        accentHex = "#10B981",
        borderHex = "#1E4D36"
    ),
    UiThemePresetSpec(
        id = "cyberpunk_violet",
        title = "Cyberpunk Violet",
        bgDeepHex = "#0C0614",
        surfaceDarkHex = "#150B24",
        surfaceCardHex = "#211238",
        userBubbleHex = "#4C1D95",
        aiBubbleHex = "#190E2B",
        accentHex = "#A855F7",
        borderHex = "#4A257A"
    ),
    UiThemePresetSpec(
        id = "warm_amber",
        title = "Warm Amber",
        bgDeepHex = "#120C06",
        surfaceDarkHex = "#1C130A",
        surfaceCardHex = "#291C10",
        userBubbleHex = "#613B12",
        aiBubbleHex = "#21160C",
        accentHex = "#F59E0B",
        borderHex = "#52391E"
    ),
    UiThemePresetSpec(
        id = "deep_ocean",
        title = "Deep Ocean",
        bgDeepHex = "#05101A",
        surfaceDarkHex = "#0A1B2B",
        surfaceCardHex = "#11283E",
        userBubbleHex = "#134B75",
        aiBubbleHex = "#0D2033",
        accentHex = "#06B6D4",
        borderHex = "#1F496B"
    )
)

private data class TerminalPresetSpec(
    val id: String,
    val title: String,
    val bgHex: String,
    val textHex: String,
    val promptHex: String
)

private val TERMINAL_THEME_PRESETS = listOf(
    TerminalPresetSpec("classic", "Classic Dark", "#0A0F18", "#DCE7F3", "#38BDF8"),
    TerminalPresetSpec("matrix", "Matrix Green", "#030A05", "#4ADE80", "#10B981"),
    TerminalPresetSpec("amber_crt", "Amber CRT", "#120C02", "#FBBF24", "#F59E0B"),
    TerminalPresetSpec("oled_black", "OLED Black", "#000000", "#E2E8F0", "#38BDF8"),
    TerminalPresetSpec("cyberpunk", "Cyberpunk", "#0D0618", "#F472B6", "#06B6D4"),
    TerminalPresetSpec("dracula", "Dracula", "#1E1F29", "#F8F8F2", "#A855F7")
)

private enum class UiColorComponent(@StringRes val labelRes: Int, val supportsAlpha: Boolean) {
    BG(R.string.theme_comp_bg, false),
    SURFACE(R.string.theme_comp_surface, true),
    CARDS(R.string.theme_comp_cards, true),
    USER_MSG(R.string.theme_comp_user_msg, true),
    AI_MSG(R.string.theme_comp_ai_msg, true),
    ACCENT(R.string.theme_comp_accent, true),
    BORDERS(R.string.theme_comp_borders, true)
}

private enum class TerminalColorComponent(@StringRes val labelRes: Int, val supportsAlpha: Boolean) {
    BG(R.string.theme_term_bg, true),
    TEXT(R.string.theme_term_text, false),
    PROMPT(R.string.theme_term_prompt, false)
}

private fun normalizeHexRgb(raw: String): String? {
    val cleaned = raw.trim().removePrefix("#").uppercase()
    val expanded = when (cleaned.length) {
        3 -> buildString { cleaned.forEach { append(it); append(it) } }
        6 -> cleaned
        8 -> cleaned.takeLast(6)
        else -> return null
    }
    return if (expanded.all { it in '0'..'9' || it in 'A'..'F' }) "#$expanded" else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingsPane(vm: MainViewModel) {
    val theme by vm.uiThemeSettings.collectAsState()
    var selectedUiComp by remember { mutableStateOf(UiColorComponent.ACCENT) }
    var selectedTermComp by remember { mutableStateOf(TerminalColorComponent.BG) }

    val currentUiHex = when (selectedUiComp) {
        UiColorComponent.BG -> theme.bgDeepHex
        UiColorComponent.SURFACE -> theme.surfaceDarkHex
        UiColorComponent.CARDS -> theme.surfaceCardHex
        UiColorComponent.USER_MSG -> theme.userBubbleHex
        UiColorComponent.AI_MSG -> theme.aiBubbleHex
        UiColorComponent.ACCENT -> theme.accentHex
        UiColorComponent.BORDERS -> theme.borderHex
    }
    val currentUiAlpha = when (selectedUiComp) {
        UiColorComponent.BG -> 1.0f
        UiColorComponent.SURFACE -> theme.surfaceAlpha
        UiColorComponent.CARDS -> theme.cardAlpha
        UiColorComponent.USER_MSG -> theme.userBubbleAlpha
        UiColorComponent.AI_MSG -> theme.aiBubbleAlpha
        UiColorComponent.ACCENT -> theme.accentAlpha
        UiColorComponent.BORDERS -> theme.borderAlpha
    }

    var uiHexInput by remember(selectedUiComp, currentUiHex) { mutableStateOf(currentUiHex.uppercase()) }

    fun applyUiColor(hex: String) {
        val updated = when (selectedUiComp) {
            UiColorComponent.BG -> theme.copy(presetId = "custom", bgDeepHex = hex)
            UiColorComponent.SURFACE -> theme.copy(presetId = "custom", surfaceDarkHex = hex)
            UiColorComponent.CARDS -> theme.copy(presetId = "custom", surfaceCardHex = hex)
            UiColorComponent.USER_MSG -> theme.copy(presetId = "custom", userBubbleHex = hex)
            UiColorComponent.AI_MSG -> theme.copy(presetId = "custom", aiBubbleHex = hex)
            UiColorComponent.ACCENT -> theme.copy(presetId = "custom", accentHex = hex)
            UiColorComponent.BORDERS -> theme.copy(presetId = "custom", borderHex = hex)
        }
        vm.saveUiThemeSettings(updated)
    }

    fun applyUiAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0.25f, 1.0f)
        val updated = when (selectedUiComp) {
            UiColorComponent.BG -> theme
            UiColorComponent.SURFACE -> theme.copy(presetId = "custom", surfaceAlpha = clamped)
            UiColorComponent.CARDS -> theme.copy(presetId = "custom", cardAlpha = clamped)
            UiColorComponent.USER_MSG -> theme.copy(presetId = "custom", userBubbleAlpha = clamped)
            UiColorComponent.AI_MSG -> theme.copy(presetId = "custom", aiBubbleAlpha = clamped)
            UiColorComponent.ACCENT -> theme.copy(presetId = "custom", accentAlpha = clamped)
            UiColorComponent.BORDERS -> theme.copy(presetId = "custom", borderAlpha = clamped)
        }
        vm.saveUiThemeSettings(updated)
    }

    val currentTermHex = when (selectedTermComp) {
        TerminalColorComponent.BG -> theme.terminalBgHex
        TerminalColorComponent.TEXT -> theme.terminalTextHex
        TerminalColorComponent.PROMPT -> theme.terminalPromptHex
    }
    var termHexInput by remember(selectedTermComp, currentTermHex) { mutableStateOf(currentTermHex.uppercase()) }

    fun applyTermColor(hex: String) {
        val updated = when (selectedTermComp) {
            TerminalColorComponent.BG -> theme.copy(terminalPreset = "custom", terminalBgHex = hex)
            TerminalColorComponent.TEXT -> theme.copy(terminalPreset = "custom", terminalTextHex = hex)
            TerminalColorComponent.PROMPT -> theme.copy(terminalPreset = "custom", terminalPromptHex = hex)
        }
        vm.saveUiThemeSettings(updated)
    }

    // 1. Quick Interface Themes (1-tap presets)
    Text(
        text = stringResource(R.string.theme_section_presets),
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Bold,
        color = ForgeColors.TextPrimary
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        UI_THEME_PRESETS.forEach { preset ->
            val isSelected = theme.presetId == preset.id
            val presetAccent = ForgeColors.parseHexColor(preset.accentHex, ForgeColors.AccentBlue)
            val presetBg = ForgeColors.parseHexColor(preset.bgDeepHex, ForgeColors.BgDeep)
            FilterChip(
                selected = isSelected,
                onClick = {
                    vm.saveUiThemeSettings(
                        theme.copy(
                            presetId = preset.id,
                            bgDeepHex = preset.bgDeepHex,
                            surfaceDarkHex = preset.surfaceDarkHex,
                            surfaceAlpha = 1.0f,
                            surfaceCardHex = preset.surfaceCardHex,
                            cardAlpha = 1.0f,
                            userBubbleHex = preset.userBubbleHex,
                            userBubbleAlpha = 0.95f,
                            aiBubbleHex = preset.aiBubbleHex,
                            aiBubbleAlpha = 1.0f,
                            accentHex = preset.accentHex,
                            accentAlpha = 1.0f,
                            borderHex = preset.borderHex,
                            borderAlpha = 1.0f
                        )
                    )
                },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                    selectedBorderColor = presetAccent
                ),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ForgeColors.SurfaceCard,
                    labelColor = ForgeColors.TextPrimary,
                    selectedContainerColor = presetAccent.copy(alpha = 0.2f),
                    selectedLabelColor = ForgeColors.TextPrimary
                ),
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(presetBg, CircleShape)
                                .border(1.5.dp, presetAccent, CircleShape)
                        )
                        Text(
                            text = preset.title,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            )
        }
    }

    // 2. Live Preview Mini-Card (Real-time Background, Header Bar, Card, User Bubble, AI Bubble, Accent Button & Border)
    Surface(
        color = ForgeColors.BgDeep,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar preview
            Surface(
                color = ForgeColors.SurfaceDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedUiComp = UiColorComponent.SURFACE }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = ForgeColors.AccentBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "ForgeMind Live Preview",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForgeColors.TextPrimary
                        )
                    }
                    Surface(
                        color = ForgeColors.AccentBlue,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { selectedUiComp = UiColorComponent.ACCENT }
                    ) {
                        Text(
                            text = "Accent",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (ForgeColors.AccentBlue.luminance() > 0.55f) Color(0xFF0B0F17) else Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = ForgeColors.BorderSubtle)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedUiComp = UiColorComponent.BG }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Card preview
                Surface(
                    color = ForgeColors.SurfaceCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedUiComp = UiColorComponent.CARDS }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.theme_comp_cards),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = ForgeColors.TextPrimary
                        )
                        Text(
                            text = "${theme.surfaceCardHex.uppercase()} · ${(theme.cardAlpha * 100).roundToInt()}%",
                            fontFamily = MonoFont,
                            fontSize = 10.5.sp,
                            color = ForgeColors.TextSecondary
                        )
                    }
                }

                // User message bubble preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        color = ForgeColors.UserBubbleBg,
                        shape = RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp),
                        border = BorderStroke(1.dp, ForgeColors.AccentBlue.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                            .clickable { selectedUiComp = UiColorComponent.USER_MSG }
                    ) {
                        Text(
                            text = "${stringResource(R.string.theme_comp_user_msg)} (${theme.userBubbleHex.uppercase()})",
                            fontSize = 11.5.sp,
                            color = ForgeColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // AI message bubble preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        color = ForgeColors.AiBubbleBg,
                        shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 3.dp),
                        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp, 12.dp, 12.dp, 3.dp))
                            .clickable { selectedUiComp = UiColorComponent.AI_MSG }
                    ) {
                        Text(
                            text = "${stringResource(R.string.theme_comp_ai_msg)} (${theme.aiBubbleHex.uppercase()})",
                            fontSize = 11.5.sp,
                            color = ForgeColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    // 3. Component Color & Opacity Designer
    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.theme_section_components),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = ForgeColors.TextPrimary
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                UiColorComponent.entries.forEach { comp ->
                    val isSelected = selectedUiComp == comp
                    val compHex = when (comp) {
                        UiColorComponent.BG -> theme.bgDeepHex
                        UiColorComponent.SURFACE -> theme.surfaceDarkHex
                        UiColorComponent.CARDS -> theme.surfaceCardHex
                        UiColorComponent.USER_MSG -> theme.userBubbleHex
                        UiColorComponent.AI_MSG -> theme.aiBubbleHex
                        UiColorComponent.ACCENT -> theme.accentHex
                        UiColorComponent.BORDERS -> theme.borderHex
                    }
                    val dotColor = ForgeColors.parseHexColor(compHex, ForgeColors.AccentBlue)
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedUiComp = comp },
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                            selectedBorderColor = ForgeColors.AccentBlue
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ForgeColors.SurfaceElevated,
                            labelColor = ForgeColors.TextPrimary,
                            selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.22f),
                            selectedLabelColor = ForgeColors.TextPrimary
                        ),
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(dotColor, CircleShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                )
                                Text(
                                    text = stringResource(comp.labelRes),
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    )
                }
            }

            ColorSwatchPaletteGrid(
                selectedHex = currentUiHex,
                onSelectHex = { hex ->
                    uiHexInput = hex
                    applyUiColor(hex)
                }
            )

            if (selectedUiComp.supportsAlpha) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.theme_opacity_fmt, (currentUiAlpha * 100f).roundToInt()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ForgeColors.TextPrimary
                    )
                    Slider(
                        value = currentUiAlpha.coerceIn(0.25f, 1.0f),
                        onValueChange = { applyUiAlpha(it) },
                        valueRange = 0.25f..1.0f
                    )
                }
            }

            HexColorInputRow(
                hexText = uiHexInput,
                currentColor = ForgeColors.parseHexColor(currentUiHex, ForgeColors.AccentBlue),
                onHexTextChange = { uiHexInput = it },
                onApplyValidHex = { normalized ->
                    uiHexInput = normalized
                    applyUiColor(normalized)
                }
            )
        }
    }

    // 4. Terminal Colors Section
    Surface(
        color = ForgeColors.SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.theme_section_terminal),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = ForgeColors.TextPrimary
            )

            // 6 Terminal Presets
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TERMINAL_THEME_PRESETS.forEach { preset ->
                    val isSelected = theme.terminalPreset == preset.id
                    val pBg = ForgeColors.parseHexColor(preset.bgHex, ForgeColors.TerminalBg)
                    val pPrompt = ForgeColors.parseHexColor(preset.promptHex, ForgeColors.TerminalPrompt)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            vm.saveUiThemeSettings(
                                theme.copy(
                                    terminalPreset = preset.id,
                                    terminalBgHex = preset.bgHex,
                                    terminalBgAlpha = 1.0f,
                                    terminalTextHex = preset.textHex,
                                    terminalPromptHex = preset.promptHex
                                )
                            )
                        },
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                            selectedBorderColor = pPrompt
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ForgeColors.SurfaceElevated,
                            labelColor = ForgeColors.TextPrimary,
                            selectedContainerColor = pPrompt.copy(alpha = 0.2f),
                            selectedLabelColor = ForgeColors.TextPrimary
                        ),
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(pBg, CircleShape)
                                        .border(1.5.dp, pPrompt, CircleShape)
                                )
                                Text(
                                    text = preset.title,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    )
                }
            }

            // Live Mini Terminal Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.TerminalOutputBoxBg, RoundedCornerShape(8.dp))
                    .border(1.dp, ForgeColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "$ forgemind --agent-status",
                        fontFamily = MonoFont,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TerminalPrompt
                    )
                    Text(
                        text = "AI shell ready · PID 4120 · exit=0",
                        fontFamily = MonoFont,
                        fontSize = 11.5.sp,
                        color = ForgeColors.TerminalText
                    )
                }
            }

            // Terminal Component Chips (Background, Text, Prompt $)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TerminalColorComponent.entries.forEach { comp ->
                    val isSelected = selectedTermComp == comp
                    val compHex = when (comp) {
                        TerminalColorComponent.BG -> theme.terminalBgHex
                        TerminalColorComponent.TEXT -> theme.terminalTextHex
                        TerminalColorComponent.PROMPT -> theme.terminalPromptHex
                    }
                    val dotColor = ForgeColors.parseHexColor(compHex, ForgeColors.TerminalPrompt)
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedTermComp = comp },
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                            selectedBorderColor = ForgeColors.AccentBlue
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ForgeColors.SurfaceElevated,
                            labelColor = ForgeColors.TextPrimary,
                            selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.22f),
                            selectedLabelColor = ForgeColors.TextPrimary
                        ),
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(dotColor, CircleShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                )
                                Text(
                                    text = stringResource(comp.labelRes),
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    )
                }
            }

            ColorSwatchPaletteGrid(
                selectedHex = currentTermHex,
                onSelectHex = { hex ->
                    termHexInput = hex
                    applyTermColor(hex)
                }
            )

            if (selectedTermComp.supportsAlpha) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.theme_opacity_fmt, (theme.terminalBgAlpha * 100f).roundToInt()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ForgeColors.TextPrimary
                    )
                    Slider(
                        value = theme.terminalBgAlpha.coerceIn(0.25f, 1.0f),
                        onValueChange = { alpha ->
                            vm.saveUiThemeSettings(
                                theme.copy(
                                    terminalPreset = "custom",
                                    terminalBgAlpha = alpha.coerceIn(0.25f, 1.0f)
                                )
                            )
                        },
                        valueRange = 0.25f..1.0f
                    )
                }
            }

            HexColorInputRow(
                hexText = termHexInput,
                currentColor = ForgeColors.parseHexColor(currentTermHex, ForgeColors.TerminalPrompt),
                onHexTextChange = { termHexInput = it },
                onApplyValidHex = { normalized ->
                    termHexInput = normalized
                    applyTermColor(normalized)
                }
            )
        }
    }

    // 5. Reset All Colors to Default
    OutlinedButton(
        border = BorderStroke(1.dp, ForgeColors.BorderHighlight),
        onClick = { vm.resetUiThemeSettings() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.theme_reset_all_btn),
            color = ForgeColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchPaletteGrid(
    selectedHex: String,
    onSelectHex: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MAJOR_THEME_SWATCHES.forEach { swatch ->
            val swatchColor = ForgeColors.parseHexColor(swatch.hex, ForgeColors.AccentBlue)
            val isSelected = selectedHex.equals(swatch.hex, ignoreCase = true)
            val checkTint = if (swatchColor.luminance() > 0.55f) Color(0xFF0B0F17) else Color.White
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(swatchColor, CircleShape)
                    .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) ForgeColors.AccentBlue else Color.White.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
                    .clickable { onSelectHex(swatch.hex) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = swatch.name,
                        tint = checkTint,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HexColorInputRow(
    hexText: String,
    currentColor: Color,
    onHexTextChange: (String) -> Unit,
    onApplyValidHex: (String) -> Unit
) {
    val normalizedCandidate = normalizeHexRgb(hexText)
    val previewColor = if (normalizedCandidate != null) {
        ForgeColors.parseHexColor(normalizedCandidate, currentColor)
    } else {
        currentColor
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(previewColor, RoundedCornerShape(8.dp))
                .border(1.dp, ForgeColors.BorderHighlight, RoundedCornerShape(8.dp))
        )
        OutlinedTextField(
            value = hexText,
            onValueChange = { raw ->
                val filtered = raw.filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(9)
                onHexTextChange(filtered)
            },
            label = { Text(stringResource(R.string.theme_hex_label)) },
            placeholder = { Text("#4F8CFF") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        ForgeActionPill(
            text = stringResource(R.string.theme_apply_hex),
            accentColor = ForgeColors.AccentBlue,
            enabled = normalizedCandidate != null,
            minHeight = 44.dp,
            onClick = {
                normalizedCandidate?.let(onApplyValidHex)
            }
        )
    }
}
