package com.forgemind.app.llm

import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class ModelScanner(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient
) {
    suspend fun scanModels(config: ProviderConfig): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val type = ProviderType.fromId(config.providerId)
        val firstKey = config.parsedKeys().firstOrNull().orEmpty()

        if (type == ProviderType.ANTHROPIC) {
            return@withContext Result.success(
                listOf(
                    ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", 200_000),
                    ModelInfo("claude-opus-4-6", "Claude Opus 4.6", 200_000),
                    ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", 200_000),
                    ModelInfo("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet", 200_000)
                )
            )
        }

        if (type == ProviderType.GEMINI) {
            if (firstKey.isBlank()) {
                return@withContext Result.success(defaultPresets(type))
            }
            return@withContext runCatching {
                val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, type.defaultBaseUrl)
                val req = Request.Builder()
                    .url("$base/v1beta/models?key=$firstKey")
                    .get()
                    .also { LlmHttpFactory.applyCustomHeaders(it, config.customHeadersJson) }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
                    val obj = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    val arr = obj["models"]?.jsonArray ?: return@use defaultPresets(type)
                    arr.mapNotNull { el ->
                        val m = el.jsonObject
                        val id = m["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
                        val methods = m["supportedGenerationMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        if ("generateContent" !in methods) return@mapNotNull null
                        ModelInfo(
                            id = id,
                            name = m["displayName"]?.jsonPrimitive?.contentOrNull ?: id,
                            contextLength = m["inputTokenLimit"]?.jsonPrimitive?.intOrNull
                        )
                    }.sortedBy { it.id }
                }
            }
        }

        if (type == ProviderType.OLLAMA) {
            return@withContext runCatching {
                val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, type.defaultBaseUrl)
                    .removeSuffix("/v1")
                val req = Request.Builder()
                    .url("$base/api/tags")
                    .get()
                    .apply {
                        if (firstKey.isNotBlank()) header("Authorization", "Bearer $firstKey")
                        LlmHttpFactory.applyCustomHeaders(this, config.customHeadersJson)
                    }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Fallback to OpenAI /v1/models
                        return@runCatching scanOpenAiModels(config, type, firstKey)
                    }
                    val obj = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    val arr = obj["models"]?.jsonArray ?: return@use defaultPresets(type)
                    arr.mapNotNull { el ->
                        val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ModelInfo(id = name, name = name)
                    }
                }
            }
        }

        return@withContext runCatching {
            scanOpenAiModels(config, type, firstKey)
        }
    }

    private fun scanOpenAiModels(config: ProviderConfig, type: ProviderType, firstKey: String): List<ModelInfo> {
        val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, type.defaultBaseUrl)
        val url = when {
            base.endsWith("/models") -> base
            base.endsWith("/v1") || base.endsWith("/api/v1") -> "$base/models"
            else -> "$base/v1/models"
        }
        val req = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (firstKey.isNotBlank()) header("Authorization", "Bearer $firstKey")
                LlmHttpFactory.applyCustomHeaders(this, config.customHeadersJson)
            }
            .build()
        val resp = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            error("Ошибка сети ($url): ${e.message ?: e::class.java.simpleName}")
        }
        resp.use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} ($url): ${r.body?.string().orEmpty().take(300)}")
            val raw = r.body?.string().orEmpty()
            val root = runCatching { LlmHttpFactory.json.parseToJsonElement(raw).jsonObject }
                .getOrElse { error("Невалидный JSON от $url: ${raw.take(200)}") }
            val data = root["data"]?.jsonArray ?: return defaultPresets(type)
            return data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ctx = obj["context_length"]?.jsonPrimitive?.intOrNull
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                val isFree = id.endsWith(":free")
                ModelInfo(
                    id = id,
                    name = name,
                    contextLength = ctx,
                    pricingHint = if (isFree) "FREE" else null
                )
            }.sortedWith(compareByDescending<ModelInfo> { it.pricingHint == "FREE" }.thenBy { it.id })
        }
    }

    companion object {
        fun defaultPresets(type: ProviderType): List<ModelInfo> = when (type) {
            ProviderType.OPENAI -> listOf(
                ModelInfo("gpt-5.4", "GPT-5.4", 200_000),
                ModelInfo("gpt-4.1", "GPT-4.1", 1_000_000),
                ModelInfo("gpt-4o", "GPT-4o", 128_000),
                ModelInfo("o3-mini", "o3-mini", 200_000)
            )
            ProviderType.GEMINI -> listOf(
                ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", 1_000_000),
                ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", 1_000_000)
            )
            ProviderType.XAI_GROK -> listOf(
                ModelInfo("grok-3-latest", "Grok 3", 131_072),
                ModelInfo("grok-3-mini-latest", "Grok 3 Mini", 131_072)
            )
            ProviderType.GROQ -> listOf(
                ModelInfo("llama-3.3-70b-versatile", "Llama 3.3 70B", 128_000),
                ModelInfo("qwen/qwen3-32b", "Qwen 3 32B", 128_000)
            )
            ProviderType.VENICE -> listOf(
                ModelInfo("llama-3.3-70b", "Llama 3.3 70B", 128_000),
                ModelInfo("deepseek-r1-671b", "DeepSeek R1", 128_000)
            )
            else -> listOf(ModelInfo(type.defaultModel, type.defaultModel, type.defaultContextWindow))
        }
    }
}
