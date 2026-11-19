package com.forgemind.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.forgemind.app.accessibility.ForgeAccessibilityService
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.McpHostSettings
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.UiThemeSettings
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.ui.theme.ForgeColors
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "forgemind_prefs")

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val secretPrefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "forgemind_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("forgemind_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    private object Keys {
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val PROVIDER_CONFIGS = stringPreferencesKey("provider_configs_json")
        val HIDDEN_BUILTIN_PROVIDERS = stringPreferencesKey("hidden_builtin_providers_json")
        val VOICE_SETTINGS = stringPreferencesKey("voice_settings_json")
        val SEARCH_SETTINGS = stringPreferencesKey("search_settings_json")
        val AGENT_SETTINGS = stringPreferencesKey("agent_settings_json")
        val MCP_HOST_SETTINGS = stringPreferencesKey("mcp_host_settings_json")
        val UI_THEME_SETTINGS = stringPreferencesKey("ui_theme_settings_json")
        val CONNECTION_STRIP_EXPANDED = booleanPreferencesKey("connection_strip_expanded")
        val EDITOR_THEME = stringPreferencesKey("editor_theme")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val FAVORITE_MODELS_JSON = stringPreferencesKey("favorite_models_json")
        val SAVED_SSH_PASSWORD_PREFIX = "ssh_pwd_"
        val SAVED_SSH_KEY_PREFIX = "ssh_key_"
        const val LICENSE_TOKEN_SF1 = "license_token_sf1"
    }

    fun getLicenseToken(): String? =
        secretPrefs.getString(Keys.LICENSE_TOKEN_SF1, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setLicenseToken(token: String?) {
        val clean = token?.trim().orEmpty()
        secretPrefs.edit().apply {
            if (clean.isEmpty()) remove(Keys.LICENSE_TOKEN_SF1)
            else putString(Keys.LICENSE_TOKEN_SF1, clean)
        }.apply()
    }

    private val _providerConfigs = MutableStateFlow(defaultProviderConfigs())
    val providerConfigs: StateFlow<Map<String, ProviderConfig>> = _providerConfigs.asStateFlow()

    private val _hiddenBuiltInProviders = MutableStateFlow<Set<String>>(emptySet())
    val hiddenBuiltInProviders: StateFlow<Set<String>> = _hiddenBuiltInProviders.asStateFlow()

    private val _voiceSettings = MutableStateFlow(VoiceSettings())
    val voiceSettings: StateFlow<VoiceSettings> = _voiceSettings.asStateFlow()

    private val _searchSettings = MutableStateFlow(SearchSettings())
    val searchSettings: StateFlow<SearchSettings> = _searchSettings.asStateFlow()

    private val _agentSettings = MutableStateFlow(AgentSettings())
    val agentSettings: StateFlow<AgentSettings> = _agentSettings.asStateFlow()

    private val _mcpHostSettings = MutableStateFlow(McpHostSettings())
    val mcpHostSettings: StateFlow<McpHostSettings> = _mcpHostSettings.asStateFlow()

    private val _uiThemeSettings = MutableStateFlow(UiThemeSettings())
    val uiThemeSettings: StateFlow<UiThemeSettings> = _uiThemeSettings.asStateFlow()

    private val _connectionStripExpanded = MutableStateFlow(false)
    val connectionStripExpanded: StateFlow<Boolean> = _connectionStripExpanded.asStateFlow()

    private val _activeProviderId = MutableStateFlow(ProviderType.OPENROUTER.id)
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    private val _editorTheme = MutableStateFlow("ayu-dark")
    val editorTheme: StateFlow<String> = _editorTheme.asStateFlow()

    private val _safTreeUri = MutableStateFlow<String?>(null)
    val safTreeUri: StateFlow<String?> = _safTreeUri.asStateFlow()

    private val _favoriteModels = MutableStateFlow<List<String>>(emptyList())
    val favoriteModels: StateFlow<List<String>> = _favoriteModels.asStateFlow()

    suspend fun loadAll() {
        val prefs = context.dataStore.data.first()
        _editorTheme.value = prefs[Keys.EDITOR_THEME] ?: "ayu-dark"
        _safTreeUri.value = prefs[Keys.SAF_TREE_URI]
        _connectionStripExpanded.value = prefs[Keys.CONNECTION_STRIP_EXPANDED] ?: false
        _favoriteModels.value = prefs[Keys.FAVORITE_MODELS_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        } ?: emptyList()

        val storedTheme = prefs[Keys.UI_THEME_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<UiThemeSettings>(raw) }.getOrNull()
        }
        val loadedTheme = storedTheme?.let { UiThemeSettings.migrateLegacyDefault(it) } ?: UiThemeSettings()
        if (storedTheme != null && loadedTheme != storedTheme) {
            context.dataStore.edit { it[Keys.UI_THEME_SETTINGS] = json.encodeToString(UiThemeSettings.serializer(), loadedTheme) }
        }
        _uiThemeSettings.value = loadedTheme
        ForgeColors.applyTheme(loadedTheme)

        val hiddenSet = prefs[Keys.HIDDEN_BUILTIN_PROVIDERS]?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
                ?.mapTo(LinkedHashSet()) { it.trim().lowercase() }
        } ?: emptySet()
        _hiddenBuiltInProviders.value = hiddenSet

        val storedConfigs = prefs[Keys.PROVIDER_CONFIGS]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, ProviderConfig>>(raw) }.getOrNull()
        } ?: emptyMap()

        val merged = defaultProviderConfigs().toMutableMap()
        storedConfigs.forEach { (id, cfg) ->
            val apiKey = secretPrefs.getString("api_key_$id", "") ?: cfg.apiKeysRaw
            merged[id] = cfg.copy(apiKeysRaw = apiKey)
        }
        // Hydrate any keys stored independently
        merged.keys.forEach { id ->
            val sec = secretPrefs.getString("api_key_$id", null)
            if (sec != null) {
                merged[id] = merged.getValue(id).copy(apiKeysRaw = sec)
            }
        }
        _providerConfigs.value = merged

        val desiredActive = prefs[Keys.ACTIVE_PROVIDER] ?: ProviderType.OPENROUTER.id
        val visible = orderedProviderConfigs(merged, hiddenSet)
        _activeProviderId.value = if (visible.any { it.providerId == desiredActive }) {
            desiredActive
        } else {
            visible.firstOrNull()?.providerId ?: ProviderType.OPENROUTER.id
        }

        val voiceBase = prefs[Keys.VOICE_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<VoiceSettings>(raw) }.getOrNull()
        } ?: VoiceSettings()
        val sanitizedVoice = voiceBase.xttsVoice.trim()
        val sanitizedExtVoice = voiceBase.xttsExternalVoice.trim()
        _voiceSettings.value = voiceBase.copy(
            xttsVoice = sanitizedVoice,
            xttsExternalVoice = sanitizedExtVoice,
            sttApiKey = secretPrefs.getString("voice_stt_key", voiceBase.sttApiKey) ?: "",
            xttsCfClientId = secretPrefs.getString("xtts_cf_id", voiceBase.xttsCfClientId) ?: "",
            xttsCfClientSecret = secretPrefs.getString("xtts_cf_secret", voiceBase.xttsCfClientSecret) ?: "",
            xttsBearerToken = secretPrefs.getString("xtts_bearer", voiceBase.xttsBearerToken) ?: "",
            xttsExternalBearerToken = secretPrefs.getString("xtts_ext_bearer", voiceBase.xttsExternalBearerToken) ?: "",
            openAiTtsKey = secretPrefs.getString("openai_tts_key", voiceBase.openAiTtsKey) ?: ""
        )

        val searchBase = prefs[Keys.SEARCH_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<SearchSettings>(raw) }.getOrNull()
        } ?: SearchSettings()
        _searchSettings.value = searchBase.copy(
            tavilyApiKey = secretPrefs.getString("search_tavily", searchBase.tavilyApiKey) ?: "",
            braveApiKey = secretPrefs.getString("search_brave", searchBase.braveApiKey) ?: "",
            googleCseKey = secretPrefs.getString("search_gcse", searchBase.googleCseKey) ?: ""
        )

        _agentSettings.value = prefs[Keys.AGENT_SETTINGS]?.let { raw ->
            runCatching { decodeAgentSettings(raw, json) }.getOrNull()
        } ?: AgentSettings()

        val mcpHostBase = prefs[Keys.MCP_HOST_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<McpHostSettings>(raw) }.getOrNull()
        } ?: McpHostSettings()
        var storedHostToken = secretPrefs.getString("mcp_host_token", mcpHostBase.authToken).orEmpty().trim()
        if (storedHostToken.isBlank()) {
            storedHostToken = generateRandomToken()
            secretPrefs.edit().putString("mcp_host_token", storedHostToken).apply()
        }
        val hydratedMcpHost = mcpHostBase.copy(
            port = mcpHostBase.port.coerceIn(1024, 65535),
            authToken = storedHostToken
        )
        _mcpHostSettings.value = hydratedMcpHost
        ForgeAccessibilityService.visualTouchFeedback = hydratedMcpHost.visualTouchFeedback
    }

    suspend fun exportPortableSettings(): com.forgemind.app.data.archive.PortableSettings {
        val prefs = context.dataStore.data.first().asMap()
        fun <T> pick(test: (Any) -> Boolean, conv: (Any) -> T): Map<String, T> =
            prefs.filterValues { test(it) }.mapKeys { it.key.name }.mapValues { conv(it.value) }
        val result = com.forgemind.app.data.archive.PortableSettings(
            strings = pick({ it is String }) { it as String },
            booleans = pick({ it is Boolean }) { it as Boolean },
            ints = pick({ it is Int }) { it as Int },
            longs = pick({ it is Long }) { it as Long },
            floats = pick({ it is Float }) { it as Float },
            doubles = pick({ it is Double }) { it as Double },
            stringSets = pick({ it is Set<*> }) { value -> (value as Set<*>).map { it.toString() }.toSet() },
            secrets = secretPrefs.all.mapValues { (_, value) -> value as? String ?: error("Unsupported secret type") }
        )
        val captured = result.strings.size + result.booleans.size + result.ints.size + result.longs.size +
            result.floats.size + result.doubles.size + result.stringSets.size
        check(captured == prefs.size) { "Unsupported setting type" }
        return result
    }

    fun validatePortableSettings(value: com.forgemind.app.data.archive.PortableSettings) {
        val typedKeys = listOf(value.strings.keys, value.booleans.keys, value.ints.keys, value.longs.keys,
            value.floats.keys, value.doubles.keys, value.stringSets.keys).flatten()
        require(typedKeys.size + value.secrets.size <= 4096)
        require(typedKeys.size == typedKeys.toSet().size)
        require((typedKeys + value.secrets.keys).all { it.isNotBlank() && it.length <= 512 })
        value.strings[Keys.PROVIDER_CONFIGS.name]?.let { json.decodeFromString<Map<String, ProviderConfig>>(it) }
        value.strings[Keys.VOICE_SETTINGS.name]?.let { json.decodeFromString<VoiceSettings>(it) }
        value.strings[Keys.SEARCH_SETTINGS.name]?.let { json.decodeFromString<SearchSettings>(it) }
        value.strings[Keys.AGENT_SETTINGS.name]?.let { decodeAgentSettings(it, json) }
        value.strings[Keys.MCP_HOST_SETTINGS.name]?.let { json.decodeFromString<McpHostSettings>(it) }
        value.strings[Keys.UI_THEME_SETTINGS.name]?.let { json.decodeFromString<UiThemeSettings>(it) }
        value.strings[Keys.FAVORITE_MODELS_JSON.name]?.let { json.decodeFromString<List<String>>(it) }
        value.strings[Keys.HIDDEN_BUILTIN_PROVIDERS.name]?.let { json.decodeFromString<List<String>>(it) }
    }

    suspend fun importPortableSettings(value: com.forgemind.app.data.archive.PortableSettings,
                                       dropUnavailableGrants: Boolean) {
        validatePortableSettings(value)
        val secrets = secretPrefs.edit().clear()
        value.secrets.forEach { (key, text) -> secrets.putString(key, text) }
        check(secrets.commit()) { "Could not store restored secrets" }
        context.dataStore.edit { prefs ->
            prefs.clear()
            value.strings.forEach { (key, text) ->
                if (!dropUnavailableGrants || key !in setOf(Keys.SAF_TREE_URI.name, Keys.ACTIVE_SESSION_ID.name)) {
                    prefs[stringPreferencesKey(key)] = text
                }
            }
            value.booleans.forEach { (key, flag) -> prefs[booleanPreferencesKey(key)] = flag }
            value.ints.forEach { (key, n) -> prefs[androidx.datastore.preferences.core.intPreferencesKey(key)] = n }
            value.longs.forEach { (key, n) -> prefs[androidx.datastore.preferences.core.longPreferencesKey(key)] = n }
            value.floats.forEach { (key, n) -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(key)] = n }
            value.doubles.forEach { (key, n) -> prefs[androidx.datastore.preferences.core.doublePreferencesKey(key)] = n }
            value.stringSets.forEach { (key, set) -> prefs[androidx.datastore.preferences.core.stringSetPreferencesKey(key)] = set }
        }
    }

    fun getActiveProviderConfig(): ProviderConfig {
        val id = _activeProviderId.value
        return _providerConfigs.value[id] ?: ProviderConfig(providerId = id)
    }

    fun getProviderConfig(providerId: String): ProviderConfig =
        _providerConfigs.value[providerId] ?: ProviderConfig(providerId = providerId)

    suspend fun setActiveProvider(providerId: String) {
        _activeProviderId.value = providerId
        // If user explicitly activates a built-in provider that was hidden, unhide it
        val lower = providerId.trim().lowercase()
        if (lower in _hiddenBuiltInProviders.value) {
            val nextHidden = _hiddenBuiltInProviders.value - lower
            _hiddenBuiltInProviders.value = nextHidden
            context.dataStore.edit {
                it[Keys.HIDDEN_BUILTIN_PROVIDERS] = json.encodeToString(nextHidden.toList())
            }
        }
        context.dataStore.edit { it[Keys.ACTIVE_PROVIDER] = providerId }
    }

    suspend fun saveProviderConfig(config: ProviderConfig) {
        secretPrefs.edit().putString("api_key_${config.providerId}", config.apiKeysRaw).apply()
        val lower = config.providerId.trim().lowercase()
        if (lower in _hiddenBuiltInProviders.value) {
            val nextHidden = _hiddenBuiltInProviders.value - lower
            _hiddenBuiltInProviders.value = nextHidden
            context.dataStore.edit {
                it[Keys.HIDDEN_BUILTIN_PROVIDERS] = json.encodeToString(nextHidden.toList())
            }
        }
        val updated = _providerConfigs.value.toMutableMap()
        updated[config.providerId] = config
        _providerConfigs.value = updated

        val sanitized = updated.mapValues { (_, c) -> c.copy(apiKeysRaw = "") }
        context.dataStore.edit {
            it[Keys.PROVIDER_CONFIGS] = json.encodeToString(sanitized)
        }
    }

    suspend fun removeProvider(providerId: String): Boolean {
        val cleanId = providerId.trim()
        if (cleanId.isEmpty()) return false
        val currentlyVisible = orderedProviderConfigs(_providerConfigs.value, _hiddenBuiltInProviders.value)
        if (currentlyVisible.size <= 1 && currentlyVisible.firstOrNull()?.providerId.equals(cleanId, ignoreCase = true)) {
            return false
        }

        secretPrefs.edit().remove("api_key_$cleanId").apply()
        val updated = _providerConfigs.value.toMutableMap()
        val builtIn = ProviderType.findById(cleanId)
        var nextHidden = _hiddenBuiltInProviders.value

        if (builtIn != null) {
            updated[builtIn.id] = ProviderConfig(
                providerId = builtIn.id,
                baseUrl = builtIn.defaultBaseUrl,
                modelId = builtIn.defaultModel,
                contextWindow = builtIn.defaultContextWindow
            )
            nextHidden = nextHidden + builtIn.id.lowercase()
            _hiddenBuiltInProviders.value = nextHidden
        } else {
            updated.remove(cleanId)
        }

        _providerConfigs.value = updated
        val sanitized = updated.mapValues { (_, c) -> c.copy(apiKeysRaw = "") }
        context.dataStore.edit {
            it[Keys.PROVIDER_CONFIGS] = json.encodeToString(sanitized)
            it[Keys.HIDDEN_BUILTIN_PROVIDERS] = json.encodeToString(nextHidden.toList())
        }

        if (_activeProviderId.value.equals(cleanId, ignoreCase = true)) {
            val fallback = orderedProviderConfigs(updated, nextHidden).firstOrNull()?.providerId
                ?: ProviderType.OPENROUTER.id
            setActiveProvider(fallback)
        }
        return true
    }

    suspend fun deleteCustomProvider(providerId: String) {
        removeProvider(providerId)
    }

    suspend fun resetBuiltInProviders(clearApiKeys: Boolean = false) {
        val updated = _providerConfigs.value.toMutableMap()
        val secEditor = secretPrefs.edit()
        ProviderType.entries.forEach { type ->
            val keptKey = if (clearApiKeys) {
                secEditor.remove("api_key_${type.id}")
                ""
            } else {
                updated[type.id]?.apiKeysRaw
                    ?: secretPrefs.getString("api_key_${type.id}", "").orEmpty()
            }
            updated[type.id] = ProviderConfig(
                providerId = type.id,
                apiKeysRaw = keptKey,
                baseUrl = type.defaultBaseUrl,
                modelId = type.defaultModel,
                contextWindow = type.defaultContextWindow
            )
        }
        if (clearApiKeys) secEditor.apply()

        _hiddenBuiltInProviders.value = emptySet()
        _providerConfigs.value = updated
        val sanitized = updated.mapValues { (_, c) -> c.copy(apiKeysRaw = "") }
        context.dataStore.edit {
            it[Keys.HIDDEN_BUILTIN_PROVIDERS] = json.encodeToString(emptyList<String>())
            it[Keys.PROVIDER_CONFIGS] = json.encodeToString(sanitized)
        }
        if (_activeProviderId.value !in updated.keys) {
            setActiveProvider(ProviderType.OPENROUTER.id)
        }
    }

    suspend fun saveUiThemeSettings(settings: UiThemeSettings) {
        _uiThemeSettings.value = settings
        ForgeColors.applyTheme(settings)
        context.dataStore.edit {
            it[Keys.UI_THEME_SETTINGS] = json.encodeToString(settings)
        }
    }

    suspend fun resetUiThemeSettings() {
        saveUiThemeSettings(UiThemeSettings())
    }

    suspend fun setConnectionStripExpanded(expanded: Boolean) {
        _connectionStripExpanded.value = expanded
        context.dataStore.edit {
            it[Keys.CONNECTION_STRIP_EXPANDED] = expanded
        }
    }

    suspend fun saveVoiceSettings(settings: VoiceSettings) {
        secretPrefs.edit()
            .putString("voice_stt_key", settings.sttApiKey)
            .putString("xtts_cf_id", settings.xttsCfClientId)
            .putString("xtts_cf_secret", settings.xttsCfClientSecret)
            .putString("xtts_bearer", settings.xttsBearerToken)
            .putString("xtts_ext_bearer", settings.xttsExternalBearerToken)
            .putString("openai_tts_key", settings.openAiTtsKey)
            .apply()
        _voiceSettings.value = settings
        val sanitized = settings.copy(
            sttApiKey = "",
            xttsCfClientId = "",
            xttsCfClientSecret = "",
            xttsBearerToken = "",
            xttsExternalBearerToken = "",
            openAiTtsKey = ""
        )
        context.dataStore.edit {
            it[Keys.VOICE_SETTINGS] = json.encodeToString(sanitized)
        }
    }

    suspend fun saveSearchSettings(settings: SearchSettings) {
        secretPrefs.edit()
            .putString("search_tavily", settings.tavilyApiKey)
            .putString("search_brave", settings.braveApiKey)
            .putString("search_gcse", settings.googleCseKey)
            .apply()
        _searchSettings.value = settings
        val sanitized = settings.copy(
            tavilyApiKey = "",
            braveApiKey = "",
            googleCseKey = ""
        )
        context.dataStore.edit {
            it[Keys.SEARCH_SETTINGS] = json.encodeToString(sanitized)
        }
    }

    suspend fun saveAgentSettings(settings: AgentSettings) {
        _agentSettings.value = settings
        context.dataStore.edit {
            it[Keys.AGENT_SETTINGS] = json.encodeToString(settings)
        }
    }

    suspend fun saveMcpHostSettings(settings: McpHostSettings) {
        val cleanPort = settings.port.coerceIn(1024, 65535)
        val token = settings.authToken.trim().ifBlank {
            secretPrefs.getString("mcp_host_token", null)?.takeIf { it.isNotBlank() } ?: generateRandomToken()
        }
        secretPrefs.edit().putString("mcp_host_token", token).apply()
        val normalized = settings.copy(port = cleanPort, authToken = token)
        _mcpHostSettings.value = normalized
        ForgeAccessibilityService.visualTouchFeedback = normalized.visualTouchFeedback
        val sanitized = normalized.copy(authToken = "")
        context.dataStore.edit {
            it[Keys.MCP_HOST_SETTINGS] = json.encodeToString(sanitized)
        }
    }

    suspend fun regenerateMcpHostToken(): String {
        val fresh = generateRandomToken()
        saveMcpHostSettings(_mcpHostSettings.value.copy(authToken = fresh))
        return fresh
    }

    suspend fun setEditorTheme(theme: String) {
        _editorTheme.value = theme
        context.dataStore.edit { it[Keys.EDITOR_THEME] = theme }
    }

    suspend fun setSafTreeUri(uri: String?) {
        _safTreeUri.value = uri
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.SAF_TREE_URI)
            else it[Keys.SAF_TREE_URI] = uri
        }
    }

    fun activeSessionIdFlow(): Flow<String?> =
        context.dataStore.data.map { it[Keys.ACTIVE_SESSION_ID] }

    suspend fun setActiveSessionId(sessionId: String?) {
        context.dataStore.edit {
            if (sessionId == null) it.remove(Keys.ACTIVE_SESSION_ID)
            else it[Keys.ACTIVE_SESSION_ID] = sessionId
        }
    }

    suspend fun toggleFavoriteModel(providerId: String, modelId: String) {
        val cleanModel = modelId.trim()
        if (cleanModel.isEmpty()) return
        val token = "${providerId.trim()}::$cleanModel"
        val cur = _favoriteModels.value
        val next = if (token in cur) cur.filterNot { it == token } else listOf(token) + cur
        _favoriteModels.value = next
        context.dataStore.edit {
            it[Keys.FAVORITE_MODELS_JSON] = json.encodeToString(next)
        }
    }

    companion object {
        private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        fun generateRandomToken(length: Int = 28): String {
            val sr = SecureRandom()
            return buildString(length) {
                repeat(length) {
                    append(TOKEN_ALPHABET[sr.nextInt(TOKEN_ALPHABET.length)])
                }
            }
        }

        fun decodeAgentSettings(
            raw: String,
            parser: Json = Json { ignoreUnknownKeys = true }
        ): AgentSettings {
            val decoded = parser.decodeFromString<AgentSettings>(raw)
            // Migrate legacy v1 default (maxTurns=25 before thinkEveryToolCalls existed) to 100
            val hasThinkInterval = raw.contains("\"thinkEveryToolCalls\"")
            return if (!hasThinkInterval && decoded.maxTurns == 25) {
                decoded.copy(maxTurns = 100, thinkEveryToolCalls = 25)
            } else {
                decoded
            }
        }

        fun defaultProviderConfigs(): Map<String, ProviderConfig> =
            ProviderType.entries.associate { type ->
                type.id to ProviderConfig(
                    providerId = type.id,
                    baseUrl = type.defaultBaseUrl,
                    modelId = type.defaultModel,
                    contextWindow = type.defaultContextWindow
                )
            }

        fun orderedProviderConfigs(
            configs: Map<String, ProviderConfig>,
            hiddenBuiltIns: Set<String> = emptySet()
        ): List<ProviderConfig> {
            val hiddenNormalized = hiddenBuiltIns.mapTo(HashSet()) { it.lowercase() }
            val builtIns = ProviderType.entries
                .filter { it.id.lowercase() !in hiddenNormalized }
                .map { type ->
                    configs[type.id] ?: ProviderConfig(
                        providerId = type.id,
                        baseUrl = type.defaultBaseUrl,
                        modelId = type.defaultModel,
                        contextWindow = type.defaultContextWindow
                    )
                }
            val builtInIds = ProviderType.entries.mapTo(HashSet()) { it.id.lowercase() }
            val userDefined = configs.values
                .filter { it.providerId.lowercase() !in builtInIds }
                .sortedWith(
                    compareBy<ProviderConfig> { it.effectiveCategory().ordinal }
                        .thenBy { it.customDisplayName.ifBlank { it.providerId }.lowercase() }
                )
            val result = builtIns + userDefined
            return result.ifEmpty {
                val fallbackType = ProviderType.OPENROUTER
                listOf(
                    configs[fallbackType.id] ?: ProviderConfig(
                        providerId = fallbackType.id,
                        baseUrl = fallbackType.defaultBaseUrl,
                        modelId = fallbackType.defaultModel,
                        contextWindow = fallbackType.defaultContextWindow
                    )
                )
            }
        }
    }
}
