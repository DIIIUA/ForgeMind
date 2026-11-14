package com.forgemind.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class WireProtocol(val shortLabel: String, val endpointHint: String) {
    OPENAI_COMPAT("OpenAI Chat", "/v1/chat/completions"),
    OPENAI_RESPONSES("OpenAI Responses", "/v1/responses"),
    ANTHROPIC("Anthropic Messages", "/v1/messages"),
    GEMINI("Google Gemini", "/v1beta/models/...:streamGenerateContent"),
    OLLAMA_NATIVE("Ollama Native", "/api/chat")
}

@Serializable
enum class ProviderCategory {
    CLOUD,
    LOCAL_LAN,
    CUSTOM
}

@Serializable
enum class ProviderAuthMode {
    AUTO,
    NONE,
    BEARER,
    X_API_KEY,
    QUERY_KEY,
    CUSTOM_HEADER
}

enum class ProviderType(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val wireProtocol: WireProtocol,
    val defaultContextWindow: Int,
    val supportsThinking: Boolean = true,
    val supportsVision: Boolean = true,
    val supportsTools: Boolean = true,
    val category: ProviderCategory = ProviderCategory.CLOUD,
    val requiresKeyByDefault: Boolean = true
) {
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        subtitle = "GPT-5 / GPT-4o / o-series",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5.4",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 200_000
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic Claude",
        subtitle = "Claude Sonnet / Opus / Haiku",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-6",
        wireProtocol = WireProtocol.ANTHROPIC,
        defaultContextWindow = 200_000
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        subtitle = "Gemini 2.5 / 3.x Flash & Pro",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        wireProtocol = WireProtocol.GEMINI,
        defaultContextWindow = 1_000_000
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        subtitle = "200+ models (free & paid)",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "google/gemini-2.5-flash",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        subtitle = "Ultra-fast LPU + Whisper STT",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    ),
    XAI_GROK(
        id = "xai_grok",
        displayName = "xAI Grok",
        subtitle = "Grok-3 / Grok-4 / Fast Reasoning",
        defaultBaseUrl = "https://api.x.ai/v1",
        defaultModel = "grok-3-latest",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 131_072
    ),
    VENICE(
        id = "venice",
        displayName = "Venice AI",
        subtitle = "Private uncensored / open-weights API",
        defaultBaseUrl = "https://api.venice.ai/api/v1",
        defaultModel = "llama-3.3-70b",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    ),
    MISTRAL(
        id = "mistral",
        displayName = "Mistral AI",
        subtitle = "Mistral Large / Codestral / Devstral",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-large-latest",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    ),
    NVIDIA(
        id = "nvidia",
        displayName = "NVIDIA NIM",
        subtitle = "DeepSeek / Qwen / Llama on NIM",
        defaultBaseUrl = "https://integrate.api.nvidia.com/v1",
        defaultModel = "deepseek-ai/deepseek-r1",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    ),
    CLOUDFLARE(
        id = "cloudflare",
        displayName = "Cloudflare Workers AI",
        subtitle = "Workers AI OpenAI endpoint",
        defaultBaseUrl = "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
        defaultModel = "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 64_000
    ),
    HUGGINGFACE(
        id = "huggingface",
        displayName = "HuggingFace Router",
        subtitle = "HF Inference Providers API",
        defaultBaseUrl = "https://router.huggingface.co/v1",
        defaultModel = "Qwen/Qwen2.5-Coder-32B-Instruct",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 64_000
    ),
    OLLAMA(
        id = "ollama",
        displayName = "Ollama (LAN / PC / Termux)",
        subtitle = "Native /api/chat + OpenAI LAN (11434)",
        defaultBaseUrl = "http://127.0.0.1:11434",
        defaultModel = "qwen2.5-coder:7b",
        wireProtocol = WireProtocol.OLLAMA_NATIVE,
        defaultContextWindow = 32_768,
        category = ProviderCategory.LOCAL_LAN,
        requiresKeyByDefault = false
    ),
    LM_STUDIO(
        id = "lm_studio",
        displayName = "LM Studio (LAN)",
        subtitle = "Local GGUF / MLX server over Wi-Fi (:1234)",
        defaultBaseUrl = "http://192.168.1.100:1234/v1",
        defaultModel = "local-model",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 32_768,
        category = ProviderCategory.LOCAL_LAN,
        requiresKeyByDefault = false
    ),
    LLAMA_CPP(
        id = "llama_cpp",
        displayName = "llama.cpp / KoboldCpp (LAN)",
        subtitle = "llama-server / KoboldCpp over LAN (:8080)",
        defaultBaseUrl = "http://192.168.1.100:8080/v1",
        defaultModel = "local-model",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 32_768,
        category = ProviderCategory.LOCAL_LAN,
        requiresKeyByDefault = false
    ),
    VLLM_LOCAL(
        id = "vllm_local",
        displayName = "vLLM / LocalAI / SGLang (LAN)",
        subtitle = "High-throughput LAN GPU server (:8000)",
        defaultBaseUrl = "http://192.168.1.100:8000/v1",
        defaultModel = "local-model",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 65_536,
        category = ProviderCategory.LOCAL_LAN,
        requiresKeyByDefault = false
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom External AI",
        subtitle = "Any URL, protocol, auth header & JSON body",
        defaultBaseUrl = "https://api.example.com/v1",
        defaultModel = "default",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000,
        category = ProviderCategory.CUSTOM,
        requiresKeyByDefault = false
    );

    companion object {
        fun findById(id: String?): ProviderType? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        fun fromId(id: String?): ProviderType {
            val clean = id?.trim().orEmpty()
            findById(clean)?.let { return it }
            return when {
                clean.startsWith("lan_ollama", ignoreCase = true) -> OLLAMA
                clean.startsWith("lan_", ignoreCase = true) -> LM_STUDIO
                clean.startsWith("custom_", ignoreCase = true) -> CUSTOM
                else -> OPENROUTER
            }
        }
    }
}

