package com.forgemind.app.llm

import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderConfig
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
        val rawKeys = config.parsedKeys()
        if (config.requiresApiKey() && rawKeys.isEmpty()) {
            emit(LlmStreamEvent.Error("Не указан API-ключ Google Gemini.", retryable = false))
            return@flow
        }

        val httpClient = LlmHttpFactory.clientForConfig(config, client)
        val keyPool = if (rawKeys.isEmpty()) listOf("") else rawKeys
        val rawUrl = LlmHttpFactory.resolveEndpointUrl(config, "https://generativelanguage.googleapis.com") { base ->
            val cleanBase = base.removeSuffix("/v1beta")
            "$cleanBase/v1beta/models/${config.modelId.trim()}:streamGenerateContent?alt=sse"
        }
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

                val body = buildRequestBody(config, messages, tools)
                val reqBuilder = Request.Builder()
                    .url(rawUrl)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                val finalUrl = LlmHttpFactory.applyProviderAuth(
                    reqBuilder = reqBuilder,
                    endpointUrl = rawUrl,
                    config = config,
                    apiKey = apiKey,
                    defaultMode = ProviderAuthMode.QUERY_KEY
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
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastError = LlmStreamEvent.Error("Сетевая ошибка Gemini: ${e.message}", retryable = true)
                    if (!emittedAny && attempts < 3) retrySame = true
                    else {
                        emit(lastError!!)
                        return@flow
                    }
                } finally {
                    cancelHook?.dispose()
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
    ): JsonObject {
        val core = buildJsonObject {
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
            val isGemini25 = config.modelId.contains("2.5")
            val isGemini3 = config.modelId.contains("3.")
            if (isGemini25 || isGemini3) {
                if (config.enableThinking) {
                    put("thinkingConfig", buildJsonObject {
                        put("includeThoughts", JsonPrimitive(true))
                        if (isGemini25) {
                            put("thinkingBudget", JsonPrimitive(config.thinkingBudget.coerceIn(512, 24576)))
                        }
                    })
                } else if (isGemini25 && config.modelId.contains("flash", ignoreCase = true)) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingBudget", JsonPrimitive(0))
                    })
                }
            }
        })

        val nonSystem = messages.filter { it.role != LlmRole.SYSTEM }
        val builtContents = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        fun pushContent(roleName: String, newParts: List<JsonElement>) {
            val safeParts = if (newParts.isEmpty()) {
                listOf(buildJsonObject { put("text", JsonPrimitive(" ")) })
            } else {
                newParts
            }
            val last = builtContents.lastOrNull()
            if (last != null && last.first == roleName) {
                last.second.addAll(safeParts)
            } else {
                builtContents += roleName to safeParts.toMutableList()
            }
        }

        var i = 0
        while (i < nonSystem.size) {
            val msg = nonSystem[i]
            when (msg.role) {
                LlmRole.USER -> {
                    pushContent("user", buildUserParts(msg).toList())
                    i++
                }
                LlmRole.ASSISTANT -> {
                    val parts = buildList {
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
                    }
                    pushContent("model", parts)
                    i++
                }
                LlmRole.TOOL -> {
                    val grouped = mutableListOf<LlmWireMessage>()
                    while (i < nonSystem.size && nonSystem[i].role == LlmRole.TOOL) {
                        grouped += nonSystem[i]
                        i++
                    }
                    val parts = grouped.map { tm ->
                        buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", JsonPrimitive(tm.toolName ?: "tool"))
                                put("response", buildJsonObject {
                                    put("output", JsonPrimitive(tm.content))
                                })
                            })
                        }
                    }
                    pushContent("user", parts)
                }
                LlmRole.SYSTEM -> i++
            }
        }
        put("contents", buildJsonArray {
            for ((roleName, partsList) in builtContents) {
                add(buildJsonObject {
                    put("role", JsonPrimitive(roleName))
                    put("parts", buildJsonArray { partsList.forEach { add(it) } })
                })
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
        return LlmHttpFactory.mergeExtraBody(core, config.extraBodyJson)
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
