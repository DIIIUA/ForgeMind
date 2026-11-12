package com.forgemind.app.llm

import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ToolCallInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AnthropicClient(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient
) : LlmClient {

    override fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent> = flow {
        val keyPool = config.parsedKeys()
        if (keyPool.isEmpty()) {
            emit(LlmStreamEvent.Error("Не указан API-ключ Anthropic.", retryable = false))
            return@flow
        }

        val base = config.baseUrl.ifBlank { "https://api.anthropic.com" }.trim().trimEnd('/')
        val url = if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
        var lastError: LlmStreamEvent.Error? = null

        keyLoop@ for ((keyIdx, apiKey) in keyPool.withIndex()) {
            if (keyIdx > 0 && !config.autoRotateKeys) break
            if (keyIdx > 0) emit(LlmStreamEvent.StatusNote("Ротация ключа Anthropic (${keyIdx + 1}/${keyPool.size})..."))

            var attempts = 0
            while (attempts < 3) {
                attempts++
                var emittedAny = false
                var rotateKey = false
                var retrySame = false

                val body = buildRequestBody(config, messages, tools)
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                LlmHttpFactory.applyCustomHeaders(reqBuilder, config.customHeadersJson)

                try {
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errText = resp.body?.string().orEmpty().take(1500)
                            val code = resp.code
                            if (LlmHttpFactory.isKeyRotationHttpCode(code) && keyIdx + 1 < keyPool.size && config.autoRotateKeys) {
                                lastError = LlmStreamEvent.Error("HTTP $code: $errText", code, true)
                                rotateKey = true
                                return@use
                            }
                            if (LlmHttpFactory.isTransientHttpCode(code) && attempts < 3) {
                                lastError = LlmStreamEvent.Error("HTTP $code: $errText", code, true)
                                retrySame = true
                                return@use
                            }
                            emit(LlmStreamEvent.Error("Anthropic HTTP $code: $errText", code, false))
                            return@flow
                        }

                        val source = resp.body?.source()
                        if (source == null) {
                            emit(LlmStreamEvent.Error("Пустой ответ от Anthropic", retryable = true))
                            return@flow
                        }

                        val activeToolBlocks = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
                        var stopReason = "end_turn"
                        var inputTokens = 0
                        var outputTokens = 0
                        val dataLines = mutableListOf<String>()

                        suspend fun processEvent() {
                            if (dataLines.isEmpty()) return
                            val raw = dataLines.joinToString("\n").trim()
                            dataLines.clear()
                            if (raw.isEmpty()) return

                            val root = runCatching {
                                LlmHttpFactory.json.parseToJsonElement(raw).jsonObject
                            }.getOrNull() ?: return

                            when (root["type"]?.jsonPrimitive?.contentOrNull) {
                                "message_start" -> {
                                    val usage = root["message"]?.jsonObject?.get("usage")?.jsonObject
                                    inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: inputTokens
                                }
                                "content_block_start" -> {
                                    val idx = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val cb = root["content_block"]?.jsonObject ?: return
                                    if (cb["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                        val id = cb["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_$idx"
                                        val name = cb["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        activeToolBlocks[idx] = Triple(id, name, StringBuilder())
                                    }
                                }
                                "content_block_delta" -> {
                                    val idx = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val delta = root["delta"]?.jsonObject ?: return
                                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                        "text_delta" -> {
                                            val t = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            if (t.isNotEmpty()) {
                                                emittedAny = true
                                                emit(LlmStreamEvent.TextDelta(t))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val t = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            if (t.isNotEmpty()) {
                                                emittedAny = true
                                                emit(LlmStreamEvent.ThinkingDelta(t))
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val part = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                            activeToolBlocks[idx]?.third?.append(part)
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    val idx = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    activeToolBlocks.remove(idx)?.let { (id, name, buf) ->
                                        if (name.isNotBlank()) {
                                            emittedAny = true
                                            emit(
                                                LlmStreamEvent.ToolCallReady(
                                                    ToolCallInfo(
                                                        id = id,
                                                        name = name,
                                                        argumentsJson = buf.toString().ifBlank { "{}" }
                                                    )
                                                )
                                            )
                                        }
                                    }
                                }
                                "message_delta" -> {
                                    val delta = root["delta"]?.jsonObject
                                    delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                                    val usage = root["usage"]?.jsonObject
                                    outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: outputTokens
                                }
                                "error" -> {
                                    val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                        ?: root.toString()
                                    emit(LlmStreamEvent.Error("Anthropic error: $msg", retryable = false))
                                }
                            }
                        }

                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                processEvent()
                            } else if (line.startsWith("data:")) {
                                dataLines += line.removePrefix("data:").trimStart()
                            }
                        }
                        processEvent()

                        if (inputTokens > 0 || outputTokens > 0) {
                            emit(LlmStreamEvent.Usage(inputTokens, outputTokens))
                        }
                        emit(LlmStreamEvent.Done(stopReason))
                        return@flow
                    }
                } catch (e: Exception) {
                    lastError = LlmStreamEvent.Error("Сетевая ошибка Anthropic: ${e.message}", retryable = true)
                    if (!emittedAny && attempts < 3) retrySame = true
                    else {
                        emit(lastError!!)
                        return@flow
                    }
                }

                if (rotateKey) continue@keyLoop
                if (retrySame) {
                    delay(750L * attempts)
                    continue
                }
                break
            }
        }
        emit(lastError ?: LlmStreamEvent.Error("Все ключи Anthropic недоступны.", retryable = false))
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(config.modelId.trim()))
        put("stream", JsonPrimitive(true))
        put("max_tokens", JsonPrimitive(config.maxTokens))

        val systemTexts = messages.filter { it.role == LlmRole.SYSTEM && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content }
        if (systemTexts.isNotBlank()) {
            put("system", JsonPrimitive(systemTexts))
        }

        if (config.enableThinking && (config.modelId.contains("3-7") || config.modelId.contains("4") || config.modelId.contains("sonnet") || config.modelId.contains("opus"))) {
            val budget = config.thinkingBudget.coerceIn(1024, (config.maxTokens - 256).coerceAtLeast(1024))
            put("thinking", buildJsonObject {
                put("type", JsonPrimitive("enabled"))
                put("budget_tokens", JsonPrimitive(budget))
            })
        } else {
            put("temperature", JsonPrimitive(config.temperature))
        }

        val chatMessages = messages.filter { it.role != LlmRole.SYSTEM }
        put("messages", buildJsonArray {
            var i = 0
            while (i < chatMessages.size) {
                val msg = chatMessages[i]
                when (msg.role) {
                    LlmRole.USER -> {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", buildUserContentBlocks(msg))
                        })
                        i++
                    }
                    LlmRole.ASSISTANT -> {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", buildJsonArray {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(msg.content))
                                    })
                                }
                                for (tc in msg.toolCalls) {
                                    val inputObj: JsonElement = runCatching {
                                        LlmHttpFactory.json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" })
                                    }.getOrElse { buildJsonObject {} }
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("tool_use"))
                                        put("id", JsonPrimitive(tc.id))
                                        put("name", JsonPrimitive(tc.name))
                                        put("input", inputObj)
                                    })
                                }
                            })
                        })
                        i++
                    }
                    LlmRole.TOOL -> {
                        // Group contiguous tool responses into one Anthropic user message
                        val grouped = mutableListOf<LlmWireMessage>()
                        while (i < chatMessages.size && chatMessages[i].role == LlmRole.TOOL) {
                            grouped += chatMessages[i]
                            i++
                        }
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", buildJsonArray {
                                for (tm in grouped) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("tool_result"))
                                        put("tool_use_id", JsonPrimitive(tm.toolCallId ?: "unknown"))
                                        put("content", JsonPrimitive(tm.content))
                                    })
                                }
                            })
                        })
                    }
                    LlmRole.SYSTEM -> i++
                }
            }
        })

        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (t in tools) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(t.name))
                        put("description", JsonPrimitive(t.description))
                        put("input_schema", t.parametersSchema)
                    })
                }
            })
        }
    }

    private fun buildUserContentBlocks(msg: LlmWireMessage) = buildJsonArray {
        val text = (msg.content + LlmHttpFactory.formatNonImageAttachments(msg.attachments)).trim()
        for (att in msg.attachments.filter { it.kind == AttachmentKind.IMAGE }) {
            val b64 = LlmHttpFactory.readAttachmentAsBase64(att) ?: continue
            add(buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("base64"))
                    put("media_type", JsonPrimitive(att.mimeType.ifBlank { "image/jpeg" }))
                    put("data", JsonPrimitive(b64))
                })
            })
        }
        if (text.isNotEmpty()) {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })
        }
    }
}