enum class ReasoningEffort(val id: String, val budgetTokens: Int) {
    OFF("off", 0),
    LOW("low", 1024),
    MEDIUM("medium", 4096),
    HIGH("high", 12288)
}

@Serializable
data class ProviderConfig(
    val providerId: String,
    val apiKeysRaw: String = "",
    val baseUrl: String = ProviderType.fromId(providerId).defaultBaseUrl,
    val modelId: String = ProviderType.fromId(providerId).defaultModel,
    val customHeadersJson: String = "{}",
    val systemPrompt: String = "",
    val temperature: Float = 0.6f,
    val maxTokens: Int = 8192,
    val contextWindow: Int = ProviderType.fromId(providerId).defaultContextWindow,
    val enableThinking: Boolean = true,
    val thinkingBudget: Int = 2048,
    val autoRotateKeys: Boolean = true,
    val customDisplayName: String = "",
    val customSubtitle: String = "",
    val isUserCreated: Boolean = false,
    val categoryOverride: ProviderCategory? = null,
    val wireProtocolOverride: WireProtocol? = null,
    val authMode: ProviderAuthMode = ProviderAuthMode.AUTO,
    val customAuthHeader: String = "X-API-Key",
    val customAuthPrefix: String = "",
    val customQueryParam: String = "key",
    val rawEndpointUrl: Boolean = false,
    val modelsEndpointUrl: String = "",
    val extraBodyJson: String = "{}",
    val allowInsecureSsl: Boolean = false,
    val readTimeoutSec: Int = 180,
    val savedModels: List<String> = emptyList(),
    val autoContextWindow: Boolean = true,
    val modelContextOverrides: Map<String, Int> = emptyMap()
) {
    fun parsedKeys(): List<String> =
        apiKeysRaw.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun effectiveWireProtocol(): WireProtocol =
        wireProtocolOverride ?: ProviderType.fromId(providerId).wireProtocol

    fun effectiveCategory(): ProviderCategory =
        categoryOverride ?: when {
            providerId.startsWith("lan_", ignoreCase = true) -> ProviderCategory.LOCAL_LAN
            providerId.startsWith("custom_", ignoreCase = true) -> ProviderCategory.CUSTOM
            else -> ProviderType.fromId(providerId).category
        }

    fun isBuiltInProvider(): Boolean =
        !isUserCreated && ProviderType.findById(providerId) != null

    fun requiresApiKey(): Boolean = when (authMode) {
        ProviderAuthMode.NONE -> false
        ProviderAuthMode.AUTO -> effectiveCategory() == ProviderCategory.CLOUD &&
            (ProviderType.findById(providerId)?.requiresKeyByDefault == true)
        else -> false
    }

    fun isReadyForUse(): Boolean = when {
        requiresApiKey() -> parsedKeys().isNotEmpty()
        isUserCreated -> baseUrl.isNotBlank() && !baseUrl.contains("api.example.com", ignoreCase = true)
        else -> parsedKeys().isNotEmpty() ||
            baseUrl != ProviderType.fromId(providerId).defaultBaseUrl ||
            savedModels.isNotEmpty()
    }

    fun shouldShowStatusDot(activeProviderId: String): Boolean =
        providerId == activeProviderId ||
            isUserCreated ||
            parsedKeys().isNotEmpty() ||
            baseUrl != ProviderType.fromId(providerId).defaultBaseUrl ||
            savedModels.isNotEmpty() ||
            authMode != ProviderAuthMode.AUTO

    fun reasoningEffort(): ReasoningEffort = when {
        !enableThinking -> ReasoningEffort.OFF
        thinkingBudget <= 1536 -> ReasoningEffort.LOW
        thinkingBudget <= 6144 -> ReasoningEffort.MEDIUM
        else -> ReasoningEffort.HIGH
    }

    fun withReasoningEffort(effort: ReasoningEffort): ProviderConfig = when (effort) {
        ReasoningEffort.OFF -> copy(enableThinking = false)
        else -> copy(
            enableThinking = true,
            thinkingBudget = effort.budgetTokens,
            maxTokens = if (maxTokens < effort.budgetTokens + 1024) effort.budgetTokens + 2048 else maxTokens
        )
    }

    fun isManualContextForModel(targetModelId: String = modelId): Boolean {
        val key = targetModelId.trim()
        return !autoContextWindow || modelContextOverrides.containsKey(key)
    }

    fun resolveContextWindowForModel(
        targetModelId: String = modelId,
        scannedContextLength: Int? = null
    ): Int {
        val key = targetModelId.trim()
        modelContextOverrides[key]?.takeIf { it >= 1024 }?.let { return it }
        if (!autoContextWindow) {
            return contextWindow.coerceAtLeast(1024)
        }
        scannedContextLength?.takeIf { it >= 1024 }?.let { return it }
        return ContextWindowResolver.inferContextWindow(
            modelId = key,
            providerType = ProviderType.fromId(providerId),
            category = effectiveCategory()
        )
    }
}

