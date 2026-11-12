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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeminiClient(
    private val client: OkHttpClient = LlmHttpFactory.streamingClient
) : LlmClient {

    override fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent> = flow {
        val keyPool = config.parsedKeys()
        if (keyPool.isEmpty()) {
            emit(LlmStreamEvent.Error("Не указан API-ключ Google Gemini.", retryable = false))
            return@flow
        }

        val base = config.baseUrl.ifBlank { "https://generativelanguage.googleapis.com" }.trim().trimEnd('/')
        var lastError: LlmStreamEvent.Error? = null

        keyLoop@ for ((keyIdx, apiKey) in keyPool.withIndex()) {
            if (keyIdx > 0 && !config.autoRotateKeys) break
            if (keyIdx > 0) emit(LlmStreamEvent.StatusNote("Ротация ключа Gemini (${keyIdx + 1}/${keyPool.size})..."))

            var attempts = 0
            while (attempts < 3) {
                attempts++
                var emittedAny = false
                var rotateKey = false
                var retrySame = false

                val url = "$base/v1beta/models/${config.modelId.trim()}:streamGenerateContent?alt=sse&key=$apiKey"
                val body = buildRequestBody(config, messages, tools)
                val reqBuilder = Request.Builder()
                    .url(url)
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
                            emit(LlmStreamEvent.Error("Gemini HTTP $code: $errText", code, false))
                            return@flow
                        }

                        val source = resp.body?.source()
                        if (source == null) {
                            emit(LlmStreamEvent.Error("Пустой поток ответа Gemini", retryable = true))
                            return@flow
                        }

                        var finishReason = "STOP"
                        val dataLines = mutableListOf<String>()

                        suspend fun flushChunk() {
                            if (dataLines.isEmpty()) return
                            val raw = dataLines.joinToString("\n").trim()
                            dataLines.clear()
                            if (raw.isEmpty() || raw == "[DONE]") return

                            val root = runCatching {
                                LlmHttpFactory.json.parseToJsonElement(raw).jsonObject
                            }.getOrNull() ?: return

                            root["usageMetadata"]?.jsonObject?.let { u ->
                                val p = u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                val c = u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                if (p > 0 || c > 0) emit(LlmStreamEvent.Usage(p, c))
                            }

                            val candidates = root["candidates"]?.jsonArray ?: return
                            for (candEl in candidates) {
                                val cand = candEl.jsonObject
                                cand["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                                val parts = cand["content"]?.jsonObject?.get("parts")?.jsonArray ?: continue
                                for (partEl in parts) {
                                    val part = partEl.jsonObject
                                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                                    val text = part["text"]?.jsonPrimitive?.contentOrNull
                                    if (!text.isNullOrEmpty()) {
                                        emittedAny = true
                                        if (isThought) emit(LlmStreamEvent.ThinkingDelta(text))
                                        else emit(LlmStreamEvent.TextDelta(text))
                                    }

                                    val fc = part["functionCall"]?.jsonObject
                                    if (fc != null) {
                                        emittedAny = true
                                        val name = fc["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        val args = fc["args"]?.toString() ?: "{}"
                                        val sig = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                                        if (name.isNotBlank()) {
                                            emit(
                                                LlmStreamEvent.ToolCallReady(
                                                    ToolCallInfo(
                                                        id = "gem_${System.nanoTime()}",
                                                        name = name,
                                                        argumentsJson = args,
                                                        thoughtSignature = sig
                                                    )
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                flushChunk()
                            } else if (line.startsWith("data:")) {
                                dataLines += line.removePrefix("data:").trimStart()
                            }
                        }
                        flushChunk()
                        emit(LlmStreamEvent.Done(finishReason))
                        return@flow
                    }
                } catch (e: Exception) {
                    lastError = LlmStreamEvent.Error("Сетевая ошибка Gemini: ${e.message}", retryable = true)
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
        emit(lastError ?: LlmStreamEvent.Error("Все ключи Gemini недоступны.", retryable = false))
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): JsonObject = buildJsonObject {
        val sysText = messages.filter { it.role == LlmRole.SYSTEM && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content }
        if (sysText.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(sysText)) })
                })
            })
        }

        put("generationConfig", buildJsonObject {
            put("temperature", JsonPrimitive(config.temperature))
            put("maxOutputTokens", JsonPrimitive(config.maxTokens))
            if (config.enableThinking && config.modelId.contains("2.5") || config.modelId.contains("3.")) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", JsonPrimitive(true))
                })
            }
        })

        val nonSystem = messages.filter { it.role != LlmRole.SYSTEM }
        put("contents", buildJsonArray {
            var i = 0
            while (i < nonSystem.size) {
                val msg = nonSystem[i]
                when (msg.role) {
                    LlmRole.USER -> {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("parts", buildUserParts(msg))
                        })
                        i++
                    }
                    LlmRole.ASSISTANT -> {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("model"))
                            put("parts", buildJsonArray {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject { put("text", JsonPrimitive(msg.content)) })
                                }
                                for (tc in msg.toolCalls) {
                                    val argsObj: JsonElement = runCatching {
                                        LlmHttpFactory.json.parseToJsonElement(tc.argumentsJson.ifBlank { "{}" })
                                    }.getOrElse { buildJsonObject {} }
                                    add(buildJsonObject {
                                        if (!tc.thoughtSignature.isNullOrBlank()) {
                                            put("thoughtSignature", JsonPrimitive(tc.thoughtSignature))
                                        }
                                        put("functionCall", buildJsonObject {
                                            put("name", JsonPrimitive(tc.name))
                                            put("args", argsObj)
                                        })
                                    })
                                }
                            })
                        })
                        i++
                    }
                    LlmRole.TOOL -> {
                        val grouped = mutableListOf<LlmWireMessage>()
                        while (i < nonSystem.size && nonSystem[i].role == LlmRole.TOOL) {
                            grouped += nonSystem[i]
                            i++
                        }
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("parts", buildJsonArray {
                                for (tm in grouped) {
                                    add(buildJsonObject {
                                        put("functionResponse", buildJsonObject {
                                            put("name", JsonPrimitive(tm.toolName ?: "tool"))
                                            put("response", buildJsonObject {
                                                put("output", JsonPrimitive(tm.content))
                                            })
                                        })
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
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        for (t in tools) {
                            add(buildJsonObject {
                                put("name", JsonPrimitive(t.name))
                                put("description", JsonPrimitive(t.description))
                                put("parameters", sanitizeGeminiSchema(t.parametersSchema))
                            })
                        }
                    })
                })
            })
        }
    }

    private fun buildUserParts(msg: LlmWireMessage) = buildJsonArray {
        for (att in msg.attachments) {
            if (att.kind == AttachmentKind.IMAGE || att.kind == AttachmentKind.PDF) {
                val b64 = LlmHttpFactory.readAttachmentAsBase64(att)
                if (b64 != null) {
                    add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", JsonPrimitive(att.mimeType))
                            put("data", JsonPrimitive(b64))
                        })
                    })
                }
            }
        }
        val otherText = LlmHttpFactory.formatNonImageAttachments(
            msg.attachments.filter { it.kind != AttachmentKind.PDF }
        )
        val combined = (msg.content + otherText).trim()
        if (combined.isNotEmpty()) {
            add(buildJsonObject { put("text", JsonPrimitive(combined)) })
        }
    }

    /** Gemini OpenAPI schema rejects additionalProperties cleanly if stripped recursively */
    private fun sanitizeGeminiSchema(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> buildJsonObject {
            for ((k, v) in el) {
                if (k == "additionalProperties" || k == "\$schema") continue
                put(k, sanitizeGeminiSchema(v))
            }
        }
        is JsonArray -> buildJsonArray { el.forEach { add(sanitizeGeminiSchema(it)) } }
        else -> el
    }
}
