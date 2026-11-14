package com.forgemind.app.llm

import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.ToolCallInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Native Ollama `/api/chat` NDJSON streaming client.
 * Passes explicit `options.num_ctx = config.contextWindow` so Ollama does not silently
 * truncate 50+ agent tool schemas to a 2048-token default window.
 */
class OllamaNativeClient(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient,
    private val openAiFallback: OpenAiCompatibleClient = OpenAiCompatibleClient(client)
) : LlmClient {

    override fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent> = flow {
        val provider = ProviderType.fromId(config.providerId)
        val providerTitle = config.customDisplayName.ifBlank { provider.displayName }
        val rawKeys = config.parsedKeys()
        if (config.requiresApiKey() && rawKeys.isEmpty()) {
            emit(LlmStreamEvent.Error("Не указан API-ключ для $providerTitle.", retryable = false))
            return@flow
        }

        val httpClient = LlmHttpFactory.clientForConfig(config, client)
        val keyPool = if (rawKeys.isEmpty()) listOf("") else rawKeys
        var includeTools = tools.isNotEmpty()
        var includeThinkFlag = config.enableThinking && modelLikelySupportsNativeThink(config.modelId)
        var lastError: LlmStreamEvent.Error? = null

        keyLoop@ for ((keyIdx, apiKey) in keyPool.withIndex()) {
            if (keyIdx > 0 && !config.autoRotateKeys) break
            var attempts = 0
            while (attempts < 3) {
                attempts++
                var emittedAny = false
                var retrySameKey = false
                var rotateKey = false
                var fallbackToOpenAi = false

                val baseEndpoint = LlmHttpFactory.resolveEndpointUrl(
                    config = config,
                    defaultBaseUrl = provider.defaultBaseUrl,
                    buildStandardEndpoint = { normalized -> normalizeOllamaChatEndpoint(normalized) }
                )
                val effectiveMessages = if (!includeTools && tools.isNotEmpty()) {
                    LlmHttpFactory.downgradeWireMessagesForTextTools(messages, tools)
                } else {
                    messages
                }
                val bodyJson = buildRequestBody(
                    config = config,
                    messages = effectiveMessages,
                    tools = if (includeTools) tools else emptyList(),
                    includeThinkFlag = includeThinkFlag
                )

                val reqBuilder = Request.Builder()
                    .header("Accept", "application/x-ndjson, application/json")
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

                val finalUrl = LlmHttpFactory.applyProviderAuth(
                    reqBuilder = reqBuilder,
                    endpointUrl = baseEndpoint,
                    config = config,
                    apiKey = apiKey,
                    defaultMode = if (apiKey.isNotBlank()) ProviderAuthMode.BEARER else ProviderAuthMode.NONE
                )
                reqBuilder.url(finalUrl)
                LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)

                val call = httpClient.newCall(reqBuilder.build())
                val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errText = resp.body?.string().orEmpty().take(1500)
                            val code = resp.code
                            if (code == 404 && !config.rawEndpointUrl && !config.baseUrl.trim().endsWith("#")) {
                                fallbackToOpenAi = true
                                return@use
                            }
                            if (code in listOf(400, 422) && includeThinkFlag &&
                                errText.contains("think", ignoreCase = true)
                            ) {
                                includeThinkFlag = false
                                attempts = (attempts - 1).coerceAtLeast(0)
                                retrySameKey = true
                                return@use
                            }
                            if (includeTools && LlmHttpFactory.isToolUnsupportedHttpError(code, errText)) {
                                includeTools = false
                                attempts = (attempts - 1).coerceAtLeast(0)
                                retrySameKey = true
                                return@use
                            }
                            if (LlmHttpFactory.isKeyRotationHttpCode(code) && keyIdx + 1 < keyPool.size && config.autoRotateKeys) {
                                lastError = LlmStreamEvent.Error("HTTP $code: $errText", code, retryable = true)
                                rotateKey = true
                                return@use
                            }
                            if (LlmHttpFactory.isTransientHttpCode(code) && attempts < 3) {
                                lastError = LlmStreamEvent.Error("HTTP $code: $errText", code, retryable = true)
                                retrySameKey = true
                                return@use
                            }
                            emit(LlmStreamEvent.Error("$providerTitle HTTP $code: $errText", code, retryable = false))
                            return@flow
                        }

                        val source = resp.body?.source()
                        if (source == null) {
                            lastError = LlmStreamEvent.Error("Пустой поток ответа от $providerTitle", retryable = true)
                            retrySameKey = attempts < 3
                            return@use
                        }

                        var finishReason = "stop"
                        var promptTok = 0
                        var completionTok = 0
                        var toolCounter = 0

                        while (!source.exhausted()) {
                            val rawLine = source.readUtf8Line()?.trim() ?: break
                            if (rawLine.isEmpty()) continue
                            val cleanJson = if (rawLine.startsWith("data:")) {
                                rawLine.removePrefix("data:").trim()
                            } else {
                                rawLine
                            }
                            if (cleanJson.isEmpty() || cleanJson == "[DONE]") continue

                            val obj = runCatching { LlmHttpFactory.json.parseToJsonElement(cleanJson).jsonObject }
                                .getOrNull() ?: continue

                            obj["error"]?.let { errEl ->
                                val msg = (errEl as? JsonPrimitive)?.contentOrNull
                                    ?: runCatching { errEl.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                                    ?: errEl.toString()
                                emit(LlmStreamEvent.Error("$providerTitle: $msg", retryable = false))
                                return@flow
                            }

                            val msgObj = obj["message"] as? JsonObject
                            if (msgObj != null) {
                                val thinking = (msgObj["thinking"] as? JsonPrimitive)?.contentOrNull
                                    ?: (msgObj["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                                if (!thinking.isNullOrEmpty()) {
                                    emittedAny = true
                                    emit(LlmStreamEvent.ThinkingDelta(thinking))
                                }

                                val content = (msgObj["content"] as? JsonPrimitive)?.contentOrNull
                                if (!content.isNullOrEmpty()) {
                                    emittedAny = true
                                    emit(LlmStreamEvent.TextDelta(content))
                                }

                                val tcArr = msgObj["tool_calls"] as? JsonArray
                                if (tcArr != null) {
                                    for (tcEl in tcArr) {
                                        val tc = tcEl as? JsonObject ?: continue
                                        val fn = tc["function"] as? JsonObject ?: continue
                                        val fnName = (fn["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                                        if (fnName.isEmpty()) continue
                                        val rawArgs = fn["arguments"]
                                        val argsJson = when (rawArgs) {
                                            null -> "{}"
                                            is JsonPrimitive -> rawArgs.contentOrNull?.ifBlank { "{}" } ?: "{}"
                                            else -> rawArgs.toString()
                                        }
                                        val callId = (tc["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                                            ?: "ollama_call_${System.nanoTime()}_${toolCounter++}"
                                        emittedAny = true
                                        finishReason = "tool_calls"
                                        emit(
                                            LlmStreamEvent.ToolCallReady(
                                                ToolCallInfo(
                                                    id = callId,
                                                    name = fnName,
                                                    argumentsJson = argsJson
                                                )
                                            )
                                        )
                                    }
                                }
                            }

                            obj["done_reason"]?.let { dr ->
                                (dr as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                    if (finishReason != "tool_calls") finishReason = it
                                }
                            }
                            promptTok = (obj["prompt_eval_count"] as? JsonPrimitive)?.intOrNull ?: promptTok
                            completionTok = (obj["eval_count"] as? JsonPrimitive)?.intOrNull ?: completionTok
                        }

                        if (promptTok > 0 || completionTok > 0) {
                            emit(LlmStreamEvent.Usage(promptTok, completionTok))
                        }
                        emit(LlmStreamEvent.Done(finishReason))
                        return@flow
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastError = LlmStreamEvent.Error("Сетевая ошибка $providerTitle: ${e.message ?: e::class.simpleName}", retryable = true)
                    if (!emittedAny && attempts < 3) {
                        retrySameKey = true
                    } else {
                        emit(lastError!!)
                        return@flow
                    }
                } finally {
                    cancelHook?.dispose()
                }

                if (fallbackToOpenAi) {
                    emitAll(openAiFallback.streamChat(config, messages, tools))
                    return@flow
                }
                if (rotateKey) continue@keyLoop
                if (retrySameKey) {
                    delay(650L * attempts)
                    continue
                }
                break
            }
        }

        emit(lastError ?: LlmStreamEvent.Error("Сервер $providerTitle недоступен.", retryable = false))
    }.flowOn(Dispatchers.IO)

    companion object {
        fun normalizeOllamaRootUrl(normalizedBase: String): String =
            normalizedBase
                .removeSuffix("/api/chat")
                .removeSuffix("/api/tags")
                .removeSuffix("/v1")
                .trimEnd('/')

        fun normalizeOllamaChatEndpoint(normalizedBase: String): String {
            if (normalizedBase.endsWith("/api/chat")) return normalizedBase
            return "${normalizeOllamaRootUrl(normalizedBase)}/api/chat"
        }

        fun modelLikelySupportsNativeThink(modelId: String): Boolean {
            val m = modelId.lowercase()
            return m.contains("qwen3") || m.contains("deepseek-r1") || m.contains("qwq") ||
                m.contains("gpt-oss") || m.contains("magistral") || m.contains("cogito") ||
                m.contains("thinking") || m.endsWith("-r1") || m.contains(":r1")
        }

        fun buildRequestBody(
            config: ProviderConfig,
            messages: List<LlmWireMessage>,
            tools: List<LlmToolSpec>,
            includeThinkFlag: Boolean
        ): JsonObject {
            val core = buildJsonObject {
                put("model", JsonPrimitive(config.modelId.trim()))
                put("stream", JsonPrimitive(true))
                if (includeThinkFlag) {
                    put("think", JsonPrimitive(true))
                }
                put("options", buildJsonObject {
                    put("num_ctx", JsonPrimitive(config.contextWindow.coerceAtLeast(2048)))
                    put("temperature", JsonPrimitive(config.temperature))
                    put("num_predict", JsonPrimitive(config.maxTokens))
                })
                put("messages", buildJsonArray {
                    for (msg in messages) {
                        add(buildOllamaMessage(msg))
                    }
                })
                if (tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        for (t in tools) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put("function", buildJsonObject {
                                    put("name", JsonPrimitive(t.name))
                                    put("description", JsonPrimitive(t.description))
                                    put("parameters", t.parametersSchema)
                                })
                            })
                        }
                    })
                }
            }
            return LlmHttpFactory.mergeExtraBody(core, config.extraBodyJson)
        }

        private fun buildOllamaMessage(msg: LlmWireMessage): JsonObject = buildJsonObject {
            val roleStr = when (msg.role) {
                LlmRole.SYSTEM -> "system"
                LlmRole.USER -> "user"
                LlmRole.ASSISTANT -> "assistant"
                LlmRole.TOOL -> "tool"
            }
            put("role", JsonPrimitive(roleStr))
            val extraFilesText = LlmHttpFactory.formatNonImageAttachments(msg.attachments)
            val fullText = (msg.content + extraFilesText).trim()
            put("content", JsonPrimitive(fullText))

            if (msg.role == LlmRole.USER) {
                val imageB64List = msg.attachments
                    .filter { it.kind == AttachmentKind.IMAGE }
                    .mapNotNull { LlmHttpFactory.readAttachmentAsBase64(it) }
                if (imageB64List.isNotEmpty()) {
                    put("images", buildJsonArray {
                        imageB64List.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }

            if (msg.role == LlmRole.TOOL && !msg.toolName.isNullOrBlank()) {
                put("tool_name", JsonPrimitive(msg.toolName))
            }

            if (msg.role == LlmRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    for (tc in msg.toolCalls) {
                        val argsObj: JsonElement = runCatching {
                            val parsed = LlmHttpFactory.json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" })
                            if (parsed is JsonObject) parsed else buildJsonObject {}
                        }.getOrElse { buildJsonObject {} }
                        add(buildJsonObject {
                            put("function", buildJsonObject {
                                put("name", JsonPrimitive(tc.name))
                                put("arguments", argsObj)
                            })
                        })
                    }
                })
            }
        }
    }
}
