package com.forgemind.app.llm

import android.util.Base64
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ToolCallInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
     */
    val smartDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val clean = hostname.trim()
            try {
                val sys = Dns.SYSTEM.lookup(clean)
                if (sys.isNotEmpty()) return sys
            } catch (_: UnknownHostException) {
                // Fall through to DoH
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
                appendLine("\n[Attached file: ${att.name} (${att.mimeType}, ${att.sizeBytes} bytes, path: ${att.localPath})]")
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

    fun normalizeBaseUrl(raw: String, fallback: String): String {
        val compact = raw.replace(Regex("\\s+"), "").ifBlank { fallback.trim() }.trimEnd('/')
        val withScheme = if (compact.startsWith("http://", ignoreCase = true) ||
            compact.startsWith("https://", ignoreCase = true)
        ) {
            compact
        } else {
            val isLocal = compact.startsWith("127.0.0.1") ||
                compact.startsWith("localhost", ignoreCase = true) ||
                compact.startsWith("192.168.") ||
                compact.startsWith("10.")
            (if (isLocal) "http://" else "https://") + compact
        }
        val schemeEnd = withScheme.indexOf("://") + 3
        val head = withScheme.substring(0, schemeEnd)
        val tail = withScheme.substring(schemeEnd).replace(Regex("/{2,}"), "/")
        return (head + tail).trimEnd('/')
    }
}
