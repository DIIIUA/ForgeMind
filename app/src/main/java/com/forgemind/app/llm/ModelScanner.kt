package com.forgemind.app.llm

import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
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
        val baseModelsUrl = buildOpenAiModelsBaseUrl(base)
        val targetUrl = buildOpenAiModelsQueryUrl(baseModelsUrl, type)

        val primaryResult = fetchOpenAiModelsRaw(targetUrl, config, firstKey)
        val rawJson = if (primaryResult.isSuccess) {
            primaryResult.getOrThrow()
        } else if (targetUrl != baseModelsUrl) {
            // Soft fallback in case a custom proxy rejects ?type=all
            fetchOpenAiModelsRaw(baseModelsUrl, config, firstKey).getOrElse {
                throw primaryResult.exceptionOrNull()!!
            }
        } else {
            throw primaryResult.exceptionOrNull()!!
        }

        return parseOpenAiModelsJson(rawJson, type, targetUrl)
    }

    private fun fetchOpenAiModelsRaw(url: String, config: ProviderConfig, firstKey: String): Result<String> = runCatching {
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
            error("Network error ($url): ${e.message ?: e::class.java.simpleName}")
        }
        resp.use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} ($url): ${r.body?.string().orEmpty().take(300)}")
            r.body?.string().orEmpty()
        }
    }

    companion object {
        fun buildOpenAiModelsBaseUrl(normalizedBase: String): String = when {
            normalizedBase.endsWith("/models") -> normalizedBase
            normalizedBase.endsWith("/v1") || normalizedBase.endsWith("/api/v1") -> "$normalizedBase/models"
            else -> "$normalizedBase/v1/models"
        }

        fun buildOpenAiModelsQueryUrl(modelsUrl: String, type: ProviderType): String {
            val isVenice = type == ProviderType.VENICE || modelsUrl.contains("venice.ai", ignoreCase = true)
            if (!isVenice || modelsUrl.contains("type=", ignoreCase = true)) return modelsUrl
            val sep = if ('?' in modelsUrl) '&' else '?'
            return "${modelsUrl}${sep}type=all"
        }

        fun parseOpenAiModelsJson(raw: String, type: ProviderType, sourceUrl: String = "models"): List<ModelInfo> {
            val root = runCatching { LlmHttpFactory.json.parseToJsonElement(raw).jsonObject }
                .getOrElse { error("Invalid JSON from $sourceUrl: ${raw.take(200)}") }
            val data = root["data"]?.jsonArray ?: return defaultPresets(type)
            val parsed = data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val spec = runCatching { obj["model_spec"]?.jsonObject }.getOrNull()
                val ctx = obj["context_length"]?.jsonPrimitive?.intOrNull
                    ?: spec?.get("availableContextTokens")?.jsonPrimitive?.intOrNull
                    ?: spec?.get("maxInputTokens")?.jsonPrimitive?.intOrNull
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: spec?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: id
                val modelType = obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                val isBeta = spec?.get("betaModel")?.jsonPrimitive?.booleanOrNull == true
                val isOffline = spec?.get("offline")?.jsonPrimitive?.booleanOrNull == true
                val isFree = id.endsWith(":free")

                val hint = buildList {
                    if (isFree) add("FREE")
                    if (modelType != null) add(modelType)
                    if (isBeta) add("beta")
                    if (isOffline) add("offline")
                }.joinToString(" · ").takeIf { it.isNotBlank() }

                val typePriority = when (modelType) {
                    null, "text", "chat", "code" -> 0
                    "embedding", "asr", "tts" -> 1
                    else -> 2
                }

                Triple(typePriority, isFree, ModelInfo(
                    id = id,
                    name = name,
                    contextLength = ctx,
                    pricingHint = hint
                ))
            }
            return parsed.sortedWith(
                compareByDescending<Triple<Int, Boolean, ModelInfo>> { it.second }
                    .thenBy { it.first }
                    .thenBy { it.third.id }
            ).map { it.third }
        }

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
