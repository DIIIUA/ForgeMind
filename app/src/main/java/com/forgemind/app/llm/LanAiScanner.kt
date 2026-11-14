package com.forgemind.app.llm

import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.WireProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

data class DiscoveredLanAiServer(
    val host: String,
    val port: Int,
    val engineLabel: String,
    val baseUrl: String,
    val suggestedProviderType: ProviderType,
    val wireProtocol: WireProtocol,
    val models: List<String>,
    val latencyMs: Long,
    val modelContextLengths: Map<String, Int> = emptyMap()
)

/**
 * High-speed parallel LAN & localhost AI server discovery + targeted single-host / direct URL connect.
 * Supports Ollama (:11434), LM Studio (:1234), llama.cpp / KoboldCpp (:8080/:5001),
 * vLLM / SGLang / LocalAI (:8000), Jan (:1337), GPT4All (:4891), OpenWebUI (:3000), and LiteLLM (:4000).
 */
class LanAiScanner {

    private val probeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1600, TimeUnit.MILLISECONDS)
            .readTimeout(2400, TimeUnit.MILLISECONDS)
            .writeTimeout(1600, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Fast targeted probe of a specific IP, hostname, `host:port`, or full `http(s)://...` URL
     * without scanning all 254 hosts in the /24 subnet.
     */
    suspend fun probeSpecificHost(rawInput: String): List<DiscoveredLanAiServer> = withContext(Dispatchers.IO) {
        val parsed = parseTargetInput(rawInput) ?: return@withContext emptyList()
        val portsToCheck = if (parsed.explicitPort != null) {
            listOf(parsed.explicitPort)
        } else {
            EXTENDED_LOCAL_PORTS
        }

        val results = coroutineScope {
            portsToCheck.map { port ->
                async {
                    val t0 = System.nanoTime()
                    val tcpOpen = isTcpPortOpen(parsed.host, port, timeoutMs = 1800)
                    val tcpMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    if (!tcpOpen && parsed.explicitPort == null) {
                        return@async null
                    }
                    val probed = probeOpenAiOrOllamaServer(
                        host = parsed.host,
                        port = port,
                        tcpLatencyMs = tcpMs,
                        scheme = parsed.scheme,
                        explicitPath = if (parsed.explicitPort != null) parsed.path else ""
                    )
                    if (probed != null) {
                        probed
                    } else if (tcpOpen && parsed.explicitPort != null) {
                        // TCP port is reachable on the user's explicit host:port, even if /v1/models requires auth or custom path
                        buildFallbackReachableServer(parsed, port, tcpMs)
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        results.sortedWith(
            compareByDescending<DiscoveredLanAiServer> { it.models.size }
                .thenBy { it.latencyMs }
        )
    }

    suspend fun scanNetwork(customHostInput: String = ""): List<DiscoveredLanAiServer> = withContext(Dispatchers.IO) {
        val localPorts = EXTENDED_LOCAL_PORTS
        val subnetPorts = listOf(11434, 1234, 8080, 8000, 5001, 4000)

        val candidateTargets = LinkedHashSet<Pair<String, Int>>()

        // 1) Explicit host entered by user (e.g. PC LAN IP or Tailscale 100.x.y.z)
        val cleanCustom = LlmHttpFactory.extractHostFromRawUrl(customHostInput)
        val explicitPort = extractExplicitPort(customHostInput)
        if (cleanCustom.isNotBlank()) {
            if (explicitPort != null) {
                candidateTargets += cleanCustom to explicitPort
            }
            for (p in localPorts) {
                candidateTargets += cleanCustom to p
            }
        }

        // 2) Device localhost (Termux / local daemon)
        for (p in localPorts) {
            candidateTargets += "127.0.0.1" to p
        }

        // 3) Active LAN IPv4 interfaces
        val localIpv4s = discoverLocalIpv4Addresses()
        for (selfIp in localIpv4s) {
            if (selfIp.startsWith("100.")) {
                // Tailscale / CGNAT self node
                for (p in subnetPorts) candidateTargets += selfIp to p
                continue
            }
            val prefix = selfIp.substringBeforeLast('.', "")
            if (prefix.isBlank()) continue
            // Prioritize common router/desktop static IPs first
            val priorityHosts = listOf(1, 2, 10, 20, 50, 100, 101, 102, 150, 200)
            for (lastOctet in priorityHosts) {
                val ip = "$prefix.$lastOctet"
                for (p in subnetPorts) candidateTargets += ip to p
            }
            for (lastOctet in 1..254) {
                val ip = "$prefix.$lastOctet"
                for (p in subnetPorts) candidateTargets += ip to p
            }
        }

        val tcpSemaphore = Semaphore(64)
        val openEndpoints = coroutineScope {
            candidateTargets.map { (host, port) ->
                async {
                    tcpSemaphore.withPermit {
                        val t0 = System.nanoTime()
                        val timeout = when {
                            host == cleanCustom && cleanCustom.isNotBlank() -> 1200
                            host == "127.0.0.1" -> 180
                            else -> 300
                        }
                        if (isTcpPortOpen(host, port, timeoutMs = timeout)) {
                            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                            Triple(host, port, ms)
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (openEndpoints.isEmpty()) return@withContext emptyList()

        val httpSemaphore = Semaphore(16)
        coroutineScope {
            openEndpoints.map { (host, port, tcpMs) ->
                async {
                    httpSemaphore.withPermit {
                        probeOpenAiOrOllamaServer(host, port, tcpMs)
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .sortedWith(
                    compareBy<DiscoveredLanAiServer> {
                        when {
                            cleanCustom.isNotBlank() && it.host.equals(cleanCustom, ignoreCase = true) -> 0
                            it.host != "127.0.0.1" -> 1
                            else -> 2
                        }
                    }
                        .thenByDescending { it.models.size }
                        .thenBy { it.latencyMs }
                )
        }
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun probeOpenAiOrOllamaServer(
        host: String,
        port: Int,
        tcpLatencyMs: Long,
        scheme: String = "http",
        explicitPath: String = ""
    ): DiscoveredLanAiServer? {
        val rootUrl = "$scheme://$host:$port"
        val startMs = System.currentTimeMillis()

        // 1) If standard Ollama port or `/api` path, probe Ollama `/api/tags` first
        if (port == 11434 || explicitPath.startsWith("/api")) {
            val ollamaModels = probeOllamaTags(rootUrl)
            if (ollamaModels != null) {
                val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(tcpLatencyMs)
                val where = if (host == "127.0.0.1") "Termux / 127.0.0.1" else host
                return DiscoveredLanAiServer(
                    host = host,
                    port = port,
                    engineLabel = "Ollama ($where)",
                    baseUrl = rootUrl,
                    suggestedProviderType = ProviderType.OLLAMA,
                    wireProtocol = WireProtocol.OLLAMA_NATIVE,
                    models = ollamaModels,
                    latencyMs = rtt
                )
            }
        }

        // 2) Probe OpenAI-compatible `/v1/models` (or explicit custom path) first,
        // and enrich missing context lengths via LM Studio `/api/v0/models` or llama.cpp `/props` only when needed.
        val candidateModelsUrls = buildList {
            if (explicitPath.isNotBlank() && explicitPath != "/") {
                val trimmedPath = explicitPath.trimEnd('/')
                if (trimmedPath.endsWith("/models")) add("$rootUrl$trimmedPath")
                else add("$rootUrl$trimmedPath/models")
            }
            add("$rootUrl/v1/models")
        }.distinct()

        for (modelsUrl in candidateModelsUrls) {
            val openAiRes = probeOpenAiModelsWithContext(modelsUrl)
            if (openAiRes != null) {
                val (models, parsedCtx) = openAiRes
                val mergedCtx = LinkedHashMap<String, Int>()
                mergedCtx.putAll(parsedCtx)
                if (models.any { it !in mergedCtx }) {
                    val lmStudioCtxMap = probeLmStudioV0Contexts(rootUrl)
                    mergedCtx.putAll(lmStudioCtxMap)
                    if (lmStudioCtxMap.isEmpty()) {
                        val llamaCppCtx = probeLlamaCppPropsContext(rootUrl)
                        if (llamaCppCtx != null) {
                            for (m in models) {
                                mergedCtx.putIfAbsent(m, llamaCppCtx)
                            }
                        }
                    }
                }
                val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(tcpLatencyMs)
                val (label, suggestedType) = classifyEngineByPort(host, port)
                val finalBase = modelsUrl.removeSuffix("/models")
                return DiscoveredLanAiServer(
                    host = host,
                    port = port,
                    engineLabel = label,
                    baseUrl = finalBase,
                    suggestedProviderType = suggestedType,
                    wireProtocol = WireProtocol.OPENAI_COMPAT,
                    models = models,
                    latencyMs = rtt,
                    modelContextLengths = mergedCtx
                )
            }
        }

        // 3) Probe Ollama `/api/tags` on non-standard ports if `/v1/models` did not match
        if (port != 11434 && port !in setOf(1234, 8000)) {
            val ollamaModels = probeOllamaTags(rootUrl)
            if (ollamaModels != null) {
                val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(tcpLatencyMs)
                val where = if (host == "127.0.0.1") "Termux / 127.0.0.1" else host
                return DiscoveredLanAiServer(
                    host = host,
                    port = port,
                    engineLabel = "Ollama ($where)",
                    baseUrl = rootUrl,
                    suggestedProviderType = ProviderType.OLLAMA,
                    wireProtocol = WireProtocol.OLLAMA_NATIVE,
                    models = ollamaModels,
                    latencyMs = rtt
                )
            }
        }

        // 4) Probe KoboldCpp `/api/v1/model`
        val koboldModel = probeKoboldModel("$rootUrl/api/v1/model")
        if (koboldModel != null) {
            val koboldCtx = probeKoboldMaxContext(rootUrl)
            val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(tcpLatencyMs)
            return DiscoveredLanAiServer(
                host = host,
                port = port,
                engineLabel = "KoboldCpp ($host:$port)",
                baseUrl = "$rootUrl/v1",
                suggestedProviderType = ProviderType.LLAMA_CPP,
                wireProtocol = WireProtocol.OPENAI_COMPAT,
                models = listOf(koboldModel),
                latencyMs = rtt,
                modelContextLengths = if (koboldCtx != null) mapOf(koboldModel to koboldCtx) else emptyMap()
            )
        }

        return null
    }

    private fun probeOllamaTags(rootUrl: String): List<String>? = runCatching {
        val req = Request.Builder().url("$rootUrl/api/tags").get().build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
            val arr = obj["models"]?.jsonArray ?: return null
            arr.mapNotNull { el ->
                el.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
    }.getOrNull()

    private fun probeOpenAiModelsWithContext(modelsUrl: String): Pair<List<String>, Map<String, Int>>? = runCatching {
        val req = Request.Builder().url(modelsUrl).get().build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            val obj = LlmHttpFactory.json.parseToJsonElement(body).jsonObject
            val arr = obj["data"]?.jsonArray ?: return null
            val names = mutableListOf<String>()
            val ctxMap = LinkedHashMap<String, Int>()
            for (el in arr) {
                val item = el.jsonObject
                val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                names += id
                val ctx = item["loaded_context_length"]?.jsonPrimitive?.intOrNull
                    ?: item["context_length"]?.jsonPrimitive?.intOrNull
                    ?: item["max_model_len"]?.jsonPrimitive?.intOrNull
                    ?: item["max_context_length"]?.jsonPrimitive?.intOrNull
                    ?: item["n_ctx"]?.jsonPrimitive?.intOrNull
                    ?: item["context_window"]?.jsonPrimitive?.intOrNull
                if (ctx != null && ctx >= 1024) {
                    ctxMap[id] = ctx
                }
            }
            names to ctxMap
        }
    }.getOrNull()

    private fun probeLmStudioV0Contexts(rootUrl: String): Map<String, Int> = runCatching {
        val req = Request.Builder().url("$rootUrl/api/v0/models").get().build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val obj = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val arr = obj["data"]?.jsonArray ?: return emptyMap()
            val map = LinkedHashMap<String, Int>()
            for (el in arr) {
                val m = el.jsonObject
                val id = m["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val ctx = m["loaded_context_length"]?.jsonPrimitive?.intOrNull
                    ?: m["max_context_length"]?.jsonPrimitive?.intOrNull
                if (ctx != null && ctx >= 1024) {
                    map[id] = ctx
                }
            }
            map
        }
    }.getOrDefault(emptyMap())

    private fun probeLlamaCppPropsContext(rootUrl: String): Int? = runCatching {
        val req = Request.Builder().url("$rootUrl/props").get().build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val obj = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            obj["default_generation_settings"]?.jsonObject?.get("n_ctx")?.jsonPrimitive?.intOrNull?.takeIf { it >= 1024 }
                ?: obj["n_ctx"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1024 }
        }
    }.getOrNull()

    private fun probeKoboldMaxContext(rootUrl: String): Int? = runCatching {
        val req = Request.Builder().url("$rootUrl/api/extra/true_max_context_length").get().build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val obj = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            obj["value"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1024 }
        }
    }.getOrNull()

    private fun probeKoboldModel(url: String): String? = runCatching {
        val req = Request.Builder().url(url).get().build()
        probeHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val obj = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            obj["result"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "ReadOnly" }
        }
    }.getOrNull()

    data class ParsedLanTarget(
        val scheme: String,
        val host: String,
        val explicitPort: Int?,
        val path: String
    )

    companion object {
        val EXTENDED_LOCAL_PORTS = listOf(11434, 1234, 8080, 8000, 5001, 5000, 1337, 4000, 4891, 3000)

        fun parseTargetInput(rawInput: String): ParsedLanTarget? {
            val trimmed = rawInput.trim().removeSuffix("#").trim()
            if (trimmed.isEmpty()) return null
            val scheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https"
                else -> "http"
            }
            val host = LlmHttpFactory.extractHostFromRawUrl(trimmed)
            if (host.isBlank()) return null
            val port = extractExplicitPort(trimmed)
            val withoutScheme = trimmed
                .removePrefix("http://").removePrefix("https://")
                .removePrefix("HTTP://").removePrefix("HTTPS://")
            val slashIdx = withoutScheme.indexOf('/')
            val path = if (slashIdx >= 0) withoutScheme.substring(slashIdx).substringBefore('?').trimEnd('/') else ""
            return ParsedLanTarget(scheme = scheme, host = host, explicitPort = port, path = path)
        }

        fun buildFallbackReachableServer(
            parsed: ParsedLanTarget,
            port: Int,
            latencyMs: Long = 0L
        ): DiscoveredLanAiServer {
            val isOllama = port == 11434 || parsed.path.startsWith("/api")
            val (label, suggestedType) = classifyEngineByPort(parsed.host, port)
            val baseUrl = when {
                isOllama -> "${parsed.scheme}://${parsed.host}:$port"
                parsed.path.isNotBlank() -> "${parsed.scheme}://${parsed.host}:$port${parsed.path}"
                else -> "${parsed.scheme}://${parsed.host}:$port/v1"
            }
            return DiscoveredLanAiServer(
                host = parsed.host,
                port = port,
                engineLabel = label,
                baseUrl = baseUrl,
                suggestedProviderType = if (isOllama) ProviderType.OLLAMA else suggestedType,
                wireProtocol = if (isOllama) WireProtocol.OLLAMA_NATIVE else WireProtocol.OPENAI_COMPAT,
                models = emptyList(),
                latencyMs = latencyMs
            )
        }

        fun buildDirectEndpointServer(rawInput: String): DiscoveredLanAiServer? {
            val parsed = parseTargetInput(rawInput) ?: return null
            val port = parsed.explicitPort ?: when {
                parsed.path.startsWith("/api") -> 11434
                parsed.scheme == "https" -> 443
                else -> 1234
            }
            return buildFallbackReachableServer(parsed, port, latencyMs = 0L)
        }

        fun classifyEngineByPort(host: String, port: Int): Pair<String, ProviderType> = when (port) {
            11434 -> "Ollama ($host:$port)" to ProviderType.OLLAMA
            1234 -> "LM Studio ($host:$port)" to ProviderType.LM_STUDIO
            8080, 5001 -> "llama.cpp / KoboldCpp ($host:$port)" to ProviderType.LLAMA_CPP
            8000 -> "vLLM / SGLang / LocalAI ($host:$port)" to ProviderType.VLLM_LOCAL
            1337 -> "Jan Local AI ($host:$port)" to ProviderType.LM_STUDIO
            4891 -> "GPT4All ($host:$port)" to ProviderType.LM_STUDIO
            3000 -> "OpenWebUI / Local Gateway ($host:$port)" to ProviderType.CUSTOM
            4000 -> "LiteLLM Gateway ($host:$port)" to ProviderType.CUSTOM
            else -> "Local OpenAI Server ($host:$port)" to ProviderType.LM_STUDIO
        }

        fun extractExplicitPort(rawInput: String): Int? {
            val clean = rawInput.trim().removeSuffix("#").trim()
                .removePrefix("http://").removePrefix("https://")
                .removePrefix("HTTP://").removePrefix("HTTPS://")
                .substringBefore('/')
                .substringBefore('?')
                .substringAfterLast('@')
            if (clean.startsWith('[')) {
                val afterBracket = clean.substringAfter(']', "")
                if (!afterBracket.startsWith(':')) return null
                return afterBracket.removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
            }
            if (!clean.contains(':')) return null
            return clean.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
        }

        fun discoverLocalIpv4Addresses(): List<String> = runCatching {
            val found = LinkedHashSet<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (LlmHttpFactory.isLocalOrPrivateHost(ip)) {
                            found += ip
                        }
                    }
                }
            }
            found.toList()
        }.getOrDefault(emptyList())
    }
}
