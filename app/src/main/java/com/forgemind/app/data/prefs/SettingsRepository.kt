package com.forgemind.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.VoiceSettings
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
        val VOICE_SETTINGS = stringPreferencesKey("voice_settings_json")
        val SEARCH_SETTINGS = stringPreferencesKey("search_settings_json")
        val AGENT_SETTINGS = stringPreferencesKey("agent_settings_json")
        val EDITOR_THEME = stringPreferencesKey("editor_theme")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val SAVED_SSH_PASSWORD_PREFIX = "ssh_pwd_"
        val SAVED_SSH_KEY_PREFIX = "ssh_key_"
    }

    private val _providerConfigs = MutableStateFlow(defaultProviderConfigs())
    val providerConfigs: StateFlow<Map<String, ProviderConfig>> = _providerConfigs.asStateFlow()

    private val _voiceSettings = MutableStateFlow(VoiceSettings())
    val voiceSettings: StateFlow<VoiceSettings> = _voiceSettings.asStateFlow()

    private val _searchSettings = MutableStateFlow(SearchSettings())
    val searchSettings: StateFlow<SearchSettings> = _searchSettings.asStateFlow()

    private val _agentSettings = MutableStateFlow(AgentSettings())
    val agentSettings: StateFlow<AgentSettings> = _agentSettings.asStateFlow()

    private val _activeProviderId = MutableStateFlow(ProviderType.OPENROUTER.id)
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    private val _editorTheme = MutableStateFlow("ayu-dark")
    val editorTheme: StateFlow<String> = _editorTheme.asStateFlow()

    private val _safTreeUri = MutableStateFlow<String?>(null)
    val safTreeUri: StateFlow<String?> = _safTreeUri.asStateFlow()

    suspend fun loadAll() {
        val prefs = context.dataStore.data.first()
        _activeProviderId.value = prefs[Keys.ACTIVE_PROVIDER] ?: ProviderType.OPENROUTER.id
        _editorTheme.value = prefs[Keys.EDITOR_THEME] ?: "ayu-dark"
        _safTreeUri.value = prefs[Keys.SAF_TREE_URI]

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

        val voiceBase = prefs[Keys.VOICE_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<VoiceSettings>(raw) }.getOrNull()
        } ?: VoiceSettings()
        val sanitizedVoice = if (voiceBase.xttsVoice.equals("Venice_selected", ignoreCase = true)) {
            ""
        } else {
            voiceBase.xttsVoice
        }
        val sanitizedExtVoice = if (voiceBase.xttsExternalVoice.equals("Venice_selected", ignoreCase = true)) {
            ""
        } else {
            voiceBase.xttsExternalVoice
        }
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
    }

    fun getActiveProviderConfig(): ProviderConfig {
        val id = _activeProviderId.value
        return _providerConfigs.value[id] ?: ProviderConfig(providerId = id)
    }

    fun getProviderConfig(providerId: String): ProviderConfig =
        _providerConfigs.value[providerId] ?: ProviderConfig(providerId = providerId)

    suspend fun setActiveProvider(providerId: String) {
        _activeProviderId.value = providerId
        context.dataStore.edit { it[Keys.ACTIVE_PROVIDER] = providerId }
    }

    suspend fun saveProviderConfig(config: ProviderConfig) {
        secretPrefs.edit().putString("api_key_${config.providerId}", config.apiKeysRaw).apply()
        val updated = _providerConfigs.value.toMutableMap()
        updated[config.providerId] = config
        _providerConfigs.value = updated

        val sanitized = updated.mapValues { (_, c) -> c.copy(apiKeysRaw = "") }
        context.dataStore.edit {
            it[Keys.PROVIDER_CONFIGS] = json.encodeToString(sanitized)
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

    companion object {
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
    }
}
