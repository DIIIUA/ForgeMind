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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.forgemind.app.R
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchEngineType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.i18n.AppLanguage
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ModelSelectorButton
import com.forgemind.app.ui.components.copyToClipboard
import com.forgemind.app.ui.formatSafTreeLabelLocalized
import com.forgemind.app.ui.localizedLabel
import com.forgemind.app.ui.localizedName
import com.forgemind.app.ui.localizedSubtitle
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

private enum class SettingsTab(@StringRes val labelRes: Int) {
    PROVIDERS(R.string.settings_tab_providers),
    ENVIRONMENTS(R.string.settings_tab_envs),
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
                            label = { Text(stringResource(tab.labelRes), fontSize = 11.5.sp) }
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
                    SettingsTab.VOICE -> VoiceSettingsPane(vm)
                    SettingsTab.MCP -> McpSettingsPane(vm)
                    SettingsTab.AGENT_WEB -> AgentAndWebSettingsPane(vm, onExportZip, onImportZip)
                }
            }
        }
    }
}

@Composable
private fun ProvidersSettingsPane(vm: MainViewModel) {
    val context = LocalContext.current
    val providerConfigs by vm.providerConfigs.collectAsState()
    val scannedModels by vm.scannedModels.collectAsState()
    val activeProviderId by vm.activeProviderId.collectAsState()
    var selectedProvId by remember(activeProviderId) { mutableStateOf(activeProviderId) }

    val saved = providerConfigs[selectedProvId] ?: ProviderConfig(selectedProvId)
    var keysRaw by remember(selectedProvId, saved) { mutableStateOf(saved.apiKeysRaw) }
    var baseUrl by remember(selectedProvId, saved) { mutableStateOf(saved.baseUrl) }
    var modelId by remember(selectedProvId, saved) { mutableStateOf(saved.modelId) }
    var headersJson by remember(selectedProvId, saved) { mutableStateOf(saved.customHeadersJson) }
    var contextWin by remember(selectedProvId, saved) { mutableStateOf(saved.contextWindow.toString()) }
    var temperature by remember(selectedProvId, saved) { mutableFloatStateOf(saved.temperature) }
    var enableThinking by remember(selectedProvId, saved) { mutableStateOf(saved.enableThinking) }
    var autoRotate by remember(selectedProvId, saved) { mutableStateOf(saved.autoRotateKeys) }
    var scanStatus by remember(selectedProvId) { mutableStateOf<String?>(null) }
    var isScanning by remember(selectedProvId) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ProviderType.entries.forEach { p ->
            val configured = (providerConfigs[p.id]?.parsedKeys()?.isNotEmpty() == true)
            FilterChip(
                selected = selectedProvId == p.id,
                onClick = { selectedProvId = p.id },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (configured) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(ForgeColors.AccentEmerald, CircleShape)
                            )
                        }
                        Text(p.localizedName(context), fontSize = 11.5.sp)
                    }
                }
            )
        }
    }

    val pType = ProviderType.fromId(selectedProvId)
    Text(pType.localizedSubtitle(context), fontSize = 12.sp, color = ForgeColors.TextSecondary)

    OutlinedTextField(
        value = keysRaw,
        onValueChange = { keysRaw = it },
        label = { Text(stringResource(R.string.settings_api_keys_label)) },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("Base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    val triggerModelScan = {
        val draft = ProviderConfig(
            providerId = selectedProvId,
            apiKeysRaw = keysRaw,
            baseUrl = baseUrl,
            modelId = modelId,
            customHeadersJson = headersJson
        )
        isScanning = true
        vm.scanModelsForProvider(draft) {
            isScanning = false
            scanStatus = it
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            label = { Text("Model ID") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            enabled = !isScanning,
            onClick = triggerModelScan
        ) {
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (isScanning) stringResource(R.string.settings_scanning_short) else stringResource(R.string.settings_scan_short),
                fontSize = 12.sp
            )
        }
    }

    scanStatus?.let { msg ->
        val isErr = msg.startsWith("Error", ignoreCase = true)
        Text(
            msg,
            fontSize = 11.5.sp,
            color = if (isErr) ForgeColors.DangerRed else ForgeColors.AccentEmerald
        )
    }
    val models = scannedModels[selectedProvId].orEmpty()
    ModelSelectorButton(
        models = models,
        currentModelId = modelId,
        isScanning = isScanning,
        onScanIfNeeded = triggerModelScan,
        onSelectModel = { selectedId ->
            modelId = selectedId
            models.firstOrNull { it.id == selectedId }
                ?.contextLength
                ?.takeIf { it > 0 }
                ?.let { contextWin = it.toString() }
        }
    )

    OutlinedTextField(
        value = headersJson,
        onValueChange = { headersJson = it },
        label = { Text(stringResource(R.string.settings_custom_headers_json)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = contextWin,
        onValueChange = { contextWin = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.settings_context_window)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text(
        stringResource(R.string.settings_temperature_fmt, String.format(java.util.Locale.US, "%.2f", temperature)),
        fontSize = 12.5.sp,
        color = ForgeColors.TextPrimary
    )
    Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f)

    SwitchRow(stringResource(R.string.settings_enable_thinking), enableThinking) { enableThinking = it }
    SwitchRow(stringResource(R.string.settings_auto_rotate_keys), autoRotate) { autoRotate = it }

    Button(
        onClick = {
            vm.saveProviderConfig(
                ProviderConfig(
                    providerId = selectedProvId,
                    apiKeysRaw = keysRaw,
                    baseUrl = baseUrl.ifBlank { pType.defaultBaseUrl },
                    modelId = modelId.ifBlank { pType.defaultModel },
                    customHeadersJson = headersJson.ifBlank { "{}" },
                    contextWindow = contextWin.toIntOrNull() ?: pType.defaultContextWindow,
                    temperature = temperature,
                    enableThinking = enableThinking,
                    autoRotateKeys = autoRotate
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.settings_save_provider))
    }
}

@Composable
private fun VoiceSettingsPane(vm: MainViewModel) {
    val context = LocalContext.current
    val saved by vm.voiceSettings.collectAsState()
    val voices by vm.xttsVoices.collectAsState()
    val defaultVoice by vm.xttsDefaultVoice.collectAsState()

    var sttProvider by remember(saved) { mutableStateOf(saved.sttProvider) }
    var sttBaseUrl by remember(saved) { mutableStateOf(saved.sttBaseUrl) }
    var sttApiKey by remember(saved) { mutableStateOf(saved.sttApiKey) }
    var sttModel by remember(saved) { mutableStateOf(saved.sttModel) }
    var sttLanguage by remember(saved) { mutableStateOf(saved.sttLanguage) }
    var autoSendVoice by remember(saved) { mutableStateOf(saved.autoSendAfterVoice) }
    var sttFallback by remember(saved) { mutableStateOf(saved.sttAutoFallback) }

    var ttsProvider by remember(saved) { mutableStateOf(saved.ttsProvider) }
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
        autoSpeakReplies = autoSpeak,
        skipCodeInTts = skipCode
    )

    SectionQuickGuideCard(
        title = stringResource(R.string.guide_voice_title),
        subtitle = stringResource(R.string.guide_voice_subtitle),
        body = stringResource(R.string.guide_voice_body)
    )

    Text(stringResource(R.string.settings_stt_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentBlue)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val draft = currentDraft()
                    vm.testXttsAndLoadVoices(draft) { status ->
                        xttsTestStatus = status.message
                        val def = status.defaultVoice ?: status.voices.firstOrNull()
                        if (def != null && (activeVoice.isBlank() || activeVoice.equals("__default__", ignoreCase = true))) {
                            setActiveVoice(def)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.settings_check_xtts_fmt, voices.size), fontSize = 12.sp)
            }
            OutlinedButton(
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
            ) {
                Text(stringResource(R.string.settings_list_short), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val draft = currentDraft()
                    vm.saveVoiceSettings(draft)
                    vm.speakMessage("test_xtts", testPhrase)
                }
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.action_test), fontSize = 12.sp)
            }
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
                color = if (st.contains("ready", ignoreCase = true) || !st.contains("err", ignoreCase = true)) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
private fun McpSettingsPane(vm: MainViewModel) {
    val servers by vm.mcpServers.collectAsState()
    val sshProfiles by vm.sshProfiles.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerEntity?>(null) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { vm.testMcpServer(srv.id) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.action_check), fontSize = 11.5.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            editingServer = srv
                            showEditor = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_edit), fontSize = 11.5.sp)
                    }
                    IconButton(onClick = { vm.deleteMcpServer(srv.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = ForgeColors.DangerRed, modifier = Modifier.size(18.dp))
                    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!p.isDefault) {
                        TextButton(
                            onClick = { vm.activateSshProfile(p.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(stringResource(R.string.ssh_primary_btn), fontSize = 11.5.sp) }
                    }
                    TextButton(
                        onClick = {
                            isCreatingNew = false
                            editingId = p.id
                            testResult = null
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text(if (isEditing) stringResource(R.string.ssh_editing_btn) else stringResource(R.string.action_configure), fontSize = 11.5.sp) }
                    if (profiles.size > 1) {
                        IconButton(onClick = { vm.deleteSshProfile(p.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = ForgeColors.DangerRed, modifier = Modifier.size(16.dp))
                        }
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
                    selected = !useCloudflareAccess && (name.contains("External", ignoreCase = true) || (!name.contains("termux", true) && port == "2222")),
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

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        testResult = context.getString(R.string.ssh_connecting)
                        vm.testSshConnection(draft()) { testResult = it }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text(stringResource(R.string.action_test), fontSize = 11.5.sp) }
                Button(
                    onClick = {
                        val saved = draft()
                        vm.saveSshProfile(saved, makeDefault = false)
                        isCreatingNew = false
                        editingId = saved.id
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_save), fontSize = 11.5.sp) }
                OutlinedButton(
                    onClick = {
                        val saved = draft()
                        vm.saveAndActivateSshProfile(saved)
                        isCreatingNew = false
                        editingId = saved.id
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text(stringResource(R.string.ssh_primary_btn), fontSize = 11.5.sp) }
            }

            testResult?.let { res ->
                Text(
                    res,
                    fontFamily = MonoFont,
                    fontSize = 11.5.sp,
                    color = when {
                        res.startsWith("Connected") -> ForgeColors.AccentEmerald
                        res.startsWith("Error") -> ForgeColors.DangerRed
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
        OutlinedButton(onClick = onExportZip, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_export_zip)) }
        OutlinedButton(onClick = onImportZip, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_import_zip)) }
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
