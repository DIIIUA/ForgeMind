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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
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
 * Streaming SSE client for OpenAI Responses API (`/v1/responses`),
 * used by modern OpenAI endpoints, Codex/Responses proxies, Azure v1 Responses, and custom gateways.
 */
class OpenAiResponsesClient(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient
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
        var includeReasoning = config.enableThinking
        var lastError: LlmStreamEvent.Error? = null

        keyLoop@ for ((keyIdx, apiKey) in keyPool.withIndex()) {
            if (keyIdx > 0 && !config.autoRotateKeys) break
            var attempts = 0
            while (attempts < 3) {
                attempts++
                var emittedAny = false
                var retrySameKey = false
                var rotateKey = false

                val baseEndpoint = LlmHttpFactory.resolveEndpointUrl(
                    config = config,
                    defaultBaseUrl = provider.defaultBaseUrl,
                    buildStandardEndpoint = { normalized -> normalizeResponsesEndpoint(normalized) }
                )
                val bodyJson = buildRequestBody(
                    config = config,
                    messages = messages,
                    tools = if (includeTools) tools else emptyList(),
                    includeReasoning = includeReasoning
                )

                val reqBuilder = Request.Builder()
                    .header("Accept", "text/event-stream")
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
                            if (code in listOf(400, 422) && includeReasoning &&
                                errText.contains("reasoning", ignoreCase = true)
                            ) {
                                includeReasoning = false
                                attempts = (attempts - 1).coerceAtLeast(0)
                                retrySameKey = true
                                return@use
                            }
                            if (code in listOf(400, 422) && includeTools &&
                                (errText.contains("tool", ignoreCase = true) || errText.contains("function", ignoreCase = true))
                            ) {
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

                        data class FnBuf(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
                        val fnByItemId = linkedMapOf<String, FnBuf>()
                        val emittedCallIds = HashSet<String>()
                        var finishReason = "stop"
                        val dataLines = mutableListOf<String>()

                        suspend fun processSse() {
                            if (dataLines.isEmpty()) return
                            val raw = dataLines.joinToString("\n").trim()
                            dataLines.clear()
                            if (raw.isEmpty() || raw == "[DONE]") return

                            val root = runCatching { LlmHttpFactory.json.parseToJsonElement(raw).jsonObject }
                                .getOrNull() ?: return

                            val type = (root["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            when (type) {
                                "response.output_text.delta" -> {
                                    val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    if (delta.isNotEmpty()) {
                                        emittedAny = true
                                        emit(LlmStreamEvent.TextDelta(delta))
                                    }
                                }
                                "response.reasoning_summary_text.delta",
                                "response.reasoning_text.delta" -> {
                                    val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    if (delta.isNotEmpty()) {
                                        emittedAny = true
                                        emit(LlmStreamEvent.ThinkingDelta(delta))
                                    }
                                }
                                "response.output_item.added" -> {
                                    val item = root["item"] as? JsonObject ?: return
                                    if ((item["type"] as? JsonPrimitive)?.contentOrNull == "function_call") {
                                        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                                            ?: (item["call_id"] as? JsonPrimitive)?.contentOrNull
                                            ?: "item_${fnByItemId.size}"
                                        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: itemId
                                        val fnName = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                        val buf = fnByItemId.getOrPut(itemId) { FnBuf() }
                                        buf.callId = callId
                                        if (fnName.isNotBlank()) buf.name = fnName
                                    }
                                }
                                "response.function_call_arguments.delta" -> {
                                    val itemId = (root["item_id"] as? JsonPrimitive)?.contentOrNull ?: "item_0"
                                    val delta = (root["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    val buf = fnByItemId.getOrPut(itemId) { FnBuf(callId = itemId) }
                                    buf.args.append(delta)
                                }
                                "response.output_item.done" -> {
                                    val item = root["item"] as? JsonObject ?: return
                                    if ((item["type"] as? JsonPrimitive)?.contentOrNull == "function_call") {
                                        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                                            ?: (item["call_id"] as? JsonPrimitive)?.contentOrNull
                                            ?: "item_0"
                                        val buf = fnByItemId[itemId]
                                        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
                                            ?: buf?.callId?.takeIf { it.isNotBlank() }
                                            ?: "resp_call_${System.nanoTime()}"
                                        val fnName = (item["name"] as? JsonPrimitive)?.contentOrNull
                                            ?: buf?.name.orEmpty()
                                        val argsStr = (item["arguments"] as? JsonPrimitive)?.contentOrNull
                                            ?.takeIf { it.isNotBlank() }
                                            ?: buf?.args?.toString().orEmpty()
                                        if (fnName.isNotBlank() && emittedCallIds.add(callId)) {
                                            emittedAny = true
                                            finishReason = "tool_calls"
                                            emit(
                                                LlmStreamEvent.ToolCallReady(
                                                    ToolCallInfo(
                                                        id = callId,
                                                        name = fnName,
                                                        argumentsJson = argsStr.ifBlank { "{}" }
                                                    )
                                                )
                                            )
                                        }
                                    }
                                }
                                "response.completed" -> {
                                    val respObj = root["response"] as? JsonObject
                                    val usage = respObj?.get("usage") as? JsonObject
                                    val inTok = (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0
                                    val outTok = (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0
                                    if (inTok > 0 || outTok > 0) {
                                        emit(LlmStreamEvent.Usage(inTok, outTok))
                                    }
                                }
                                "error", "response.failed" -> {
                                    val errObj = (root["error"] as? JsonObject)
                                        ?: (root["response"] as? JsonObject)?.get("error") as? JsonObject
                                    val msg = (errObj?.get("message") as? JsonPrimitive)?.contentOrNull
                                        ?: root.toString()
                                    throw IllegalStateException(msg)
                                }
                                else -> {
                                    // Non-streaming JSON fallback if proxy returned a single `response` object
                                    if (!emittedAny && root.containsKey("output")) {
                                        val outArr = root["output"] as? JsonArray ?: return
                                        for (itemEl in outArr) {
                                            val item = itemEl as? JsonObject ?: continue
                                            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                                                "message" -> {
                                                    val parts = item["content"] as? JsonArray ?: continue
                                                    for (pEl in parts) {
                                                        val p = pEl as? JsonObject ?: continue
                                                        val txt = (p["text"] as? JsonPrimitive)?.contentOrNull
                                                        if (!txt.isNullOrEmpty()) {
                                                            emittedAny = true
                                                            emit(LlmStreamEvent.TextDelta(txt))
                                                        }
                                                    }
                                                }
                                                "function_call" -> {
                                                    val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
                                                        ?: "resp_call_${System.nanoTime()}"
                                                    val fnName = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                                    val args = (item["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
                                                    if (fnName.isNotBlank() && emittedCallIds.add(callId)) {
                                                        emittedAny = true
                                                        finishReason = "tool_calls"
                                                        emit(LlmStreamEvent.ToolCallReady(ToolCallInfo(callId, fnName, args)))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        var sawSse = false
                        val rawLines = mutableListOf<String>()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                processSse()
                            } else if (line.startsWith("data:")) {
                                sawSse = true
                                dataLines += line.removePrefix("data:").trimStart()
                            } else if (!sawSse && !line.startsWith("event:") && !line.startsWith(":")) {
                                rawLines += line
                            }
                        }
                        processSse()
                        if (!sawSse && rawLines.isNotEmpty()) {
                            dataLines += rawLines.joinToString("\n")
                            processSse()
                        }

                        for ((_, buf) in fnByItemId) {
                            if (buf.name.isNotBlank() && emittedCallIds.add(buf.callId.ifBlank { "call_${System.nanoTime()}" })) {
                                finishReason = "tool_calls"
                                emit(
                                    LlmStreamEvent.ToolCallReady(
                                        ToolCallInfo(
                                            id = buf.callId.ifBlank { "call_${System.nanoTime()}" },
                                            name = buf.name,
                                            argumentsJson = buf.args.toString().ifBlank { "{}" }
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
                } catch (e: Exception) {
                    lastError = LlmStreamEvent.Error("Ошибка $providerTitle: ${e.message ?: e::class.simpleName}", retryable = true)
                    if (!emittedAny && attempts < 3) retrySameKey = true
                    else {
                        emit(lastError!!)
                        return@flow
                    }
                } finally {
                    cancelHook?.dispose()
                }

                if (rotateKey) continue@keyLoop
                if (retrySameKey) {
                    delay(700L * attempts)
                    continue
                }
                break
            }
        }

        emit(lastError ?: LlmStreamEvent.Error("Все ключи или попытки $providerTitle исчерпаны.", retryable = false))
    }.flowOn(Dispatchers.IO)

    companion object {
        fun normalizeResponsesEndpoint(normalizedBase: String): String = when {
            normalizedBase.endsWith("/responses") -> normalizedBase
            normalizedBase.endsWith("/v1") || normalizedBase.endsWith("/api/v1") -> "$normalizedBase/responses"
            else -> "$normalizedBase/v1/responses"
        }

        fun buildRequestBody(
            config: ProviderConfig,
            messages: List<LlmWireMessage>,
            tools: List<LlmToolSpec>,
            includeReasoning: Boolean
        ): JsonObject {
            val sysText = messages.filter { it.role == LlmRole.SYSTEM && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content }

            val core = buildJsonObject {
                put("model", JsonPrimitive(config.modelId.trim()))
                put("stream", JsonPrimitive(true))
                put("max_output_tokens", JsonPrimitive(config.maxTokens))
                if (sysText.isNotBlank()) {
                    put("instructions", JsonPrimitive(sysText))
                }
                if (includeReasoning) {
                    put("reasoning", buildJsonObject {
                        put("effort", JsonPrimitive(config.reasoningEffort().id))
                    })
                } else {
                    put("temperature", JsonPrimitive(config.temperature))
                }

                put("input", buildJsonArray {
                    for (msg in messages) {
                        when (msg.role) {
                            LlmRole.SYSTEM -> Unit
                            LlmRole.USER -> {
                                val extraText = LlmHttpFactory.formatNonImageAttachments(msg.attachments)
                                val fullText = (msg.content + extraText).trim()
                                val imgs = msg.attachments.filter { it.kind == AttachmentKind.IMAGE }
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("message"))
                                    put("role", JsonPrimitive("user"))
                                    put("content", buildJsonArray {
                                        if (fullText.isNotEmpty()) {
                                            add(buildJsonObject {
                                                put("type", JsonPrimitive("input_text"))
                                                put("text", JsonPrimitive(fullText))
                                            })
                                        }
                                        for (img in imgs) {
                                            val b64 = LlmHttpFactory.readAttachmentAsBase64(img) ?: continue
                                            val mime = img.mimeType.ifBlank { "image/jpeg" }
                                            add(buildJsonObject {
                                                put("type", JsonPrimitive("input_image"))
                                                put("image_url", JsonPrimitive("data:$mime;base64,$b64"))
                                            })
                                        }
                                    })
                                })
                            }
                            LlmRole.ASSISTANT -> {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("message"))
                                        put("role", JsonPrimitive("assistant"))
                                        put("content", buildJsonArray {
                                            add(buildJsonObject {
                                                put("type", JsonPrimitive("output_text"))
                                                put("text", JsonPrimitive(msg.content))
                                            })
                                        })
                                    })
                                }
                                for (tc in msg.toolCalls) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("function_call"))
                                        put("call_id", JsonPrimitive(tc.id))
                                        put("name", JsonPrimitive(tc.name))
                                        put("arguments", JsonPrimitive(tc.argumentsJson.ifBlank { "{}" }))
                                    })
                                }
                            }
                            LlmRole.TOOL -> {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("function_call_output"))
                                    put("call_id", JsonPrimitive(msg.toolCallId ?: "call_unknown"))
                                    put("output", JsonPrimitive(msg.content))
                                })
                            }
                        }
                    }
                })

                if (tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        for (t in tools) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put("name", JsonPrimitive(t.name))
                                put("description", JsonPrimitive(t.description))
                                put("parameters", t.parametersSchema)
                            })
                        }
                    })
                }
            }
            return LlmHttpFactory.mergeExtraBody(core, config.extraBodyJson)
        }
    }
}
