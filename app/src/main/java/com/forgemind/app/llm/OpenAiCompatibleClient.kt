package com.forgemind.app.llm

import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.ToolCallInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class OpenAiCompatibleClient(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient
) : LlmClient {

    override fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent> = flow {
        val provider = ProviderType.fromId(config.providerId)
        val rawKeys = config.parsedKeys()
        val requiresKey = provider != ProviderType.OLLAMA && provider != ProviderType.CUSTOM
        if (requiresKey && rawKeys.isEmpty()) {
            emit(LlmStreamEvent.Error("Не указан API-ключ для ${provider.displayName}. Откройте Настройки -> Провайдеры.", retryable = false))
            return@flow
        }

        val keyPool = if (rawKeys.isEmpty()) listOf("") else rawKeys
        var includeStreamOptions = provider in setOf(
            ProviderType.OPENAI, ProviderType.OPENROUTER, ProviderType.GROQ,
            ProviderType.XAI_GROK, ProviderType.VENICE
        )
        var includeTools = tools.isNotEmpty()
        var lastError: LlmStreamEvent.Error? = null

        keyLoop@ for ((keyIdx, apiKey) in keyPool.withIndex()) {
            if (keyIdx > 0 && !config.autoRotateKeys) break
            if (keyIdx > 0) {
                emit(LlmStreamEvent.StatusNote("Ротация API-ключа (${keyIdx + 1}/${keyPool.size})..."))
            }

            var attempts = 0
            while (attempts < 3) {
                attempts++
                var emittedContent = false
                var shouldRotateKey = false
                var shouldRetrySameKey = false

                val bodyJson = buildRequestBody(
                    config = config,
                    provider = provider,
                    messages = messages,
                    tools = if (includeTools) tools else emptyList(),
                    includeStreamOptions = includeStreamOptions
                )

                val endpoint = normalizeChatEndpoint(config.baseUrl.ifBlank { provider.defaultBaseUrl })
                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

                if (apiKey.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $apiKey")
                }
                if (provider == ProviderType.OPENROUTER) {
                    reqBuilder.header("HTTP-Referer", "https://github.com/forgemind-android")
                    reqBuilder.header("X-Title", "ForgeMind Android IDE")
                }
                LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)

                val call = client.newCall(reqBuilder.build())
                val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val errText = response.body?.string().orEmpty().take(1500)
                            val code = response.code
                            // Graceful fallback if endpoint rejects stream_options
                            if (code == 400 && includeStreamOptions && errText.contains("stream_options", ignoreCase = true)) {
                                includeStreamOptions = false
                                attempts = (attempts - 1).coerceAtLeast(0)
                                shouldRetrySameKey = true
                                return@use
                            }
                            // Graceful fallback if model does not support tools
                            if (code in listOf(400, 422) && includeTools &&
                                (errText.contains("tool", ignoreCase = true) || errText.contains("function", ignoreCase = true))
                            ) {
                                includeTools = false
                                attempts = (attempts - 1).coerceAtLeast(0)
                                shouldRetrySameKey = true
                                return@use
                            }
                            if (LlmHttpFactory.isKeyRotationHttpCode(code) && keyIdx + 1 < keyPool.size && config.autoRotateKeys) {
                                lastError = LlmStreamEvent.Error("HTTP $code: $errText", code, retryable = true)
                                shouldRotateKey = true
                                return@use
                            }
                            if (LlmHttpFactory.isTransientHttpCode(code) && attempts < 3) {
                                lastError = LlmStreamEvent.Error("Временная ошибка HTTP $code: $errText", code, retryable = true)
                                shouldRetrySameKey = true
                                return@use
                            }
                            emit(LlmStreamEvent.Error("${provider.displayName} HTTP $code: $errText", code, retryable = false))
                            return@flow
                        }

                        val source = response.body?.source()
                        if (source == null) {
                            lastError = LlmStreamEvent.Error("Пустой поток ответа", retryable = true)
                            shouldRetrySameKey = attempts < 3
                            return@use
                        }

                        val toolBuffers = linkedMapOf<Int, MutableToolCallBuffer>()
                        val reportedIdxToBufferKey = mutableMapOf<Int, Int>()
                        var finishReason = "stop"
                        var eventDataLines = mutableListOf<String>()

                        suspend fun flushSseEvent() {
                            if (eventDataLines.isEmpty()) return
                            val payload = eventDataLines.joinToString("\n").trim()
                            eventDataLines.clear()
                            if (payload.isEmpty()) return
                            if (payload == "[DONE]") return

                            val root = runCatching {
                                LlmHttpFactory.json.parseToJsonElement(payload).jsonObject
                            }.getOrNull() ?: return

                            // OpenRouter/Groq can send embedded {"error": ...} inside 200 stream
                            root["error"]?.let { errEl ->
                                val msg = runCatching { errEl.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                                    ?: errEl.toString()
                                throw StreamEmbeddedException(msg)
                            }

                            root["usage"]?.let { uEl ->
                                runCatching {
                                    val u = uEl.jsonObject
                                    val p = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    val c = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                                    if (p > 0 || c > 0) {
                                        emit(LlmStreamEvent.Usage(p, c))
                                    }
                                }
                            }

                            val choices = root["choices"] as? JsonArray ?: return
                            for (choiceEl in choices) {
                                val choice = choiceEl as? JsonObject ?: continue
                                (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { fr ->
                                    if (fr.isNotBlank()) finishReason = fr
                                }
                                val delta = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject) ?: continue

                                val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                                    ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
                                    ?: (delta["thinking"] as? JsonPrimitive)?.contentOrNull
                                if (!reasoning.isNullOrEmpty()) {
                                    emittedContent = true
                                    emit(LlmStreamEvent.ThinkingDelta(reasoning))
                                }

                                val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
                                if (!content.isNullOrEmpty()) {
                                    emittedContent = true
                                    emit(LlmStreamEvent.TextDelta(content))
                                }

                                val tcArray = delta["tool_calls"] as? JsonArray
                                if (tcArray != null) {
                                    emittedContent = true
                                    for (tcEl in tcArray) {
                                        val tcObj = tcEl as? JsonObject ?: continue
                                        val idx = (tcObj["index"] as? JsonPrimitive)?.intOrNull ?: 0
                                        val chunkId = (tcObj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                                        var targetKey = reportedIdxToBufferKey[idx] ?: idx
                                        var buf = toolBuffers.getOrPut(targetKey) { MutableToolCallBuffer() }
                                        if (chunkId != null) {
                                            if (buf.id.isNotEmpty() && buf.id != chunkId && buf.name.isNotEmpty()) {
                                                targetKey = (toolBuffers.keys.maxOrNull() ?: idx) + 1
                                                buf = toolBuffers.getOrPut(targetKey) { MutableToolCallBuffer() }
                                            }
                                            reportedIdxToBufferKey[idx] = targetKey
                                            buf.id = chunkId
                                        }
                                        val fn = tcObj["function"] as? JsonObject
                                        (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { n ->
                                            if (n.isNotEmpty() && buf.name != n) buf.name += n
                                        }
                                        (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { buf.args.append(it) }
                                    }
                                }
                            }
                        }

                        val rawNonSseLines = mutableListOf<String>()
                        var sawSseData = false
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                flushSseEvent()
                            } else if (line.startsWith("data:")) {
                                sawSseData = true
                                eventDataLines += line.removePrefix("data:").trimStart()
                            } else if (!sawSseData && !line.startsWith(":") &&
                                !line.startsWith("event:") && !line.startsWith("id:") && !line.startsWith("retry:")
                            ) {
                                rawNonSseLines += line
                            }
                        }
                        flushSseEvent()
                        if (!sawSseData && rawNonSseLines.isNotEmpty()) {
                            eventDataLines += rawNonSseLines.joinToString("\n")
                            flushSseEvent()
                        }

                        for ((idx, buf) in toolBuffers.toSortedMap()) {
                            if (buf.name.isNotBlank()) {
                                val finalId = buf.id.ifBlank { "call_${System.nanoTime()}_$idx" }
                                val argsText = buf.args.toString().ifBlank { "{}" }
                                emit(
                                    LlmStreamEvent.ToolCallReady(
                                        ToolCallInfo(
                                            id = finalId,
                                            name = buf.name.trim(),
                                            argumentsJson = argsText
                                        )
                                    )
                                )
                            }
                        }
                        emit(LlmStreamEvent.Done(finishReason))
                        return@flow
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: StreamEmbeddedException) {
                    val msg = e.message ?: "Stream error"
                    if (!emittedContent && msg.contains("rate", ignoreCase = true)) {
                        if (keyIdx + 1 < keyPool.size && config.autoRotateKeys) {
                            shouldRotateKey = true
                            lastError = LlmStreamEvent.Error(msg, retryable = true)
                        } else if (attempts < 3) {
                            shouldRetrySameKey = true
                            lastError = LlmStreamEvent.Error(msg, retryable = true)
                        } else {
                            emit(LlmStreamEvent.Error(msg, retryable = false))
                            return@flow
                        }
                    } else {
                        emit(LlmStreamEvent.Error(msg, retryable = false))
                        return@flow
                    }
                } catch (e: Exception) {
                    lastError = LlmStreamEvent.Error("Сетевая ошибка: ${e.message ?: e::class.simpleName}", retryable = true)
                    if (!emittedContent && attempts < 3) {
                        shouldRetrySameKey = true
                    } else {
                        emit(lastError!!)
                        return@flow
                    }
                } finally {
                    cancelHook?.dispose()
                }

                if (shouldRotateKey) continue@keyLoop
                if (shouldRetrySameKey) {
                    delay(750L * attempts)
                    continue
                }
                break
            }
        }

        emit(lastError ?: LlmStreamEvent.Error("Все API-ключи исчерпаны или недоступны.", retryable = false))
    }.flowOn(Dispatchers.IO)

    private class MutableToolCallBuffer(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder()
    )

    private class StreamEmbeddedException(message: String) : Exception(message)

    companion object {
        fun normalizeChatEndpoint(baseUrl: String): String {
            val clean = LlmHttpFactory.normalizeBaseUrl(baseUrl, "https://api.openai.com/v1")
            return when {
                clean.endsWith("/chat/completions") -> clean
                clean.endsWith("/v1") || clean.endsWith("/api/v1") || clean.endsWith("/ai/v1") -> "$clean/chat/completions"
                clean.contains("api.cloudflare.com") && !clean.endsWith("/v1") -> "$clean/v1/chat/completions"
                else -> "$clean/v1/chat/completions"
            }
        }

        fun buildRequestBody(
            config: ProviderConfig,
            provider: ProviderType,
            messages: List<LlmWireMessage>,
            tools: List<LlmToolSpec>,
            includeStreamOptions: Boolean
        ): JsonObject = buildJsonObject {
            put("model", JsonPrimitive(config.modelId.trim()))
            put("stream", JsonPrimitive(true))
            if (includeStreamOptions) {
                put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
            }

            val isReasoningOpenAi = provider == ProviderType.OPENAI &&
                (config.modelId.startsWith("o1") || config.modelId.startsWith("o3") ||
                    config.modelId.startsWith("o4") || config.modelId.startsWith("gpt-5"))
            if (!isReasoningOpenAi) {
                put("temperature", JsonPrimitive(config.temperature))
            }
            if (isReasoningOpenAi) {
                put("max_completion_tokens", JsonPrimitive(config.maxTokens))
            } else {
                put("max_tokens", JsonPrimitive(config.maxTokens))
            }

            put("messages", buildJsonArray {
                for (msg in messages) {
                    add(buildWireMessage(msg))
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
                put("tool_choice", JsonPrimitive("auto"))
            }
        }

        private fun buildWireMessage(msg: LlmWireMessage): JsonObject = buildJsonObject {
            val roleStr = when (msg.role) {
                LlmRole.SYSTEM -> "system"
                LlmRole.USER -> "user"
                LlmRole.ASSISTANT -> "assistant"
                LlmRole.TOOL -> "tool"
            }
            put("role", JsonPrimitive(roleStr))

            val extraFilesText = LlmHttpFactory.formatNonImageAttachments(msg.attachments)
            val fullText = (msg.content + extraFilesText).trim()
            val images = msg.attachments.filter { it.kind == AttachmentKind.IMAGE }

            if (msg.role == LlmRole.USER && images.isNotEmpty()) {
                put("content", buildJsonArray {
                    if (fullText.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(fullText))
                        })
                    }
                    for (img in images) {
                        val b64 = LlmHttpFactory.readAttachmentAsBase64(img)
                        if (b64 != null) {
                            val mime = img.mimeType.ifBlank { "image/jpeg" }
                            add(buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put("image_url", buildJsonObject {
                                    put("url", JsonPrimitive("data:$mime;base64,$b64"))
                                })
                            })
                        }
                    }
                })
            } else {
                if (msg.role == LlmRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                    if (fullText.isNotEmpty()) put("content", JsonPrimitive(fullText))
                    else put("content", kotlinx.serialization.json.JsonNull)
                } else {
                    put("content", JsonPrimitive(fullText))
                }
            }

            if (msg.role == LlmRole.TOOL && !msg.toolCallId.isNullOrBlank()) {
                put("tool_call_id", JsonPrimitive(msg.toolCallId))
                if (!msg.toolName.isNullOrBlank()) {
                    put("name", JsonPrimitive(msg.toolName))
                }
            }

            if (msg.role == LlmRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    for (tc in msg.toolCalls) {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(tc.id))
                            put("type", JsonPrimitive("function"))
                            put("function", buildJsonObject {
                                put("name", JsonPrimitive(tc.name))
                                put("arguments", JsonPrimitive(tc.argumentsJson))
                            })
                        })
                    }
                })
            }
        }
    }
}
