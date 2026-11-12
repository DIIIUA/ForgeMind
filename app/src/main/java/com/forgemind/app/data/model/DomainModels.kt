package com.forgemind.app.data.model

import kotlinx.serialization.Serializable

enum class WireProtocol {
    OPENAI_COMPAT,
    ANTHROPIC,
    GEMINI
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
    val supportsTools: Boolean = true
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
        subtitle = "200+ моделей (бесплатные и платные)",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "google/gemini-2.5-flash",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        subtitle = "Сверхбыстрый LPU + Whisper STT",
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
        subtitle = "Приватный uncensored / open-weights API",
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
        subtitle = "DeepSeek / Qwen / Llama на NIM",
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
        displayName = "Ollama (ПК / Termux / Tunnel)",
        subtitle = "Локальные модели через LAN / Cloudflare / 127.0.0.1",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5-coder:7b",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 32_768
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom OpenAI / LiteLLM",
        subtitle = "Любой совместимый endpoint + кастомные заголовки",
        defaultBaseUrl = "http://127.0.0.1:4000/v1",
        defaultModel = "default",
        wireProtocol = WireProtocol.OPENAI_COMPAT,
        defaultContextWindow = 128_000
    );

    companion object {
        fun fromId(id: String?): ProviderType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPENROUTER
    }
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
    val autoRotateKeys: Boolean = true
) {
    fun parsedKeys(): List<String> =
        apiKeysRaw.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

enum class WorkspaceEnvType(val id: String, val label: String) {
    INTERNAL("internal", "Домашняя ИИ"),
    TERMUX_SSH("termux_ssh", "SSH / Termux"),
    SAF("saf", "Папка Android (SAF)");

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
    val thoughtSignature: String? = null
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
    ANDROID_SPEECH("android_speech", "Встроенный Android STT (офлайн)");

    companion object {
        fun fromId(id: String?): SttProviderType =
            entries.firstOrNull { it.id == id } ?: GROQ_WHISPER
    }
}

enum class TtsProviderType(val id: String, val label: String) {
    XTTS_V2("xtts_v2", "XTTS локальный (Wi-Fi / LAN)"),
    XTTS_EXTERNAL("xtts_external", "XTTS внешний (Cloudflare)"),
    OPENAI_TTS("openai_tts", "OpenAI / Compatible TTS"),
    ANDROID_TTS("android_tts", "Встроенный Android TTS");

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
    val autoSendAfterVoice: Boolean = false,
    val handsFreeMode: Boolean = false,
    val ttsProvider: TtsProviderType = TtsProviderType.XTTS_V2,
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
    SEARXNG("searxng", "SearXNG (свой инстанс)");

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
    val globalSystemPrompt: String = ""
)

@Serializable
enum class McpTransport {
    STREAMABLE_HTTP,
    HTTP_SSE,
    SSH_STDIO
}

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