object ContextWindowResolver {
    val PRESET_WINDOWS: List<Int> = listOf(
        4_096,
        8_192,
        16_384,
        32_768,
        65_536,
        131_072,
        200_000,
        262_144,
        1_000_000
    )

    private val EXPLICIT_CTX_SUFFIX_REGEX = Regex(
        """(?:^|[-_:/\s])(\d{1,4})(k|m)(?:[-_:/\s]|ctx|context|$)""",
        RegexOption.IGNORE_CASE
    )
    private val SMALL_PARAM_SIZE_REGEX = Regex(
        """(?:^|[-_:/\s])(0\.5|1|1\.5|1\.7|2|3|3\.8|4|6|7|8|9)b(?:[-_:/\s.]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun formatTokensShort(tokens: Int): String = when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens in 130_000..132_000 -> "128K"
        tokens in 260_000..265_000 -> "256K"
        tokens in 65_000..66_000 -> "64K"
        tokens in 32_000..33_000 -> "32K"
        tokens in 16_000..16_600 -> "16K"
        tokens in 8_000..8_300 -> "8K"
        tokens in 4_000..4_200 -> "4K"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }

    fun inferContextWindow(
        modelId: String,
        providerType: ProviderType,
        category: ProviderCategory = providerType.category
    ): Int {
        val m = modelId.trim().lowercase()
        if (m.isEmpty()) return providerType.defaultContextWindow

        // 1) Explicit token marker in model ID (e.g. qwen2.5:32k, llama-8k, model-128k, gemini-1m)
        for (match in EXPLICIT_CTX_SUFFIX_REGEX.findAll(m)) {
            val num = match.groupValues[1].toIntOrNull() ?: continue
            val unit = match.groupValues[2].lowercase()
            // Avoid mistaking parameter count (like 7b/8b) — regex only matches k/m!
            val tokens = if (unit == "m") num * 1_000_000 else if (num in listOf(4, 8, 16, 32, 64, 128, 256, 512)) num * 1_024 else num * 1_000
            if (tokens in 2_048..2_097_152) return tokens
        }

        // 2) Local LAN / GGUF / Ollama / LM Studio conservative auto defaults (when server metadata wasn't reported)
        if (category == ProviderCategory.LOCAL_LAN) {
            if (SMALL_PARAM_SIZE_REGEX.containsMatchIn(m)) {
                return 16_384
            }
            return 32_768
        }

        // 3) Cloud / Known model families
        return when {
            m.contains("gemini-1.5") || m.contains("gemini-2.") || m.contains("gemini-3") -> 1_000_000
            m.contains("gpt-4.1") -> 1_000_000
            m.contains("claude-3") || m.contains("claude-sonnet-4") || m.contains("claude-opus-4") || m.contains("claude-haiku-4") -> 200_000
            m.startsWith("gpt-5") || m.contains("/gpt-5") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
                m.contains("/o3") || m.contains("/o4") -> 200_000
            m.contains("grok-4") -> 256_000
            m.contains("grok-3") || m.contains("grok-2") -> 131_072
            m.contains("gpt-4o") || m.contains("gpt-4-turbo") -> 128_000
            m.contains("codestral") || m.contains("devstral") -> 256_000
            m.contains("mistral-large") || m.contains("ministral") || m.contains("pixtral") -> 128_000
            m.contains("deepseek-chat") || m.contains("deepseek-reasoner") || m.contains("deepseek-v3") ||
                m.contains("deepseek-v4") || m.contains("deepseek-r1") -> {
                if (SMALL_PARAM_SIZE_REGEX.containsMatchIn(m) || m.contains("heretic") || m.contains("gguf")) 32_768 else 128_000
            }
            m.contains("qwen3") || m.contains("qwen2.5") || m.contains("qwq") -> {
                if (SMALL_PARAM_SIZE_REGEX.containsMatchIn(m) && (m.contains("gguf") || m.contains("heretic"))) 32_768 else 128_000
            }
            m.contains("llama-3.1") || m.contains("llama-3.2") || m.contains("llama-3.3") || m.contains("llama-4") ||
                m.contains("llama3.1") || m.contains("llama3.2") || m.contains("llama3.3") -> 128_000
            m.contains("gemma-3") || m.contains("gemma3") -> 128_000
            m.contains("gemma-2") || m.contains("gemma2") -> 8_192
            else -> providerType.defaultContextWindow
        }
    }
}

enum class WorkspaceEnvType(val id: String, val label: String) {
    INTERNAL("internal", "AI Home"),
    DEVICE_STORAGE("device_storage", "Phone Storage"),
    TERMUX_SSH("termux_ssh", "SSH / Termux"),
    SAF("saf", "Android Folder (SAF)");

    companion object {
        fun fromId(id: String?): WorkspaceEnvType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: INTERNAL
    }
}

enum class LlmRole {
    SYSTEM, USER, ASSISTANT, TOOL
}

@Serializable
data class ToolCallInfo(
    val id: String,
    val name: String,
    val argumentsJson: String = "{}",
    val thoughtSignature: String? = null,
    val viaTextFallback: Boolean = false
)

@Serializable
enum class ToolExecutionStatus {
    RUNNING, NEEDS_APPROVAL, SUCCESS, ERROR, DENIED
}

@Serializable
data class ToolExecutionCard(
    val callId: String,
    val toolName: String,
    val status: ToolExecutionStatus = ToolExecutionStatus.RUNNING,
    val summary: String = "",
    val argumentsJson: String = "{}",
    val resultPreview: String = "",
    val diffText: String? = null,
    val snapshotId: String? = null,
    val mcpServerName: String? = null,
    val durationMs: Long = 0L
)

@Serializable
enum class AttachmentKind {
    IMAGE, PDF, CODE_TEXT, AUDIO, BINARY
}

@Serializable
data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long = 0L,
    val kind: AttachmentKind = AttachmentKind.BINARY,
    val previewText: String? = null,
    val isFromAssistant: Boolean = false
)

enum class SttProviderType(val id: String, val label: String) {
    GROQ_WHISPER("groq_whisper", "Groq Whisper (whisper-large-v3-turbo)"),
    OPENAI_WHISPER("openai_whisper", "OpenAI Whisper"),
    CUSTOM_WHISPER("custom_whisper", "Custom Whisper API"),
    ANDROID_SPEECH("android_speech", "Built-in Android STT (offline)");

    companion object {
        fun fromId(id: String?): SttProviderType =
            entries.firstOrNull { it.id == id } ?: GROQ_WHISPER
    }
}

enum class TtsProviderType(val id: String, val label: String) {
    MICROSOFT_EDGE("microsoft_edge", "Microsoft Neural (Free)"),
    XTTS_V2("xtts_v2", "XTTS local (Wi-Fi / LAN)"),
    XTTS_EXTERNAL("xtts_external", "XTTS external (Cloudflare)"),
    OPENAI_TTS("openai_tts", "OpenAI / Compatible TTS"),
    ANDROID_TTS("android_tts", "Built-in Android TTS");

    companion object {
        fun fromId(id: String?): TtsProviderType =
            entries.firstOrNull { it.id == id } ?: XTTS_V2
    }
}

@Serializable
data class VoiceSettings(
    val sttProvider: SttProviderType = SttProviderType.GROQ_WHISPER,
    val sttBaseUrl: String = "https://api.groq.com/openai/v1",
    val sttApiKey: String = "",
    val sttModel: String = "whisper-large-v3-turbo",
    val sttLanguage: String = "ru",
    val sttAutoFallback: Boolean = false,
    val autoSendAfterVoice: Boolean = false,
    val handsFreeMode: Boolean = false,
    val ttsProvider: TtsProviderType = TtsProviderType.XTTS_V2,
    val edgeTtsVoice: String = "auto",
    val edgeTtsPitchHz: Int = 0,
    val xttsBaseUrl: String = "http://127.0.0.1:8020",
    val xttsVoice: String = "",
    val xttsLanguage: String = "ru",
    val xttsPitch: Float = 0.0f,
    val xttsKeepTempo: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val xttsExternalUrl: String = "",
    val xttsExternalVoice: String = "",
    val xttsCfClientId: String = "",
    val xttsCfClientSecret: String = "",
    val xttsBearerToken: String = "",
    val xttsExternalBearerToken: String = "",
    val openAiTtsUrl: String = "https://api.openai.com/v1/audio/speech",
    val openAiTtsKey: String = "",
    val openAiTtsModel: String = "gpt-4o-mini-tts",
    val openAiTtsVoice: String = "nova",
    val autoSpeakReplies: Boolean = false,
    val skipCodeInTts: Boolean = true
) {
    fun activeXttsBaseUrl(): String =
        if (ttsProvider == TtsProviderType.XTTS_EXTERNAL) xttsExternalUrl else xttsBaseUrl

    fun activeXttsVoice(): String =
        if (ttsProvider == TtsProviderType.XTTS_EXTERNAL) xttsExternalVoice else xttsVoice

    fun activeXttsBearerToken(): String =
        if (ttsProvider == TtsProviderType.XTTS_EXTERNAL) xttsExternalBearerToken else xttsBearerToken
}

enum class SearchEngineType(val id: String, val label: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    TAVILY("tavily", "Tavily API"),
    BRAVE("brave", "Brave Search API"),
    GOOGLE_CSE("google_cse", "Google Custom Search"),
    SEARXNG("searxng", "SearXNG (self-hosted)");

    companion object {
        fun fromId(id: String?): SearchEngineType =
            entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

@Serializable
data class SearchSettings(
    val primaryEngine: SearchEngineType = SearchEngineType.DUCKDUCKGO,
    val tavilyApiKey: String = "",
    val braveApiKey: String = "",
    val googleCseKey: String = "",
    val googleCseCx: String = "",
    val searxngUrl: String = "",
    val enableJsScrape: Boolean = true
)

@Serializable
data class AgentSettings(
    val enableTools: Boolean = true,
    val autoApproveSafe: Boolean = true,
    val autoApproveAll: Boolean = false,
    val maxTurns: Int = 100,
    val thinkEveryToolCalls: Int = 25,
    val parallelToolCalls: Boolean = true,
    val autoCompactContext: Boolean = true,
    val compactThresholdPercent: Int = 78,
    val autoSnapshotBeforeEdit: Boolean = true,
    val globalSystemPrompt: String = "",
    val autoApproveRead: Boolean = true,
    val autoApproveFileWrite: Boolean = true,
    val autoApproveFileDelete: Boolean = false,
    val autoApproveShell: Boolean = false,
    val autoApproveBrowser: Boolean = true,
    val autoApproveBrowserJs: Boolean = false,
    val autoApproveMcp: Boolean = false,
    val autoApproveChatManage: Boolean = true,
    val blockedTools: List<String> = emptyList(),
    val allowedTools: List<String> = emptyList()
)

@Serializable
enum class McpTransport {
    STREAMABLE_HTTP,
    HTTP_SSE,
    SSH_STDIO
}

@Serializable
data class McpHostSettings(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val requireAuth: Boolean = false,
    val authToken: String = "",
    val exposeShellAndWriteTools: Boolean = true,
    val visualTouchFeedback: Boolean = true
)

data class McpHostRuntimeState(
    val isRunning: Boolean = false,
    val boundPort: Int = 0,
    val requestsHandled: Long = 0L,
    val lastToolCalled: String? = null,
    val lastError: String? = null
)

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = """{"type":"object","properties":{}}"""
)

data class PendingApproval(
    val id: String,
    val toolName: String,
    val title: String,
    val details: String,
    val diffPreview: String? = null
)

data class ModelInfo(
    val id: String,
    val name: String = id,
    val contextLength: Int? = null,
    val pricingHint: String? = null
)

data class WorkspaceFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val envType: WorkspaceEnvType,
    val sshServerId: String? = null
)

@Serializable
data class UiThemeSettings(
    val presetId: String = "forge_default",
    val bgDeepHex: String = "#0B0F17",
    val surfaceDarkHex: String = "#121826",
    val surfaceAlpha: Float = 1.0f,
    val surfaceCardHex: String = "#1E273B",
    val cardAlpha: Float = 1.0f,
    val userBubbleHex: String = "#204382",
    val userBubbleAlpha: Float = 0.95f,
    val aiBubbleHex: String = "#162033",
    val aiBubbleAlpha: Float = 1.0f,
    val accentHex: String = "#4F8CFF",
    val accentAlpha: Float = 1.0f,
    val borderHex: String = "#35445E",
    val borderAlpha: Float = 1.0f,
    val terminalPreset: String = "classic",
    val terminalBgHex: String = "#0A0F18",
    val terminalBgAlpha: Float = 1.0f,
    val terminalTextHex: String = "#DCE7F3",
    val terminalPromptHex: String = "#38BDF8"
)

