package com.forgemind.app.ui.screens.settings

import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ModelSelectorButton
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

private enum class SettingsTab(val label: String) {
    PROVIDERS("Провайдеры AI"),
    ENVIRONMENTS("Среды ИИ (SAF / SSH)"),
    VOICE("Голос XTTS/STT"),
    MCP("MCP-серверы"),
    AGENT_WEB("Веб и Агент")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
    onPickSafFolder: () -> Unit
) {
    var activeTab by remember { mutableStateOf(SettingsTab.PROVIDERS) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.BgDeep)
    ) {
        Surface(color = ForgeColors.SurfaceDark) {
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
                        label = { Text(tab.label, fontSize = 11.5.sp) }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (activeTab) {
                SettingsTab.PROVIDERS -> ProvidersSettingsPane(vm)
                SettingsTab.ENVIRONMENTS -> EnvironmentsSettingsPane(vm, onPickSafFolder)
                SettingsTab.VOICE -> VoiceSettingsPane(vm)
                SettingsTab.MCP -> McpSettingsPane(vm)
                SettingsTab.AGENT_WEB -> AgentAndWebSettingsPane(vm, onExportZip, onImportZip)
            }
        }
    }
}

@Composable
private fun ProvidersSettingsPane(vm: MainViewModel) {
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
                label = { Text(if (configured) "● ${p.displayName}" else p.displayName, fontSize = 11.5.sp) }
            )
        }
    }

    val pType = ProviderType.fromId(selectedProvId)
    Text(pType.subtitle, fontSize = 12.sp, color = ForgeColors.TextSecondary)

    OutlinedTextField(
        value = keysRaw,
        onValueChange = { keysRaw = it },
        label = { Text("API-ключи (по одному на строку для авто-ротации)") },
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
            Text(if (isScanning) "Скан..." else "Скан", fontSize = 12.sp)
        }
    }

    scanStatus?.let { msg ->
        val isErr = msg.startsWith("Ошибка", ignoreCase = true)
        Text(
            msg,
            fontSize = 11.5.sp,
            color = if (isErr) ForgeColors.DangerRed else ForgeColors.AccentEmerald
        )
    }
    val models = scannedModels[selectedProvId].orEmpty()
    ModelSelectorButton(
        models = models,
        selectedModelId = modelId,
        scanning = isScanning,
        onRequestScan = triggerModelScan,
        onSelectModel = { model ->
            modelId = model.id
            model.contextLength?.takeIf { it > 0 }?.let { contextWin = it.toString() }
        }
    )

    OutlinedTextField(
        value = headersJson,
        onValueChange = { headersJson = it },
        label = { Text("Кастомные заголовки JSON (напр. CF-Access)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = contextWin,
        onValueChange = { contextWin = it.filter { c -> c.isDigit() } },
        label = { Text("Окно контекста (токенов)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text("Температура: ${String.format(java.util.Locale.US, "%.2f", temperature)}", fontSize = 12.5.sp, color = ForgeColors.TextPrimary)
    Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f)

    SwitchRow("Thinking / Reasoning режим", enableThinking) { enableThinking = it }
    SwitchRow("Авто-ротация ключей при 429/лимите", autoRotate) { autoRotate = it }

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
        Text("Сохранить провайдер")
    }
}

@Composable
private fun VoiceSettingsPane(vm: MainViewModel) {
    val saved by vm.voiceSettings.collectAsState()
    val voices by vm.xttsVoices.collectAsState()
    val defaultVoice by vm.xttsDefaultVoice.collectAsState()

    var sttProvider by remember(saved) { mutableStateOf(saved.sttProvider) }
    var sttBaseUrl by remember(saved) { mutableStateOf(saved.sttBaseUrl) }
    var sttApiKey by remember(saved) { mutableStateOf(saved.sttApiKey) }
    var sttModel by remember(saved) { mutableStateOf(saved.sttModel) }
    var sttLanguage by remember(saved) { mutableStateOf(saved.sttLanguage) }
    var autoSendVoice by remember(saved) { mutableStateOf(saved.autoSendAfterVoice) }

    var ttsProvider by remember(saved) { mutableStateOf(saved.ttsProvider) }
    var xttsBaseUrl by remember(saved) { mutableStateOf(saved.xttsBaseUrl) }
    var xttsVoice by remember(saved) {
        mutableStateOf(
            saved.xttsVoice.takeUnless { it.equals("Venice_selected", ignoreCase = true) } ?: ""
        )
    }
    var xttsExternalUrl by remember(saved) { mutableStateOf(saved.xttsExternalUrl) }
    var xttsExternalVoice by remember(saved) {
        mutableStateOf(
            saved.xttsExternalVoice.takeUnless { it.equals("Venice_selected", ignoreCase = true) } ?: ""
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

    Text("Распознавание речи (STT)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentBlue)
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
                label = { Text(s.label.substringBefore(" ("), fontSize = 11.5.sp) }
            )
        }
    }

    if (sttProvider != SttProviderType.ANDROID_SPEECH) {
        OutlinedTextField(
            value = sttApiKey,
            onValueChange = { sttApiKey = it },
            label = { Text("API-ключ STT (Groq / OpenAI Whisper)") },
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
                label = { Text("Модель STT") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = sttLanguage,
                onValueChange = { sttLanguage = it },
                label = { Text("Язык") },
                singleLine = true,
                modifier = Modifier.width(86.dp)
            )
        }
    }
    SwitchRow("Сразу отправлять после диктовки", autoSendVoice) { autoSendVoice = it }

    HorizontalDivider(color = ForgeColors.BorderSubtle)

    Text("Синтез речи (TTS)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentEmerald)
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
                label = { Text(t.label.substringBefore(" ("), fontSize = 11.5.sp) }
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
                    if (def != null && (activeVoice.isBlank() || activeVoice.equals("Venice_selected", ignoreCase = true))) {
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
                    if (isExternal) "XTTS внешний URL (Cloudflare: https://...)"
                    else "XTTS локальный URL (Wi-Fi / LAN, напр. http://192.168.1.100:8020)"
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
            label = { Text(if (isExternal) "Голос внешнего XTTS" else "Голос локального XTTS (по умолчанию модели)") },
            placeholder = { Text(defaultVoice ?: "Загрузится голос модели") },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (voices.isEmpty()) {
                            vm.testXttsAndLoadVoices(currentDraft()) { status ->
                                xttsTestStatus = status.message
                                val def = status.defaultVoice ?: status.voices.firstOrNull()
                                if (def != null && (activeVoice.isBlank() || activeVoice.equals("Venice_selected", ignoreCase = true))) {
                                    setActiveVoice(def)
                                }
                                if (status.voices.isNotEmpty()) showVoicePicker = true
                            }
                        } else {
                            showVoicePicker = true
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Выбрать голос")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val draft = currentDraft()
                    vm.testXttsAndLoadVoices(draft) { status ->
                        xttsTestStatus = status.message
                        val def = status.defaultVoice ?: status.voices.firstOrNull()
                        if (def != null && (activeVoice.isBlank() || activeVoice.equals("Venice_selected", ignoreCase = true))) {
                            setActiveVoice(def)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Проверить XTTS (${voices.size})", fontSize = 12.sp)
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
                Text("Список", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val draft = currentDraft()
                    vm.saveVoiceSettings(draft)
                    vm.speakMessage("test_xtts", "Привет! Проверка голоса из ForgeMind.")
                }
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                Text("Тест", fontSize = 12.sp)
            }
        }

        if (showVoicePicker) {
            val filteredVoices = remember(voices, voiceQuery) {
                val q = voiceQuery.trim()
                voices.filter { v ->
                    !v.equals("Venice_selected", ignoreCase = true) &&
                        (q.isBlank() || v.contains(q, ignoreCase = true))
                }.distinct()
            }
            AlertDialog(
                onDismissRequest = { showVoicePicker = false },
                title = { Text("Голоса модели XTTS (${filteredVoices.size})") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = voiceQuery,
                            onValueChange = { voiceQuery = it },
                            placeholder = { Text("Поиск голоса...") },
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
                                                text = "по умолчанию",
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
                    TextButton(onClick = { showVoicePicker = false }) { Text("Закрыть") }
                }
            )
        }
        xttsTestStatus?.let { st ->
            Text(
                st,
                fontSize = 12.sp,
                color = if (st.contains("готов", ignoreCase = true)) ForgeColors.AccentEmerald else ForgeColors.DangerRed
            )
        }

        Text("Высота тона (Pitch): ${xttsPitch.toInt()} полутонов", fontSize = 12.5.sp, color = ForgeColors.TextPrimary)
        Slider(value = xttsPitch, onValueChange = { xttsPitch = it }, valueRange = -6f..12f)
        SwitchRow("Сохранять темп при сдвиге тона (keep_tempo)", xttsKeepTempo) { xttsKeepTempo = it }

        if (isExternal) {
            OutlinedTextField(
                value = xttsCfId,
                onValueChange = { xttsCfId = it },
                label = { Text("CF-Access-Client-Id (если защищён Cloudflare Access)") },
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
            label = { Text("Bearer Token (опционально)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Text("Скорость речи (темп): ${String.format(java.util.Locale.US, "%.2fx", ttsSpeed)}", fontSize = 12.5.sp, color = ForgeColors.TextPrimary)
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

    SwitchRow("Автоматически озвучивать ответы ИИ", autoSpeak) { autoSpeak = it }
    SwitchRow("Пропускать блоки кода при озвучке", skipCode) { skipCode = it }

    Button(
        onClick = { vm.saveVoiceSettings(currentDraft()) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Сохранить голосовые настройки")
    }
}

private enum class McpAuthMode(val label: String) {
    NONE("Нет"),
    BEARER("Bearer"),
    BASIC("Логин/Пароль"),
    API_KEY("API Key")
}

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
        Text("Подключённые MCP-серверы", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
        Button(onClick = {
            editingServer = null
            showEditor = true
        }) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Добавить")
        }
    }

    Text(
        "Поддерживаются Streamable HTTP, Legacy HTTP + SSE (Bearer / Basic / API Key / Cloudflare Access) и SSH Stdio.",
        fontSize = 12.sp,
        color = ForgeColors.TextSecondary
    )

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
                        val statusText = when {
                            !srv.enabled -> "Отключён вручную • Кэш инструментов: $toolsCount"
                            !srv.lastError.isNullOrBlank() -> "Ошибка подключения • Инструментов: $toolsCount"
                            srv.lastConnectedAt != null -> "Подключён • Инструментов: $toolsCount"
                            else -> "Ожидает подключения • Инструментов: $toolsCount"
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
                    Text("Ошибка: ${srv.lastError}", fontSize = 11.5.sp, color = ForgeColors.DangerRed)
                } else if (!srv.enabled && !srv.lastError.isNullOrBlank()) {
                    Text("Прошлая ошибка (сервер выключен): ${srv.lastError}", fontSize = 11.sp, color = ForgeColors.TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { vm.testMcpServer(srv.id) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Проверить", fontSize = 11.5.sp)
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
                        Text("Изменить", fontSize = 11.5.sp)
                    }
                    IconButton(onClick = { vm.deleteMcpServer(srv.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Delete, "Удалить", tint = ForgeColors.DangerRed, modifier = Modifier.size(18.dp))
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

    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var endpoint by remember(initial?.id) { mutableStateOf(initial?.endpoint ?: "") }
    var transport by remember(initial?.id) {
        mutableStateOf(
            runCatching { McpTransport.valueOf(initial?.transport ?: "") }.getOrDefault(McpTransport.STREAMABLE_HTTP)
        )
    }
    var authMode by remember(initial?.id) { mutableStateOf(initMode) }
    var bearerToken by remember(initial?.id) { mutableStateOf(initBearer) }
    var basicUser by remember(initial?.id) { mutableStateOf(initBasicUser) }
    var basicPass by remember(initial?.id) { mutableStateOf(initBasicPass) }
    var apiKeyHeader by remember(initial?.id) { mutableStateOf(initApiKeyHeader) }
    var apiKeyValue by remember(initial?.id) { mutableStateOf(initApiKeyVal) }
    var useCfAccess by remember(initial?.id) { mutableStateOf(initCfId.isNotBlank() || initCfSecret.isNotBlank()) }
    var cfId by remember(initial?.id) { mutableStateOf(initCfId) }
    var cfSecret by remember(initial?.id) { mutableStateOf(initCfSecret) }
    var sshServerId by remember(initial?.id) { mutableStateOf(initSshSrv) }
    var extraHeadersJson by remember(initial?.id) { mutableStateOf(initExtraJson) }

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
                if (initial == null) "Добавить MCP-сервер" else "Настройка MCP: ${initial.name}",
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
                    label = { Text("Название сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Протокол / Транспорт", fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
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
                                McpTransport.SSH_STDIO -> "Команда запуска (напр. python3 mcp.py)"
                                McpTransport.HTTP_SSE -> "URL SSE-потока (напр. https://host/sse)"
                                McpTransport.STREAMABLE_HTTP -> "URL эндпоинта (напр. https://host/mcp)"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (transport == McpTransport.SSH_STDIO) {
                    Text("Целевой SSH-сервер", fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = sshServerId.isBlank(),
                            onClick = { sshServerId = "" },
                            label = { Text("Основной SSH", fontSize = 11.sp) }
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
                    Text("Авторизация", fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        McpAuthMode.entries.forEach { m ->
                            FilterChip(
                                selected = authMode == m,
                                onClick = { authMode = m },
                                label = { Text(m.label, fontSize = 11.sp) }
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
                                label = { Text("Логин (Username)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = basicPass,
                                onValueChange = { basicPass = it },
                                label = { Text("Пароль (Password)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        McpAuthMode.API_KEY -> {
                            OutlinedTextField(
                                value = apiKeyHeader,
                                onValueChange = { apiKeyHeader = it },
                                label = { Text("Имя заголовка (напр. X-API-Key)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = apiKeyValue,
                                onValueChange = { apiKeyValue = it },
                                label = { Text("Значение ключа / токена") },
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
                        label = { Text("Доп. заголовки JSON (опционально)") },
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
                        onSave(initial?.id, name, transport, endpoint, buildCombinedHeadersJson())
                    }
                }
            ) {
                Text(if (initial == null) "Подключить" else "Сохранить и проверить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun EnvironmentsSettingsPane(
    vm: MainViewModel,
    onPickSafFolder: () -> Unit
) {
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
                name = "SSH-сервер (2222)",
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
        name = name.trim().ifBlank { "SSH-сервер (${port.toIntOrNull() ?: 2222})" },
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
            "Все включённые среды работают одновременно в одном чате ИИ: домашняя папка, папка Android (SAF), локальный Termux и внешний SSH-сервер.",
            fontSize = 12.sp,
            color = ForgeColors.TextSecondary,
            modifier = Modifier.padding(12.dp)
        )
    }

    // ---- Card 1: Android SAF Folder for AI ----
    Card(
        colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.FolderOpen, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(20.dp))
                Text("Папка Android (SAF) для ИИ", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
            }
            Text(
                if (safTreeUri.isNullOrBlank()) {
                    "Текущая папка: не выбрана (можно выбрать, например, Movies/AIApp)"
                } else {
                    "Текущая папка: ${WorkspaceManager.formatSafTreeLabel(safTreeUri)}"
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
                        if (safTreeUri.isNullOrBlank()) "Выбрать папку (Movies/AIApp)" else "Сменить папку SAF",
                        fontSize = 12.sp
                    )
                }
                if (!safTreeUri.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { vm.clearSafFolderUri() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Сбросить", fontSize = 12.sp)
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
        Text("SSH-серверы для ИИ", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
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
            Text("Добавить сервер", fontSize = 12.sp)
        }
    }

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
                                Text("Основной (по умолчанию)", fontSize = 10.5.sp, color = ForgeColors.AccentEmerald)
                            } else if (p.enabled) {
                                Text("Включён для ИИ", fontSize = 10.5.sp, color = ForgeColors.AccentBlue)
                            }
                        }
                    }
                    Switch(checked = p.enabled, onCheckedChange = { vm.toggleSshProfileEnabled(p, it) })
                }
                val userLabel = p.username.ifBlank { "<логин не задан>" }
                val wdLabel = if (p.defaultWorkDir.isNotBlank()) " • ${p.defaultWorkDir}" else ""
                Text(
                    "$userLabel@${p.host.ifBlank { "<хост>" }}:${p.port}$wdLabel",
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!p.isDefault) {
                        TextButton(
                            onClick = { vm.activateSshProfile(p.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("Основной", fontSize = 11.5.sp) }
                    }
                    TextButton(
                        onClick = {
                            isCreatingNew = false
                            editingId = p.id
                            testResult = null
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text(if (isEditing) "Редактируется" else "Настроить", fontSize = 11.5.sp) }
                    if (profiles.size > 1) {
                        IconButton(onClick = { vm.deleteSshProfile(p.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Delete, "Удалить", tint = ForgeColors.DangerRed, modifier = Modifier.size(16.dp))
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
                if (isCreatingNew) "Новый SSH-сервер" else "Настройка выбранного SSH-сервера",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ForgeColors.AccentBlue
            )

            SwitchRow("Использовать этот SSH-сервер в едином чате", enabled) { enabled = it }

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
                        if (name.startsWith("Termux") || name.startsWith("SSH-сервер") || name.isBlank() || name == "SSH внешний") {
                            name = "SSH внешний (CF)"
                        }
                        if (workDir == "/data/data/com.termux/files/home") workDir = ""
                        fillCfTokensFromMcp(overwrite = false)
                    },
                    label = { Text("Cloudflare SSH (:443)", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = !useCloudflareAccess && (name.contains("внешн", ignoreCase = true) || (!name.contains("termux", true) && port == "2222")),
                    onClick = {
                        useCloudflareAccess = false
                        port = "2222"
                        if (name.startsWith("Termux") || name.startsWith("SSH-сервер") || name.isBlank() || name == "SSH внешний (CF)") {
                            name = "SSH внешний"
                        }
                        if (workDir == "/data/data/com.termux/files/home") workDir = ""
                    },
                    label = { Text("SSH внешний (:2222)", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = !useCloudflareAccess && name.contains("termux", ignoreCase = true) && port == "2222",
                    onClick = {
                        useCloudflareAccess = false
                        host = host.ifBlank { "127.0.0.1" }
                        port = "2222"
                        if (name.startsWith("SSH") || name.isBlank()) name = "Termux (локальный)"
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
                label = { Text("Название сервера") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = {
                        Text(
                            if (useCloudflareAccess) "Hostname CF (напр. ssh.example.com)"
                            else "Host / IP (напр. 127.0.0.1)"
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
            SwitchRow("Туннель Cloudflare Access (cloudflared ssh)", useCloudflareAccess) { checked ->
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
                            "Заполнить CF-Access из MCP (${mcpCfCreds.sourceServerName})"
                        } else {
                            "В MCP приложения не найдены заголовки CF-Access"
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
                                "CF-Access-Client-Id (пусто = из MCP)"
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
                                "CF-Access-Client-Secret (пусто = из MCP)"
                            } else {
                                "CF-Access-Client-Secret"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Встроенный WebSocket-туннель wss://host/ без бинарника cloudflared. Если поля токенов пустые, они автоматически подтянутся из MCP в приложении. Ниже укажите логин и пароль/ключ самой SSH-оболочки.",
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username (обязательно для SSH)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль (если не по ключу)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = privateKeyPem,
                onValueChange = { privateKeyPem = it },
                label = { Text("Приватный SSH-ключ OpenSSH PEM (опционально)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase ключа (если есть)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = workDir,
                onValueChange = { workDir = it },
                label = { Text("Рабочий каталог (пусто = домашняя папка SSH)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        testResult = "Подключение..."
                        vm.testSshConnection(draft()) { testResult = it }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("Тест", fontSize = 11.5.sp) }
                Button(
                    onClick = {
                        val saved = draft()
                        vm.saveSshProfile(saved, makeDefault = false)
                        isCreatingNew = false
                        editingId = saved.id
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить", fontSize = 11.5.sp) }
                OutlinedButton(
                    onClick = {
                        val saved = draft()
                        vm.saveAndActivateSshProfile(saved)
                        isCreatingNew = false
                        editingId = saved.id
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("Основной", fontSize = 11.5.sp) }
            }

            testResult?.let { res ->
                Text(
                    res,
                    fontFamily = MonoFont,
                    fontSize = 11.5.sp,
                    color = when {
                        res.startsWith("Подключено") -> ForgeColors.AccentEmerald
                        res.startsWith("Ошибка") -> ForgeColors.DangerRed
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
    var parallelCalls by remember(agentSaved) { mutableStateOf(agentSaved.parallelToolCalls) }
    var autoCompact by remember(agentSaved) { mutableStateOf(agentSaved.autoCompactContext) }
    var compactPct by remember(agentSaved) { mutableStateOf(agentSaved.compactThresholdPercent.toString()) }
    var thinkEveryCalls by remember(agentSaved) { mutableStateOf(agentSaved.thinkEveryToolCalls.toString()) }
    var maxTurnsText by remember(agentSaved) { mutableStateOf(agentSaved.maxTurns.toString()) }
    var autoSnapshot by remember(agentSaved) { mutableStateOf(agentSaved.autoSnapshotBeforeEdit) }
    var globalPrompt by remember(agentSaved) { mutableStateOf(agentSaved.globalSystemPrompt) }

    Text("Веб-поиск и JS-скрапинг", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentBlue)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SearchEngineType.entries.forEach { e ->
            FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e.label, fontSize = 11.5.sp) })
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
        SearchEngineType.DUCKDUCKGO -> Text("DuckDuckGo работает сразу без ключа.", fontSize = 12.sp, color = ForgeColors.TextSecondary)
    }
    SwitchRow("Headless WebView JS-рендеринг для SPA", jsScrape) { jsScrape = it }

    Button(onClick = {
        vm.saveSearchSettings(
            SearchSettings(engine, tavilyKey, braveKey, googleKey, googleCx, searxUrl, jsScrape)
        )
    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки поиска") }

    HorizontalDivider(color = ForgeColors.BorderSubtle)

    Text("Автономный Агент и Память", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ForgeColors.AccentEmerald)
    SwitchRow("Разрешить инструменты (SSH, Файлы, Веб, MCP)", enableTools) { enableTools = it }
    SwitchRow("Авто-одобрять безопасные операции (чтение/поиск)", autoApproveSafe) { autoApproveSafe = it }
    SwitchRow("Полный автопилот (одобрять запись/shell без запроса)", autoApproveAll) { autoApproveAll = it }
    SwitchRow("Параллельный вызов независимых инструментов", parallelCalls) { parallelCalls = it }
    SwitchRow("Авто-сжатие контекста при переполнении", autoCompact) { autoCompact = it }
    SwitchRow("Сохранять Diff-снапшоты перед изменением файлов", autoSnapshot) { autoSnapshot = it }

    OutlinedTextField(
        value = thinkEveryCalls,
        onValueChange = { thinkEveryCalls = it.filter { c -> c.isDigit() } },
        label = { Text("Пауза на размышление каждые N команд (0 = выкл)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = maxTurnsText,
        onValueChange = { maxTurnsText = it.filter { c -> c.isDigit() } },
        label = { Text("Макс. ходов агента за запрос (предохранитель)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = compactPct,
        onValueChange = { compactPct = it.filter { c -> c.isDigit() } },
        label = { Text("Порог авто-сжатия (%)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = globalPrompt,
        onValueChange = { globalPrompt = it },
        label = { Text("Глобальный системный промпт (для всех чатов)") },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )

    Button(onClick = {
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
                globalSystemPrompt = globalPrompt
            )
        )
    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки агента") }

    HorizontalDivider(color = ForgeColors.BorderSubtle)
    Text("Архив базы данных (.zip)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ForgeColors.TextPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onExportZip, modifier = Modifier.weight(1f)) { Text("Экспорт ZIP") }
        OutlinedButton(onClick = onImportZip, modifier = Modifier.weight(1f)) { Text("Импорт ZIP") }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontSize = 13.sp, color = ForgeColors.TextPrimary, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
