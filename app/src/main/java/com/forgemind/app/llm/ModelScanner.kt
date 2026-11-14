package com.forgemind.app.llm

import com.forgemind.app.data.model.ContextWindowResolver
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.WireProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ModelScanner(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient
) {
    suspend fun probeReachability(config: ProviderConfig): Boolean = withContext(Dispatchers.IO) {
        val rawBase = config.baseUrl.trim()
        if (rawBase.isBlank() || rawBase.contains("api.example.com", ignoreCase = true)) {
            return@withContext false
        }
        val firstKey = config.parsedKeys().firstOrNull().orEmpty()
        if (config.requiresApiKey() && firstKey.isBlank()) {
            return@withContext false
        }
        val fastClient = LlmHttpFactory.clientForConfig(config, client).newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .callTimeout(4500, TimeUnit.MILLISECONDS)
            .build()

        val proto = config.effectiveWireProtocol()
        val type = ProviderType.fromId(config.providerId)

        if (config.modelsEndpointUrl.isNotBlank()) {
            return@withContext runCatching {
                val url = LlmHttpFactory.normalizeBaseUrl(config.modelsEndpointUrl, rawBase)
                val defaultAuth = when (proto) {
                    WireProtocol.ANTHROPIC -> ProviderAuthMode.X_API_KEY
                    WireProtocol.GEMINI -> ProviderAuthMode.QUERY_KEY
                    else -> ProviderAuthMode.BEARER
                }
                executeAuthedGet(fastClient, url, config, firstKey, defaultAuth)
                true
            }.getOrDefault(false)
        }

        if (config.rawEndpointUrl || rawBase.endsWith("#")) {
            return@withContext runCatching {
                val exactUrl = rawBase.removeSuffix("#").trim()
                val reqBuilder = Request.Builder().url(exactUrl).get()
                val defaultAuth = when (proto) {
                    WireProtocol.ANTHROPIC -> ProviderAuthMode.X_API_KEY
                    WireProtocol.GEMINI -> ProviderAuthMode.QUERY_KEY
                    else -> ProviderAuthMode.BEARER
                }
                val finalUrl = LlmHttpFactory.applyProviderAuth(reqBuilder, exactUrl, config, firstKey, defaultAuth)
                reqBuilder.url(finalUrl)
                LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)
                fastClient.newCall(reqBuilder.build()).execute().use { r ->
                    r.code in 200..299 || r.code == 400 || r.code == 405
                }
            }.getOrDefault(false)
        }

        when (proto) {
            WireProtocol.ANTHROPIC -> runCatching {
                val base = LlmHttpFactory.normalizeBaseUrl(rawBase, ProviderType.ANTHROPIC.defaultBaseUrl)
                    .removeSuffix("/v1/messages")
                val modelsUrl = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
                val reqBuilder = Request.Builder()
                    .url(modelsUrl)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                val finalUrl = LlmHttpFactory.applyProviderAuth(
                    reqBuilder = reqBuilder,
                    endpointUrl = modelsUrl,
                    config = config,
                    apiKey = firstKey,
                    defaultMode = ProviderAuthMode.X_API_KEY
                )
                reqBuilder.url(finalUrl)
                LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)
                fastClient.newCall(reqBuilder.build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)

            WireProtocol.GEMINI -> runCatching {
                val base = LlmHttpFactory.normalizeBaseUrl(rawBase, ProviderType.GEMINI.defaultBaseUrl)
                    .removeSuffix("/v1beta")
                executeAuthedGet(fastClient, "$base/v1beta/models", config, firstKey, ProviderAuthMode.QUERY_KEY)
                true
            }.getOrDefault(false)

            WireProtocol.OLLAMA_NATIVE -> runCatching {
                val base = LlmHttpFactory.normalizeBaseUrl(rawBase, ProviderType.OLLAMA.defaultBaseUrl)
                    .removeSuffix("/api/chat")
                    .removeSuffix("/api")
                    .removeSuffix("/v1")
                val tagsOk = runCatching {
                    executeAuthedGet(fastClient, "$base/api/tags", config, firstKey, ProviderAuthMode.BEARER)
                    true
                }.getOrDefault(false)
                if (tagsOk) true else {
                    scanOpenAiModels(fastClient, config, type, firstKey)
                    true
                }
            }.getOrDefault(false)

            WireProtocol.OPENAI_COMPAT, WireProtocol.OPENAI_RESPONSES -> runCatching {
                val okPrimary = runCatching {
                    scanOpenAiModels(fastClient, config, type, firstKey)
                    true
                }.getOrDefault(false)
                if (okPrimary) true
                else if (config.effectiveCategory() == ProviderCategory.LOCAL_LAN || LlmHttpFactory.isLocalOrPrivateHost(rawBase)) {
                    probeLocalFallbackModels(fastClient, config, firstKey) != null
                } else {
                    false
                }
            }.getOrDefault(false)
        }
    }
    suspend fun scanModels(config: ProviderConfig): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val type = ProviderType.fromId(config.providerId)
        val proto = config.effectiveWireProtocol()
        val firstKey = config.parsedKeys().firstOrNull().orEmpty()
        val httpClient = LlmHttpFactory.clientForConfig(config, client)

        // 1) Explicit custom models endpoint override
        if (config.modelsEndpointUrl.isNotBlank()) {
            return@withContext runCatching {
                val rawUrl = LlmHttpFactory.normalizeBaseUrl(config.modelsEndpointUrl, config.baseUrl)
                val defaultAuth = when (proto) {
                    WireProtocol.ANTHROPIC -> ProviderAuthMode.X_API_KEY
                    WireProtocol.GEMINI -> ProviderAuthMode.QUERY_KEY
                    else -> ProviderAuthMode.BEARER
                }
                val body = executeAuthedGet(httpClient, rawUrl, config, firstKey, defaultAuth)
                mergeSavedModels(parseFlexibleModelsPayload(body, type, rawUrl), config)
            }
        }

        // 2) Route by effectiveWireProtocol
        when (proto) {
            WireProtocol.ANTHROPIC -> {
                val defaultAnthropicPresets = listOf(
                    ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", 200_000),
                    ModelInfo("claude-opus-4-6", "Claude Opus 4.6", 200_000),
                    ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", 200_000),
                    ModelInfo("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet", 200_000)
                )
                if (firstKey.isBlank() && config.isBuiltInProvider()) {
                    return@withContext Result.success(mergeSavedModels(defaultAnthropicPresets, config))
                }
                val live = runCatching {
                    val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, ProviderType.ANTHROPIC.defaultBaseUrl)
                        .removeSuffix("/v1/messages")
                    val modelsUrl = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
                    val reqBuilder = Request.Builder()
                        .url(modelsUrl)
                        .header("anthropic-version", "2023-06-01")
                        .get()
                    val finalUrl = LlmHttpFactory.applyProviderAuth(
                        reqBuilder = reqBuilder,
                        endpointUrl = modelsUrl,
                        config = config,
                        apiKey = firstKey,
                        defaultMode = ProviderAuthMode.X_API_KEY
                    )
                    reqBuilder.url(finalUrl)
                    LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)
                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        parseFlexibleModelsPayload(resp.body?.string().orEmpty(), type, finalUrl)
                    }
                }.getOrNull()
                return@withContext Result.success(
                    mergeSavedModels(live?.takeIf { it.isNotEmpty() } ?: defaultAnthropicPresets, config)
                )
            }

            WireProtocol.GEMINI -> {
                if (firstKey.isBlank() && config.requiresApiKey()) {
                    return@withContext Result.success(mergeSavedModels(defaultPresets(type), config))
                }
                return@withContext runCatching {
                    val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, ProviderType.GEMINI.defaultBaseUrl)
                        .removeSuffix("/v1beta")
                    val rawUrl = "$base/v1beta/models"
                    val body = executeAuthedGet(httpClient, rawUrl, config, firstKey, ProviderAuthMode.QUERY_KEY)
                    val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
                    val arr = obj["models"]?.jsonArray ?: return@runCatching mergeSavedModels(defaultPresets(type), config)
                    val models = arr.mapNotNull { el ->
                        val m = el.jsonObject
                        val id = m["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
                        val methods = m["supportedGenerationMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        if (methods != null && methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
                        ModelInfo(
                            id = id,
                            name = m["displayName"]?.jsonPrimitive?.contentOrNull ?: id,
                            contextLength = m["inputTokenLimit"]?.jsonPrimitive?.intOrNull
                        )
                    }.sortedBy { it.id }
                    mergeSavedModels(models, config)
                }
            }

            WireProtocol.OLLAMA_NATIVE -> {
                return@withContext runCatching {
                    val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, ProviderType.OLLAMA.defaultBaseUrl)
                        .removeSuffix("/api/chat")
                        .removeSuffix("/api")
                        .removeSuffix("/v1")
                    val tagsResult = runCatching {
                        val body = executeAuthedGet(httpClient, "$base/api/tags", config, firstKey, ProviderAuthMode.BEARER)
                        val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
                        val arr = obj["models"]?.jsonArray ?: error("Missing models array")
                        arr.mapNotNull { el ->
                            val m = el.jsonObject
                            val name = m["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val details = m["details"] as? JsonObject
                            val paramSize = details?.get("parameter_size")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val quant = details?.get("quantization_level")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val hint = listOfNotNull(paramSize, quant).joinToString(" · ").takeIf { it.isNotBlank() }
                            ModelInfo(id = name, name = name, pricingHint = hint)
                        }
                    }
                    val list = tagsResult.getOrElse {
                        scanOpenAiModels(httpClient, config, type, firstKey)
                    }
                    mergeSavedModels(list, config)
                }
            }

            WireProtocol.OPENAI_COMPAT, WireProtocol.OPENAI_RESPONSES -> {
                return@withContext runCatching {
                    val list = try {
                        scanOpenAiModels(httpClient, config, type, firstKey)
                    } catch (primaryErr: Exception) {
                        // LAN fallback for KoboldCpp `/api/v1/model` or Ollama `/api/tags` when user pasted bare host:port
                        if (config.effectiveCategory() == ProviderCategory.LOCAL_LAN ||
                            LlmHttpFactory.isLocalOrPrivateHost(config.baseUrl)
                        ) {
                            probeLocalFallbackModels(httpClient, config, firstKey) ?: throw primaryErr
                        } else {
                            throw primaryErr
                        }
                    }
                    mergeSavedModels(list, config)
                }
            }
        }
    }

    private fun scanOpenAiModels(
        httpClient: OkHttpClient,
        config: ProviderConfig,
        type: ProviderType,
        firstKey: String
    ): List<ModelInfo> {
        val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, type.defaultBaseUrl)
            .removeSuffix("/chat/completions")
            .removeSuffix("/responses")
        val baseModelsUrl = buildOpenAiModelsBaseUrl(base)
        val targetUrl = buildOpenAiModelsQueryUrl(baseModelsUrl, type)

        val primaryResult = runCatching {
            executeAuthedGet(httpClient, targetUrl, config, firstKey, ProviderAuthMode.BEARER)
        }
        val rawJson = if (primaryResult.isSuccess) {
            primaryResult.getOrThrow()
        } else if (targetUrl != baseModelsUrl) {
            runCatching {
                executeAuthedGet(httpClient, baseModelsUrl, config, firstKey, ProviderAuthMode.BEARER)
            }.getOrElse {
                throw primaryResult.exceptionOrNull()!!
            }
        } else {
            throw primaryResult.exceptionOrNull()!!
        }

        val parsed = parseOpenAiModelsJson(rawJson, type, targetUrl)
        if ((config.effectiveCategory() == ProviderCategory.LOCAL_LAN || LlmHttpFactory.isLocalOrPrivateHost(config.baseUrl)) &&
            parsed.size <= 250 &&
            parsed.any { it.contextLength == null || it.contextLength <= 0 }
        ) {
            val localCtxMap = probeLocalExtraContextMap(httpClient, config, firstKey)
            if (localCtxMap.isNotEmpty()) {
                return deduplicateModels(
                    parsed.map { m ->
                        val liveCtx = localCtxMap[m.id] ?: localCtxMap["*"]
                        if (liveCtx != null && (m.contextLength == null || m.contextLength <= 0)) {
                            m.copy(contextLength = liveCtx)
                        } else {
                            m
                        }
                    }
                )
            }
        }
        return parsed
    }

    suspend fun probeModelContextLength(config: ProviderConfig, modelId: String): Int? = withContext(Dispatchers.IO) {
        val cleanModel = modelId.trim()
        if (cleanModel.isEmpty()) return@withContext null
        val firstKey = config.parsedKeys().firstOrNull().orEmpty()
        val fastClient = LlmHttpFactory.clientForConfig(config, client).newBuilder()
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .build()

        when (config.effectiveWireProtocol()) {
            WireProtocol.OLLAMA_NATIVE -> runCatching {
                val base = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, ProviderType.OLLAMA.defaultBaseUrl)
                    .removeSuffix("/api/chat")
                    .removeSuffix("/api")
                    .removeSuffix("/v1")
                val payload = buildJsonObject {
                    put("model", JsonPrimitive(cleanModel))
                }.toString()
                val reqBuilder = Request.Builder()
                    .url("$base/api/show")
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                val finalUrl = LlmHttpFactory.applyProviderAuth(reqBuilder, "$base/api/show", config, firstKey, ProviderAuthMode.BEARER)
                reqBuilder.url(finalUrl)
                LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)
                fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    parseOllamaShowContextLength(resp.body?.string().orEmpty())
                }
            }.getOrNull()

            WireProtocol.OPENAI_COMPAT, WireProtocol.OPENAI_RESPONSES -> {
                if (config.effectiveCategory() == ProviderCategory.LOCAL_LAN || LlmHttpFactory.isLocalOrPrivateHost(config.baseUrl)) {
                    val map = probeLocalExtraContextMap(fastClient, config, firstKey)
                    map[cleanModel] ?: map["*"]
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun probeLocalExtraContextMap(
        httpClient: OkHttpClient,
        config: ProviderConfig,
        firstKey: String
    ): Map<String, Int> {
        val root = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, "http://127.0.0.1:1234")
            .removeSuffix("/chat/completions")
            .removeSuffix("/responses")
            .removeSuffix("/v1")
            .removeSuffix("/api/v1")
        val map = LinkedHashMap<String, Int>()
        // 1) LM Studio `/api/v0/models` (reports loaded_context_length & max_context_length)
        runCatching {
            val body = executeAuthedGet(httpClient, "$root/api/v0/models", config, firstKey, ProviderAuthMode.BEARER)
            val arr = LlmHttpFactory.json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            arr?.forEach { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val ctx = obj["loaded_context_length"]?.jsonPrimitive?.intOrNull
                    ?: obj["max_context_length"]?.jsonPrimitive?.intOrNull
                if (id.isNotEmpty() && ctx != null && ctx >= 1024) {
                    map[id] = ctx
                }
            }
        }
        // 2) llama.cpp `/props` (reports default_generation_settings.n_ctx)
        if (map.isEmpty()) {
            runCatching {
                val body = executeAuthedGet(httpClient, "$root/props", config, firstKey, ProviderAuthMode.BEARER)
                val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
                val nCtx = obj["default_generation_settings"]?.jsonObject?.get("n_ctx")?.jsonPrimitive?.intOrNull
                    ?: obj["n_ctx"]?.jsonPrimitive?.intOrNull
                if (nCtx != null && nCtx >= 1024) {
                    map["*"] = nCtx
                }
            }
        }
        // 3) KoboldCpp `/api/extra/true_max_context_length`
        if (map.isEmpty()) {
            runCatching {
                val body = executeAuthedGet(httpClient, "$root/api/extra/true_max_context_length", config, firstKey, ProviderAuthMode.BEARER)
                val v = LlmHttpFactory.json.parseToJsonElement(body).jsonObject["value"]?.jsonPrimitive?.intOrNull
                if (v != null && v >= 1024) {
                    map["*"] = v
                }
            }
        }
        return map
    }

    private fun probeLocalFallbackModels(
        httpClient: OkHttpClient,
        config: ProviderConfig,
        firstKey: String
    ): List<ModelInfo>? {
        val root = LlmHttpFactory.normalizeBaseUrl(config.baseUrl, "http://127.0.0.1:8080")
            .removeSuffix("/v1")
            .removeSuffix("/api/v1")
        // Try KoboldCpp `/api/v1/model`
        runCatching {
            val body = executeAuthedGet(httpClient, "$root/api/v1/model", config, firstKey, ProviderAuthMode.BEARER)
            val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
            val modelName = obj["result"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "ReadOnly" }
            if (modelName != null) return listOf(ModelInfo(id = modelName, name = modelName, pricingHint = "KoboldCpp"))
        }
        // Try Ollama `/api/tags`
        runCatching {
            val body = executeAuthedGet(httpClient, "$root/api/tags", config, firstKey, ProviderAuthMode.BEARER)
            val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
            val arr = obj["models"]?.jsonArray
            if (arr != null) {
                return deduplicateModels(
                    arr.mapNotNull { el ->
                        val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ModelInfo(id = name, name = name, pricingHint = "Ollama")
                    }
                )
            }
        }
        return null
    }

    private fun executeAuthedGet(
        httpClient: OkHttpClient,
        rawUrl: String,
        config: ProviderConfig,
        firstKey: String,
        defaultAuthMode: ProviderAuthMode
    ): String {
        val reqBuilder = Request.Builder()
            .url(rawUrl)
            .get()
        val finalUrl = LlmHttpFactory.applyProviderAuth(
            reqBuilder = reqBuilder,
            endpointUrl = rawUrl,
            config = config,
            apiKey = firstKey,
            defaultMode = defaultAuthMode
        )
        reqBuilder.url(finalUrl)
        LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)
        val resp = try {
            httpClient.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            error("Network error ($finalUrl): ${e.message ?: e::class.java.simpleName}")
        }
        return resp.use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} ($finalUrl): ${r.body?.string().orEmpty().take(300)}")
            r.body?.string().orEmpty()
        }
    }

    companion object {
        private val OLLAMA_NUM_CTX_REGEX = Regex("""(?m)^\s*num_ctx\s+(\d+)""")

        fun parseOllamaShowContextLength(raw: String): Int? = runCatching {
            val obj = LlmHttpFactory.json.parseToJsonElement(raw).jsonObject
            // 1) Explicit Modelfile `num_ctx` override in `parameters` string
            val paramsText = obj["parameters"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val explicitNumCtx = OLLAMA_NUM_CTX_REGEX.find(paramsText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (explicitNumCtx != null && explicitNumCtx >= 1024) return@runCatching explicitNumCtx

            // 2) Architecture `*.context_length` inside `model_info` (e.g. "qwen2.context_length", "llama.context_length")
            val info = obj["model_info"] as? JsonObject
            if (info != null) {
                for ((k, v) in info) {
                    if (k.endsWith("context_length", ignoreCase = true)) {
                        val n = (v as? JsonPrimitive)?.intOrNull
                        if (n != null && n >= 1024) return@runCatching n
                    }
                }
            }
            null
        }.getOrNull()

        private const val MAX_MODEL_DISPLAY_NAME_LEN = 180
        private const val MAX_PRICING_HINT_LEN = 64

        /**
         * Guarantees strictly unique, non-blank trimmed `id` values across arbitrarily large
         * aggregator catalogs (1,000–15,000+ models) so Compose `LazyColumn` keys never collide,
         * while merging context lengths, display names, and modality hints from duplicate entries.
         */
        fun deduplicateModels(models: Iterable<ModelInfo>): List<ModelInfo> {
            val map = LinkedHashMap<String, ModelInfo>()
            for (raw in models) {
                val cleanId = raw.id.trim()
                if (cleanId.isEmpty()) continue
                val cleanName = raw.name.trim().ifEmpty { cleanId }.take(MAX_MODEL_DISPLAY_NAME_LEN)
                val cleanCtx = raw.contextLength?.takeIf { it > 0 }
                val cleanHint = raw.pricingHint?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PRICING_HINT_LEN)

                val existing = map[cleanId]
                if (existing == null) {
                    map[cleanId] = ModelInfo(
                        id = cleanId,
                        name = cleanName,
                        contextLength = cleanCtx,
                        pricingHint = cleanHint
                    )
                } else {
                    val mergedName = if (existing.name != cleanId && existing.name.isNotBlank()) {
                        existing.name
                    } else {
                        cleanName
                    }
                    val mergedCtx = when {
                        existing.contextLength != null && cleanCtx != null -> maxOf(existing.contextLength, cleanCtx)
                        existing.contextLength != null -> existing.contextLength
                        else -> cleanCtx
                    }
                    val mergedHint = mergeHintTokens(existing.pricingHint, cleanHint)
                    map[cleanId] = existing.copy(
                        name = mergedName,
                        contextLength = mergedCtx,
                        pricingHint = mergedHint
                    )
                }
            }
            return ArrayList(map.values)
        }

        private fun mergeHintTokens(first: String?, second: String?): String? {
            if (first.isNullOrBlank()) return second?.take(MAX_PRICING_HINT_LEN)
            if (second.isNullOrBlank() || first.equals(second, ignoreCase = true)) return first.take(MAX_PRICING_HINT_LEN)
            val seenLower = HashSet<String>()
            val tokens = ArrayList<String>(4)
            for (src in listOf(first, second)) {
                for (part in src.split("·")) {
                    val t = part.trim()
                    if (t.isNotEmpty() && seenLower.add(t.lowercase())) {
                        tokens.add(t)
                        if (tokens.size >= 4) break
                    }
                }
                if (tokens.size >= 4) break
            }
            return tokens.joinToString(" · ").take(MAX_PRICING_HINT_LEN).takeIf { it.isNotBlank() }
        }

        fun mergeSavedModels(scanned: List<ModelInfo>, config: ProviderConfig): List<ModelInfo> {
            val cleanScanned = deduplicateModels(scanned)
            if (config.savedModels.isEmpty()) return cleanScanned
            val existingIds = cleanScanned.mapTo(HashSet(cleanScanned.size * 2)) { it.id }
            val seenPinned = HashSet<String>()
            val pinned = config.savedModels.mapNotNull { raw ->
                val id = raw.trim()
                if (id.isNotEmpty() && id !in existingIds && seenPinned.add(id)) {
                    ModelInfo(id = id, name = id, pricingHint = "PINNED")
                } else {
                    null
                }
            }
            return if (pinned.isEmpty()) cleanScanned else pinned + cleanScanned
        }

        fun buildOpenAiModelsBaseUrl(normalizedBase: String): String = when {
            normalizedBase.endsWith("/models") -> normalizedBase
            normalizedBase.endsWith("/v1") || normalizedBase.endsWith("/api/v1") || normalizedBase.endsWith("/ai/v1") -> "$normalizedBase/models"
            else -> "$normalizedBase/v1/models"
        }

        fun buildOpenAiModelsQueryUrl(modelsUrl: String, type: ProviderType): String {
            val isVenice = type == ProviderType.VENICE || modelsUrl.contains("venice.ai", ignoreCase = true)
            if (!isVenice || modelsUrl.contains("type=", ignoreCase = true)) return modelsUrl
            val sep = if ('?' in modelsUrl) '&' else '?'
            return "${modelsUrl}${sep}type=all"
        }

        fun parseFlexibleModelsPayload(raw: String, type: ProviderType, sourceUrl: String = "models"): List<ModelInfo> {
            val element = runCatching { LlmHttpFactory.json.parseToJsonElement(raw) }
                .getOrElse { error("Invalid JSON from $sourceUrl: ${raw.take(200)}") }
            if (element is JsonArray) {
                return deduplicateModels(
                    element.mapNotNull { item ->
                        when (item) {
                            is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { ModelInfo(it, it) }
                            is JsonObject -> {
                                val id = (item["id"]?.jsonPrimitive?.contentOrNull
                                    ?: item["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/"))
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: return@mapNotNull null
                                val name = item["display_name"]?.jsonPrimitive?.contentOrNull
                                    ?: item["displayName"]?.jsonPrimitive?.contentOrNull
                                    ?: item["name"]?.jsonPrimitive?.contentOrNull
                                    ?: id
                                ModelInfo(id = id, name = name)
                            }
                            else -> null
                        }
                    }
                )
            }
            val obj = element.jsonObject
            if ("data" in obj) return parseOpenAiModelsJson(raw, type, sourceUrl)
            val modelsArr = obj["models"] as? JsonArray
            if (modelsArr != null) {
                return deduplicateModels(
                    modelsArr.mapNotNull { el ->
                        val m = el as? JsonObject ?: return@mapNotNull null
                        val id = (m["id"]?.jsonPrimitive?.contentOrNull
                            ?: m["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/"))
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        val name = m["displayName"]?.jsonPrimitive?.contentOrNull
                            ?: m["display_name"]?.jsonPrimitive?.contentOrNull
                            ?: id
                        ModelInfo(
                            id = id,
                            name = name,
                            contextLength = m["inputTokenLimit"]?.jsonPrimitive?.intOrNull
                                ?: m["context_length"]?.jsonPrimitive?.intOrNull
                        )
                    }
                )
            }
            return defaultPresets(type)
        }

        fun parseOpenAiModelsJson(raw: String, type: ProviderType, sourceUrl: String = "models"): List<ModelInfo> {
            val root = runCatching { LlmHttpFactory.json.parseToJsonElement(raw).jsonObject }
                .getOrElse { error("Invalid JSON from $sourceUrl: ${raw.take(200)}") }
            val data = root["data"]?.jsonArray ?: return defaultPresets(type)
            val parsed = ArrayList<Triple<Int, Boolean, ModelInfo>>(data.size)
            for (el in data) {
                val obj = el as? JsonObject ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val spec = runCatching { obj["model_spec"]?.jsonObject }.getOrNull()
                val ctx = obj["loaded_context_length"]?.jsonPrimitive?.intOrNull
                    ?: obj["context_length"]?.jsonPrimitive?.intOrNull
                    ?: obj["max_model_len"]?.jsonPrimitive?.intOrNull
                    ?: obj["max_context_length"]?.jsonPrimitive?.intOrNull
                    ?: obj["n_ctx"]?.jsonPrimitive?.intOrNull
                    ?: obj["context_window"]?.jsonPrimitive?.intOrNull
                    ?: obj["num_ctx"]?.jsonPrimitive?.intOrNull
                    ?: spec?.get("availableContextTokens")?.jsonPrimitive?.intOrNull
                    ?: spec?.get("maxInputTokens")?.jsonPrimitive?.intOrNull
                val name = obj["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: spec?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: id
                val modelType = obj["type"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.length in 1..24 && it != "model" }
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

                parsed.add(
                    Triple(
                        typePriority,
                        isFree,
                        ModelInfo(
                            id = id,
                            name = name,
                            contextLength = ctx,
                            pricingHint = hint
                        )
                    )
                )
            }
            parsed.sortWith(
                compareByDescending<Triple<Int, Boolean, ModelInfo>> { it.second }
                    .thenBy { it.first }
                    .thenBy { it.third.id }
            )
            return deduplicateModels(parsed.map { it.third })
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
            ProviderType.OLLAMA -> listOf(
                ModelInfo("qwen2.5-coder:7b", "Qwen 2.5 Coder 7B", 32_768),
                ModelInfo("qwen3:8b", "Qwen 3 8B", 32_768),
                ModelInfo("llama3.2:3b", "Llama 3.2 3B", 32_768),
                ModelInfo("deepseek-r1:8b", "DeepSeek R1 8B", 32_768)
            )
            else -> listOf(ModelInfo(type.defaultModel, type.defaultModel, type.defaultContextWindow))
        }
    }
}
