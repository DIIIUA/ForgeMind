package com.forgemind.app.llm

import android.annotation.SuppressLint
import android.util.Base64
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ToolCallInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class LlmWireMessage(
    val role: LlmRole,
    val content: String = "",
    val toolCalls: List<ToolCallInfo> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val attachments: List<ChatAttachment> = emptyList()
)

data class LlmToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject
)

sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class ThinkingDelta(val text: String) : LlmStreamEvent
    data class ToolCallReady(val toolCall: ToolCallInfo) : LlmStreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int) : LlmStreamEvent
    data class StatusNote(val text: String) : LlmStreamEvent
    data class Done(val finishReason: String = "stop") : LlmStreamEvent
    data class Error(
        val message: String,
        val httpCode: Int? = null,
        val retryable: Boolean = false
    ) : LlmStreamEvent
}

interface LlmClient {
    fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent>
}

object LlmHttpFactory {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val dohCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private val dohBootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName(hostname))
            })
            .build()
    }

    /**
     * System DNS with automatic DNS-over-HTTPS (1.1.1.1 / 8.8.8.8) fallback
     * when VPN/local resolvers fail to resolve external API hosts.
     * Private LAN / mDNS / Tailscale hosts skip external DoH so LAN errors fail fast.
     */
    val smartDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val clean = hostname.trim()
            try {
                val sys = Dns.SYSTEM.lookup(clean)
                if (sys.isNotEmpty()) return sys
            } catch (uhe: UnknownHostException) {
                if (isIpLiteral(clean) || isLocalOrPrivateHost(clean)) {
                    throw uhe
                }
            }
            if (isIpLiteral(clean) || isLocalOrPrivateHost(clean)) {
                throw UnknownHostException("Unable to resolve local host \"$clean\"")
            }
            val key = clean.lowercase()
            val now = System.currentTimeMillis()
            dohCache[key]?.let { (ts, cached) ->
                if (now - ts < 300_000L && cached.isNotEmpty()) return cached
            }
            val resolved = resolveViaDoh(clean)
            if (resolved.isNotEmpty()) {
                dohCache[key] = now to resolved
                return resolved
            }
            throw UnknownHostException("Unable to resolve host \"$clean\" (System DNS & DoH 1.1.1.1/8.8.8.8 failed)")
        }
    }

    fun parseDohAnswerIps(jsonBody: String): List<InetAddress> = runCatching {
        val root = json.parseToJsonElement(jsonBody).jsonObject
        val answers = root["Answer"] as? JsonArray ?: return emptyList()
        answers.mapNotNull { el ->
            val obj = el.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content?.toIntOrNull()
            if (type != 1 && type != 28) return@mapNotNull null
            val data = obj["data"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (data.isBlank()) return@mapNotNull null
            runCatching { InetAddress.getByName(data) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val encoded = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrDefault(hostname)
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$encoded&type=A",
            "https://8.8.8.8/resolve?name=$encoded&type=A",
            "https://1.0.0.1/dns-query?name=$encoded&type=A"
        )
        for (url in endpoints) {
            val ips = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()
                dohBootstrapClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else parseDohAnswerIps(resp.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    fun baseClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dns(smartDns)
        .retryOnConnectionFailure(true)

    val streamingClient: OkHttpClient by lazy {
        baseClientBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // streaming must not cut long generations
            .build()
    }

    private val customClientCache = ConcurrentHashMap<String, OkHttpClient>()

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createTrustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllManager: X509TrustManager by lazy { createTrustAllManager() }

    fun clientForConfig(
        config: ProviderConfig,
        baseClient: OkHttpClient = streamingClient
    ): OkHttpClient {
        val timeoutSec = config.readTimeoutSec.coerceIn(15, 600)
        if (timeoutSec == 180 && !config.allowInsecureSsl) {
            return baseClient
        }
        if (baseClient !== streamingClient) {
            val b = baseClient.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (config.allowInsecureSsl) applyInsecureSsl(b)
            return b.build()
        }
        val key = "${timeoutSec}_${config.allowInsecureSsl}"
        return customClientCache.getOrPut(key) {
            val b = streamingClient.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (config.allowInsecureSsl) applyInsecureSsl(b)
            b.build()
        }
    }

    private fun applyInsecureSsl(builder: OkHttpClient.Builder) {
        runCatching {
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf(trustAllManager), SecureRandom())
            builder.sslSocketFactory(sslCtx.socketFactory, trustAllManager)
            builder.hostnameVerifier { _, _ -> true }
        }
    }

    fun applyProviderAuth(
        reqBuilder: Request.Builder,
        endpointUrl: String,
        config: ProviderConfig,
        apiKey: String,
        defaultMode: ProviderAuthMode
    ): String {
        val cleanKey = apiKey.trim()
        val mode = if (config.authMode == ProviderAuthMode.AUTO) defaultMode else config.authMode
        if (mode == ProviderAuthMode.NONE || cleanKey.isEmpty()) {
            return endpointUrl
        }
        return when (mode) {
            ProviderAuthMode.BEARER -> {
                reqBuilder.header("Authorization", "Bearer $cleanKey")
                endpointUrl
            }
            ProviderAuthMode.X_API_KEY -> {
                reqBuilder.header("x-api-key", cleanKey)
                endpointUrl
            }
            ProviderAuthMode.CUSTOM_HEADER -> {
                val h = config.customAuthHeader.trim().ifBlank { "X-API-Key" }
                reqBuilder.header(h, "${config.customAuthPrefix}$cleanKey")
                endpointUrl
            }
            ProviderAuthMode.QUERY_KEY -> {
                val param = config.customQueryParam.trim().ifBlank { "key" }
                val encodedParam = runCatching { URLEncoder.encode(param, "UTF-8") }.getOrDefault(param)
                val encodedVal = runCatching { URLEncoder.encode(cleanKey, "UTF-8") }.getOrDefault(cleanKey)
                if (endpointUrl.contains("$encodedParam=")) {
                    endpointUrl
                } else {
                    val sep = if ('?' in endpointUrl) '&' else '?'
                    "${endpointUrl}${sep}${encodedParam}=${encodedVal}"
                }
            }
            ProviderAuthMode.AUTO, ProviderAuthMode.NONE -> endpointUrl
        }
    }

    fun mergeExtraBody(base: JsonObject, extraBodyJson: String): JsonObject {
        val trimmed = extraBodyJson.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return base
        val extraObj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return base
        if (extraObj.isEmpty()) return base
        return mergeJsonObjects(base, extraObj)
    }

    private fun mergeJsonObjects(target: JsonObject, patch: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in target) {
            val overrideVal = patch[k]
            if (overrideVal == null) {
                put(k, v)
            } else if (v is JsonObject && overrideVal is JsonObject) {
                put(k, mergeJsonObjects(v, overrideVal))
            } else {
                put(k, overrideVal)
            }
        }
        for ((k, v) in patch) {
            if (k !in target) {
                put(k, v)
            }
        }
    }

    fun applyCustomHeaders(builder: Request.Builder, headersJson: String) {
        if (headersJson.isBlank() || headersJson == "{}") return
        runCatching {
            val obj = json.parseToJsonElement(headersJson).jsonObject
            obj.forEach { (k, v) ->
                val cleanKey = k.trim()
                if (cleanKey.isBlank() || cleanKey.startsWith("_")) return@forEach
                val valStr = runCatching { v.jsonPrimitive.content }.getOrNull() ?: v.toString()
                if (valStr.isNotBlank()) {
                    builder.header(cleanKey, valStr)
                }
            }
        }
    }

    fun readAttachmentAsBase64(attachment: ChatAttachment, maxBytes: Long = 10 * 1024 * 1024): String? {
        val file = File(attachment.localPath)
        if (!file.exists() || !file.isFile || file.length() > maxBytes) return null
        return runCatching {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun formatNonImageAttachments(attachments: List<ChatAttachment>): String {
        val extras = attachments.filter { it.kind != AttachmentKind.IMAGE }
        if (extras.isEmpty()) return ""
        return buildString {
            for (att in extras) {
                appendLine("\n[Прикреплённый файл: ${att.name} (${att.mimeType}, ${att.sizeBytes} байт, путь: ${att.localPath})]")
                if (!att.previewText.isNullOrBlank()) {
                    appendLine("```")
                    appendLine(att.previewText.take(12_000))
                    appendLine("```")
                }
            }
        }
    }

    fun isKeyRotationHttpCode(code: Int): Boolean =
        code == 429 || code == 401 || code == 402 || code == 403

    fun isTransientHttpCode(code: Int): Boolean =
        code == 408 || code == 500 || code == 502 || code == 503 || code == 504 || code == 529

    fun extractHostFromRawUrl(raw: String): String {
        val noScheme = raw.trim().removeSuffix("#").trim()
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("HTTP://").removePrefix("HTTPS://")
        val authority = noScheme.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return if (authority.startsWith('[')) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }.trim().lowercase()
    }

    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return true // IPv6
        val parts = h.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    fun isLocalOrPrivateHost(rawHostOrUrl: String): Boolean {
        val h = extractHostFromRawUrl(rawHostOrUrl)
        if (h.isBlank()) return false
        if (h == "localhost" || h == "::1" || h == "0.0.0.0" || h.startsWith("127.")) return true
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (h.startsWith("100.")) {
            val second = h.removePrefix("100.").substringBefore('.').toIntOrNull()
            if (second != null && second in 64..127 && isIpLiteral(h)) return true // Tailscale / CGNAT
        }
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h.endsWith(".internal") || h.endsWith(".home.arpa") || h.endsWith(".localdomain")
        ) {
            return true
        }
        // Single-label LAN machine name (e.g. "desktop-pc:11434")
        if (!h.contains('.')) return true
        return false
    }

    fun resolveEndpointUrl(
        config: ProviderConfig,
        defaultBaseUrl: String,
        buildStandardEndpoint: (String) -> String
    ): String {
        val rawTrimmed = config.baseUrl.replace(Regex("\\s+"), "")
        val forceExact = config.rawEndpointUrl || rawTrimmed.endsWith("#")
        if (forceExact && rawTrimmed.removeSuffix("#").isNotBlank()) {
            val exact = rawTrimmed.removeSuffix("#")
            val withScheme = if (exact.startsWith("http://", ignoreCase = true) ||
                exact.startsWith("https://", ignoreCase = true)
            ) {
                exact
            } else {
                (if (isLocalOrPrivateHost(exact)) "http://" else "https://") + exact
            }
            return withScheme
        }
        return buildStandardEndpoint(normalizeBaseUrl(config.baseUrl, defaultBaseUrl))
    }

    fun normalizeBaseUrl(raw: String, fallback: String): String {
        val compact = raw.replace(Regex("\\s+"), "")
            .removeSuffix("#")
            .ifBlank { fallback.trim().removeSuffix("#") }
            .trimEnd('/')
        val withScheme = if (compact.startsWith("http://", ignoreCase = true) ||
            compact.startsWith("https://", ignoreCase = true)
        ) {
            compact
        } else {
            val isLocal = isLocalOrPrivateHost(compact)
            (if (isLocal) "http://" else "https://") + compact
        }
        val schemeEnd = withScheme.indexOf("://") + 3
        val head = withScheme.substring(0, schemeEnd)
        val tail = withScheme.substring(schemeEnd).replace(Regex("/{2,}"), "/")
        return (head + tail).trimEnd('/')
    }

    fun isToolUnsupportedHttpError(code: Int, errText: String): Boolean {
        if (code !in listOf(400, 404, 422, 500)) return false
        val lower = errText.lowercase()
        return lower.contains("tool") ||
            lower.contains("function") ||
            lower.contains("jinja") ||
            lower.contains("chat_template") ||
            lower.contains("template") ||
            lower.contains("does not support") ||
            lower.contains("not support") ||
            lower.contains("unsupported")
    }

    fun formatCompactToolCatalogForPrompt(tools: List<LlmToolSpec>, maxTools: Int = 36): String {
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("=== РЕЗЕРВНЫЙ ТЕКСТОВЫЙ ПРОТОКОЛ ИНСТРУМЕНТОВ (<tool_call>) ===")
            appendLine("Сервер этой модели не принимает нативное поле API `tools`. Чтобы реально выполнить команду или инструмент, выведи блок `<tool_call>` с валидным JSON и останови генерацию до получения результата:")
            appendLine("<tool_call>")
            appendLine("{\"name\": \"run_command\", \"arguments\": {\"command\": \"ls -la\", \"target_env\": \"internal\"}}")
            appendLine("</tool_call>")
            appendLine("ЗАПРЕЩЕНО писать команды просто в ```bash``` вместо `<tool_call>` или выдумывать вывод команды от себя.")
            appendLine("Доступные инструменты:")
            tools.take(maxTools).forEach { spec ->
                val props = (spec.parametersSchema["properties"] as? JsonObject)?.keys.orEmpty()
                val req = (spec.parametersSchema["required"] as? JsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.toSet()
                    .orEmpty()
                val paramsSig = props.joinToString(", ") { p -> if (p in req) p else "$p?" }
                val shortDesc = spec.description.lineSequence().firstOrNull().orEmpty().take(110)
                appendLine("- ${spec.name}($paramsSig): $shortDesc")
            }
            if (tools.size > maxTools) {
                appendLine("- ...и ещё ${tools.size - maxTools} инструментов (для MCP используй mcp_list_servers / mcp_call_tool).")
            }
        }
    }

    fun downgradeWireMessagesForTextTools(
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): List<LlmWireMessage> {
        val result = mutableListOf<LlmWireMessage>()
        val catalogSuffix = formatCompactToolCatalogForPrompt(tools)
        var injectedCatalog = false
        val pendingToolOutputs = StringBuilder()

        fun flushToolOutputsAsUser() {
            if (pendingToolOutputs.isEmpty()) return
            val text = pendingToolOutputs.toString().trim()
            pendingToolOutputs.clear()
            val prev = result.lastOrNull()
            if (prev != null && prev.role == LlmRole.USER) {
                result[result.lastIndex] = prev.copy(
                    content = listOf(prev.content, text).filter { it.isNotBlank() }.joinToString("\n\n")
                )
            } else {
                result += LlmWireMessage(role = LlmRole.USER, content = text)
            }
        }

        for (m in messages) {
            when (m.role) {
                LlmRole.SYSTEM -> {
                    flushToolOutputsAsUser()
                    val content = if (!injectedCatalog && catalogSuffix.isNotBlank()) {
                        injectedCatalog = true
                        m.content + "\n" + catalogSuffix
                    } else {
                        m.content
                    }
                    result += m.copy(content = content, toolCalls = emptyList())
                }
                LlmRole.TOOL -> {
                    if (pendingToolOutputs.isNotEmpty()) pendingToolOutputs.append("\n\n")
                    pendingToolOutputs.append("[Результат инструмента ${m.toolName ?: "tool"}]:\n${m.content}")
                }
                LlmRole.ASSISTANT -> {
                    flushToolOutputsAsUser()
                    val callsText = if (m.toolCalls.isNotEmpty()) {
                        m.toolCalls.joinToString("\n") { tc ->
                            "<tool_call>\n{\"name\": ${json.encodeToString(kotlinx.serialization.serializer<String>(), tc.name)}, \"arguments\": ${tc.argumentsJson.ifBlank { "{}" }}}\n</tool_call>"
                        }
                    } else ""
                    val merged = listOf(m.content, callsText).filter { it.isNotBlank() }.joinToString("\n\n")
                    if (merged.isNotBlank()) {
                        result += LlmWireMessage(role = LlmRole.ASSISTANT, content = merged, attachments = m.attachments)
                    }
                }
                LlmRole.USER -> {
                    flushToolOutputsAsUser()
                    val prev = result.lastOrNull()
                    if (prev != null && prev.role == LlmRole.USER) {
                        result[result.lastIndex] = prev.copy(
                            content = listOf(prev.content, m.content).filter { it.isNotBlank() }.joinToString("\n\n"),
                            attachments = prev.attachments + m.attachments
                        )
                    } else {
                        result += m.copy(toolCalls = emptyList())
                    }
                }
            }
        }
        flushToolOutputsAsUser()
        if (!injectedCatalog && catalogSuffix.isNotBlank()) {
            result.add(0, LlmWireMessage(role = LlmRole.SYSTEM, content = catalogSuffix.trim()))
        }
        return result
    }
}
