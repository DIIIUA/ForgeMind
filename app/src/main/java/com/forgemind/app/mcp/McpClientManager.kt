package com.forgemind.app.mcp

import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class McpCallResult(
    val text: String,
    val isError: Boolean = false,
    val savedFilePaths: List<String> = emptyList()
)

data class McpDiscoveryMetadata(
    val directToolCount: Int = 0,
    val resourceCount: Int = 0,
    val promptCount: Int = 0,
    val instructions: String = "",
    val hasGatewayOrCodeMode: Boolean = false,
    val taskerToolCount: Int = 0
)

data class McpCatalogInsight(
    val directToolCount: Int,
    val isGatewayOrCodeMode: Boolean,
    val taskerToolCount: Int,
    val resourceCount: Int,
    val promptCount: Int,
    val summaryLine: String
)

class McpClientManager(
    private val mcpServerDao: McpServerDao,
    private val termuxBridge: TermuxBridge,
    private val chatFilesDir: File
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = LlmHttpFactory.baseClientBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sseClient = LlmHttpFactory.baseClientBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpcId = AtomicInteger(1)
    private val imageSeq = AtomicInteger(1)
    private val sessionIds = ConcurrentHashMap<String, String>()
    private val sseSessions = ConcurrentHashMap<String, LegacySseSession>()
    private val activeTools = ConcurrentHashMap<String, List<McpToolInfo>>()
    private val discoveryMetadata = ConcurrentHashMap<String, McpDiscoveryMetadata>()

    private data class DiscoveredServerResult(
        val tools: List<McpToolInfo>,
        val instructions: String
    )

    fun getDiscoveryMetadata(serverId: String): McpDiscoveryMetadata? = discoveryMetadata[serverId]

    fun resolveServerInstructions(server: McpServerEntity): String =
        discoveryMetadata[server.id]?.instructions?.takeIf { it.isNotBlank() }
            ?: server.instructions.trim()

    fun analyzeServerCatalog(server: McpServerEntity, tools: List<McpToolInfo>): McpCatalogInsight =
        analyzeCatalog(tools, discoveryMetadata[server.id])

    private class LegacySseSession(
        val configKey: String,
        val call: Call,
        val response: Response,
        val endpointDeferred: CompletableDeferred<String>,
        val pending: ConcurrentHashMap<String, CompletableDeferred<JsonObject>>,
        val open: AtomicBoolean,
        val initialized: AtomicBoolean,
        var readerJob: Job? = null
    ) {
        fun close() {
            open.set(false)
            runCatching { call.cancel() }
            runCatching { response.close() }
            runCatching { readerJob?.cancel() }
            if (!endpointDeferred.isCompleted) {
                endpointDeferred.completeExceptionally(IllegalStateException("SSE session closed"))
            }
            pending.values.forEach { d ->
                if (!d.isCompleted) d.completeExceptionally(IllegalStateException("SSE connection closed"))
            }
            pending.clear()
        }
    }

    suspend fun getAllServers(): List<McpServerEntity> = mcpServerDao.getAll()

    suspend fun getEnabledToolsMap(): Map<McpServerEntity, List<McpToolInfo>> {
        val enabled = mcpServerDao.getEnabled()
        val out = linkedMapOf<McpServerEntity, List<McpToolInfo>>()
        for (srv in enabled) {
            val cached = activeTools[srv.id] ?: decodeTools(srv.cachedToolsJson)
            out[srv] = cached
        }
        return out
    }

    suspend fun connectAndDiscover(serverId: String): Result<List<McpToolInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                sessionIds.remove(serverId)
                sseSessions.remove(serverId)?.close()
                val server = mcpServerDao.getById(serverId) ?: error("MCP server not found: $serverId")
                val discovered = discoverServer(server).getOrThrow()
                val tools = discovered.tools
                activeTools[server.id] = tools
                mcpServerDao.upsert(
                    server.copy(
                        cachedToolsJson = json.encodeToString(tools),
                        lastConnectedAt = System.currentTimeMillis(),
                        lastError = null,
                        instructions = discovered.instructions
                    )
                )
                tools
            }.onFailure { err ->
                mcpServerDao.getById(serverId)?.let { srv ->
                    mcpServerDao.upsert(srv.copy(lastError = err.message ?: "Connection failed"))
                }
            }
        }

    suspend fun connectDynamicServer(
        name: String,
        endpoint: String,
        transport: McpTransport = McpTransport.STREAMABLE_HTTP,
        headersJson: String = "{}"
    ): Result<Pair<McpServerEntity, List<McpToolInfo>>> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = mcpServerDao.getAll().firstOrNull {
                it.endpoint.equals(endpoint.trim(), ignoreCase = true) ||
                    it.name.equals(name.trim(), ignoreCase = true)
            }
            val entity = McpServerEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "mcp_${System.currentTimeMillis() % 10000}" },
                transport = transport.name,
                endpoint = endpoint.trim(),
                headersJson = headersJson.ifBlank { "{}" },
                enabled = true
            )
            mcpServerDao.upsert(entity)
            val discovered = discoverServer(entity).getOrThrow()
            val tools = discovered.tools
            activeTools[entity.id] = tools
            val saved = entity.copy(
                cachedToolsJson = json.encodeToString(tools),
                lastConnectedAt = System.currentTimeMillis(),
                lastError = null,
                instructions = discovered.instructions
            )
            mcpServerDao.upsert(saved)
            saved to tools
        }
    }

    suspend fun callTool(
        serverId: String,
        toolName: String,
        argumentsJson: String
    ): McpCallResult = withContext(Dispatchers.IO) {
        val server = mcpServerDao.getById(serverId)
            ?: return@withContext McpCallResult("MCP server '$serverId' not found", isError = true)

        val argsElement = runCatching {
            json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse { buildJsonObject {} }

        val res = sendRpc(
            server = server,
            method = "tools/call",
            params = buildJsonObject {
                put("name", JsonPrimitive(toolName))
                put("arguments", argsElement)
            }
        )

        res.fold(
            onSuccess = { root ->
                val resultObj = root["result"]?.jsonObject
                val errorObj = root["error"]?.jsonObject
                if (errorObj != null) {
                    val code = errorObj["code"]?.toString() ?: ""
                    val msg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: errorObj.toString()
                    return@withContext McpCallResult("MCP error $code: $msg", isError = true)
                }
                if (resultObj == null) {
                    return@withContext McpCallResult(root.toString())
                }
                val isErr = resultObj["isError"]?.jsonPrimitive?.contentOrNull == "true"
                val contentArr = resultObj["content"]?.jsonArray
                if (contentArr == null || contentArr.isEmpty()) {
                    return@withContext McpCallResult(resultObj.toString(), isError = isErr)
                }

                val texts = mutableListOf<String>()
                val savedPaths = mutableListOf<String>()
                for (itemEl in contentArr) {
                    val item = itemEl.jsonObject
                    when (item["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> item["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
                        "image" -> {
                            val b64 = item["data"]?.jsonPrimitive?.contentOrNull
                            val mime = item["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                            if (!b64.isNullOrBlank()) {
                                val ext = imageExtFromMime(mime)
                                chatFilesDir.mkdirs()
                                val out = File(chatFilesDir, "mcp_${System.currentTimeMillis()}_${imageSeq.getAndIncrement()}.$ext")
                                runCatching {
                                    out.writeBytes(java.util.Base64.getMimeDecoder().decode(b64))
                                    savedPaths += out.absolutePath
                                    texts += "[MCP returned image: ${out.absolutePath}]"
                                }
                            }
                        }
                        "resource" -> {
                            val r = item["resource"]?.jsonObject
                            val txt = r?.get("text")?.jsonPrimitive?.contentOrNull ?: r?.toString()
                            if (!txt.isNullOrBlank()) texts += txt
                        }
                        else -> texts += item.toString()
                    }
                }
                McpCallResult(
                    text = texts.joinToString("\n\n").ifBlank { resultObj.toString() },
                    isError = isErr,
                    savedFilePaths = savedPaths
                )
            },
            onFailure = { e ->
                McpCallResult("MCP call '$toolName' failed (${server.name}): ${e.message}", isError = true)
            }
        )
    }

    suspend fun listResources(serverId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val server = mcpServerDao.getById(serverId) ?: error("MCP server not found")
            val root = sendRpc(server, "resources/list", buildJsonObject {}).getOrThrow()
            root["result"]?.toString() ?: root.toString()
        }
    }

    suspend fun readResource(serverId: String, uri: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val server = mcpServerDao.getById(serverId) ?: error("MCP server not found")
            val root = sendRpc(
                server,
                "resources/read",
                buildJsonObject { put("uri", JsonPrimitive(uri)) }
            ).getOrThrow()
            root["result"]?.toString() ?: root.toString()
        }
    }

    private suspend fun discoverServer(server: McpServerEntity): Result<DiscoveredServerResult> {
        // 1. initialize
        val initParams = buildJsonObject {
            put("protocolVersion", JsonPrimitive("2025-03-26"))
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", JsonPrimitive("ForgeMind-Android"))
                put("version", JsonPrimitive("1.0.0"))
            })
        }
        val initRes = sendRpc(server, "initialize", initParams)
        if (initRes.isFailure) return Result.failure(initRes.exceptionOrNull()!!)

        val initObj = initRes.getOrThrow()
        val initResultObj = runCatching { initObj["result"]?.jsonObject }.getOrNull()
        val instructions = runCatching {
            val el = initResultObj?.get("instructions")
            el?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        }.getOrDefault("")

        // 2. notifications/initialized (best effort)
        runCatching { sendRpcNotification(server, "notifications/initialized") }

        // 3. tools/list (with nextCursor pagination support)
        val collected = collectPaginatedTools(server).getOrElse {
            return Result.failure(it)
        }
        recordDiscoveryMetadata(server.id, collected, instructions)
        if (collected.isEmpty()) return Result.success(DiscoveredServerResult(emptyList(), instructions))
        val toolsArray = JsonArray(collected)

        val parsed = toolsArray.mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val schema = obj["inputSchema"]?.toString() ?: """{"type":"object","properties":{}}"""
            McpToolInfo(name = name, description = desc, inputSchemaJson = schema)
        }
        return Result.success(DiscoveredServerResult(parsed, instructions))
    }

    private fun imageExtFromMime(mime: String): String {
        val lower = mime.lowercase()
        return when {
            lower.contains("jpeg") || lower.contains("jpg") -> "jpg"
            lower.contains("webp") -> "webp"
            lower.contains("gif") -> "gif"
            else -> "png"
        }
    }

    private suspend fun collectPaginatedTools(server: McpServerEntity): Result<List<JsonElement>> {
        val byName = LinkedHashMap<String, JsonElement>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null
        for (page in 0 until MAX_TOOLS_PAGES) {
            val params = buildJsonObject {
                cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", JsonPrimitive(it)) }
            }
            val toolsRes = sendRpc(server, "tools/list", params)
            if (toolsRes.isFailure) {
                return if (byName.isEmpty()) Result.failure(toolsRes.exceptionOrNull()!!) else Result.success(byName.values.toList())
            }
            val root = toolsRes.getOrThrow()
            val err = root["error"]
            if (err != null) {
                return if (byName.isEmpty()) Result.failure(IllegalStateException("MCP tools/list error: $err")) else Result.success(byName.values.toList())
            }
            val resultObj = root["result"]?.jsonObject ?: break
            val toolsArray = resultObj["tools"]?.jsonArray ?: break
            var addedThisPage = 0
            toolsArray.forEachIndexed { idx, el ->
                val name = runCatching { el.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "__anon_${page}_$idx"
                if (!byName.containsKey(name)) {
                    byName[name] = el
                    addedThisPage++
                }
            }
            val nextCursor = resultObj["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (nextCursor == null || addedThisPage == 0 || !seenCursors.add(nextCursor)) {
                break
            }
            cursor = nextCursor
        }
        return Result.success(byName.values.toList())
    }

    private fun recordDiscoveryMetadata(
        serverId: String,
        toolElements: List<JsonElement>,
        instructions: String = ""
    ) {
        var taskerCount = 0
        var hasGateway = false
        for (el in toolElements) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            if (name.startsWith("tasker_")) taskerCount++
            if (isGatewayIndicator(name, desc, toolElements.size)) hasGateway = true
        }
        discoveryMetadata[serverId] = McpDiscoveryMetadata(
            directToolCount = toolElements.size,
            instructions = instructions,
            hasGatewayOrCodeMode = hasGateway,
            taskerToolCount = taskerCount
        )
    }

    private suspend fun sendRpc(
        server: McpServerEntity,
        method: String,
        params: JsonObject
    ): Result<JsonObject> {
        val id = rpcId.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        return when (runCatching { McpTransport.valueOf(server.transport) }.getOrDefault(McpTransport.STREAMABLE_HTTP)) {
            McpTransport.SSH_STDIO -> sendViaSshStdio(server, payload, expectReply = true)
            McpTransport.STREAMABLE_HTTP -> sendViaHttp(server, payload, expectReply = true)
            McpTransport.HTTP_SSE -> sendViaLegacySse(server, payload, expectReply = true)
        }
    }

    private suspend fun sendRpcNotification(server: McpServerEntity, method: String) {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
        }
        when (runCatching { McpTransport.valueOf(server.transport) }.getOrDefault(McpTransport.STREAMABLE_HTTP)) {
            McpTransport.SSH_STDIO -> sendViaSshStdio(server, payload, expectReply = false)
            McpTransport.STREAMABLE_HTTP -> sendViaHttp(server, payload, expectReply = false)
            McpTransport.HTTP_SSE -> sendViaLegacySse(server, payload, expectReply = false)
        }
    }

    private fun isHtmlAuthIntercept(body: String, contentType: String): Boolean {
        val ct = contentType.lowercase()
        val head = body.trimStart().take(400).lowercase()
        return ct.contains("text/html") ||
            head.startsWith("<!doctype html") ||
            head.startsWith("<html") ||
            head.contains("cloudflareaccess")
    }

    private suspend fun getOrOpenSseSession(server: McpServerEntity): LegacySseSession {
        val configKey = "${server.endpoint.trim()}|${server.headersJson}"
        val existing = sseSessions[server.id]
        if (existing != null && existing.open.get() && existing.configKey == configKey) {
            return existing
        }
        existing?.close()
        sseSessions.remove(server.id)

        val sseUrl = server.endpoint.trim()
        val reqBuilder = Request.Builder()
            .url(sseUrl)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
        LlmHttpFactory.applyCustomHeaders(reqBuilder, server.headersJson)

        val call = sseClient.newCall(reqBuilder.build())
        val resp = try {
            call.execute()
        } catch (e: Exception) {
            error("SSE network error ($sseUrl): ${e.message ?: e::class.java.simpleName}")
        }

        val ct = resp.header("Content-Type").orEmpty()
        if (!resp.isSuccessful) {
            val errBody = runCatching { resp.body?.string().orEmpty().take(500) }.getOrDefault("")
            resp.close()
            if (isHtmlAuthIntercept(errBody, ct)) {
                error("SSE HTTP ${resp.code}: Cloudflare Access / HTML login page — add CF-Access-Client-Id/Secret or token")
            }
            error("SSE HTTP ${resp.code} ($sseUrl): $errBody")
        }
        if (ct.lowercase().contains("text/html")) {
            val preview = runCatching { resp.body?.string().orEmpty().take(300) }.getOrDefault("")
            resp.close()
            error("SSE server returned HTML instead of text/event-stream (check URL / Cloudflare Access auth): $preview")
        }

        val endpointDeferred = CompletableDeferred<String>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val open = AtomicBoolean(true)
        val initialized = AtomicBoolean(false)
        val session = LegacySseSession(configKey, call, resp, endpointDeferred, pending, open, initialized)
        sseSessions[server.id] = session

        session.readerJob = sseScope.launch {
            try {
                val stream = resp.body?.byteStream() ?: error("Empty SSE stream")
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var eventName = ""
                    val dataBuf = StringBuilder()
                    while (open.get()) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                val ev = eventName.trim().lowercase()
                                val data = dataBuf.toString().trim()
                                eventName = ""
                                dataBuf.clear()
                                if (data.isNotEmpty()) {
                                    if (!endpointDeferred.isCompleted &&
                                        (ev == "endpoint" || (!data.startsWith("{") && (data.startsWith("/") || data.startsWith("http"))))
                                    ) {
                                        endpointDeferred.complete(resolveEndpointUrl(sseUrl, data))
                                    } else if (data.startsWith("{")) {
                                        runCatching {
                                            val obj = json.parseToJsonElement(data).jsonObject
                                            val msgId = obj["id"]?.jsonPrimitive?.contentOrNull
                                            if (msgId != null) {
                                                pending.remove(msgId)?.complete(obj)
                                            }
                                        }
                                    }
                                }
                            }
                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                dataBuf.append(line.removePrefix("data:").trimStart())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!endpointDeferred.isCompleted) {
                    endpointDeferred.completeExceptionally(e)
                }
            } finally {
                open.set(false)
                session.close()
                sseSessions.remove(server.id, session)
            }
        }
        return session
    }

    private suspend fun sendViaLegacySse(
        server: McpServerEntity,
        payload: JsonObject,
        expectReply: Boolean,
        allowReconnect: Boolean = true
    ): Result<JsonObject> {
        val method = payload["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val res = runCatching {
            val session = getOrOpenSseSession(server)
            val postUrl = withTimeout(15_000) { session.endpointDeferred.await() }

            if (method == "initialize") {
                session.initialized.set(true)
            } else if (method != "notifications/initialized" && !session.initialized.get()) {
                val initId = rpcId.getAndIncrement().toString()
                val initDeferred = CompletableDeferred<JsonObject>()
                session.pending[initId] = initDeferred
                val initPayload = buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", JsonPrimitive(initId.toInt()))
                    put("method", JsonPrimitive("initialize"))
                    put("params", buildJsonObject {
                        put("protocolVersion", JsonPrimitive("2025-03-26"))
                        put("capabilities", buildJsonObject {})
                        put("clientInfo", buildJsonObject {
                            put("name", JsonPrimitive("ForgeMind-Android"))
                            put("version", JsonPrimitive("1.0.0"))
                        })
                    })
                }
                postSseMessage(server, postUrl, initPayload, initId, initDeferred, expectReply = true)
                val notifPayload = buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("method", JsonPrimitive("notifications/initialized"))
                }
                runCatching { postSseMessage(server, postUrl, notifPayload, null, null, expectReply = false) }
                session.initialized.set(true)
            }

            val idStr = payload["id"]?.jsonPrimitive?.contentOrNull
            val replyDeferred = if (expectReply && idStr != null) {
                CompletableDeferred<JsonObject>().also { session.pending[idStr] = it }
            } else null

            postSseMessage(server, postUrl, payload, idStr, replyDeferred, expectReply)
        }

        if (res.isFailure && allowReconnect) {
            sseSessions.remove(server.id)?.close()
            if (method != "initialize" && method != "notifications/initialized") {
                return sendViaLegacySse(server, payload, expectReply, allowReconnect = false)
            }
        }
        return res
    }

    private suspend fun postSseMessage(
        server: McpServerEntity,
        postUrl: String,
        payload: JsonObject,
        idStr: String?,
        replyDeferred: CompletableDeferred<JsonObject>?,
        expectReply: Boolean
    ): JsonObject {
        val reqBuilder = Request.Builder()
            .url(postUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        LlmHttpFactory.applyCustomHeaders(reqBuilder, server.headersJson)

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                sseSessions[server.id]?.pending?.remove(idStr)
                val errBody = resp.body?.string().orEmpty().take(500)
                if (resp.code == 404 || resp.code == 410) {
                    sseSessions.remove(server.id)?.close()
                }
                error("SSE POST HTTP ${resp.code} ($postUrl): $errBody")
            }
            if (!expectReply) {
                return buildJsonObject { put("ok", JsonPrimitive(true)) }
            }
            val ct = resp.header("Content-Type").orEmpty()
            val rawBody = resp.body?.string().orEmpty()
            if (isHtmlAuthIntercept(rawBody, ct)) {
                sseSessions[server.id]?.pending?.remove(idStr)
                error("SSE POST returned an HTML authentication page (Cloudflare Access)")
            }
            if (resp.code != 202 && resp.code != 204 && rawBody.isNotBlank()) {
                sseSessions[server.id]?.pending?.remove(idStr)
                return parseJsonOrSseResponse(rawBody, ct)
            }
        }
        return withTimeout(25_000) {
            replyDeferred?.await() ?: buildJsonObject { put("ok", JsonPrimitive(true)) }
        }
    }

    private fun sendViaHttp(
        server: McpServerEntity,
        payload: JsonObject,
        expectReply: Boolean
    ): Result<JsonObject> = runCatching {
        val url = server.endpoint.trim()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        sessionIds[server.id]?.let { sid ->
            reqBuilder.header("Mcp-Session-Id", sid)
        }
        LlmHttpFactory.applyCustomHeaders(reqBuilder, server.headersJson)

        val resp = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            error("Network error ($url): ${e.message ?: e::class.java.simpleName}")
        }
        resp.use { r ->
            r.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sid ->
                sessionIds[server.id] = sid
            }
            val contentType = r.header("Content-Type").orEmpty()
            val rawBody = r.body?.string().orEmpty()
            if (isHtmlAuthIntercept(rawBody, contentType)) {
                error("HTTP ${r.code} ($url): received HTML login page (Cloudflare Access / Login) — check token or CF-Access-Client-Id/Secret")
            }
            if (r.code == 404 || r.code == 410) {
                sessionIds.remove(server.id)
            }
            if (!r.isSuccessful) {
                error("HTTP ${r.code} ($url): ${rawBody.take(600)}")
            }
            if (!expectReply || r.code == 202 || r.code == 204) {
                return@use buildJsonObject { put("ok", JsonPrimitive(true)) }
            }
            if (rawBody.isBlank()) {
                return@use buildJsonObject { put("ok", JsonPrimitive(true)) }
            }
            parseJsonOrSseResponse(rawBody, contentType)
        }
    }

    private suspend fun sendViaSshStdio(
        server: McpServerEntity,
        payload: JsonObject,
        expectReply: Boolean
    ): Result<JsonObject> = runCatching {
        val initJson = """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"ForgeMind","version":"1.0"}}}"""
        val method = payload["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val inputLines = if (method == "initialize") {
            payload.toString()
        } else {
            "$initJson\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n$payload"
        }
        val sshServerHint = runCatching {
            val obj = json.parseToJsonElement(server.headersJson.ifBlank { "{}" }).jsonObject
            obj["_ssh_server"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val b64 = java.util.Base64.getEncoder().encodeToString(inputLines.toByteArray(Charsets.UTF_8))
        val cmd = "printf '%s\\n' ${TermuxBridge.shellSingleQuote(b64)} | base64 -d | ${server.endpoint}"
        val res = termuxBridge.runCommand(cmd, timeoutSeconds = 30, serverHint = sshServerHint)
        if (res.error != null) error(res.error)
        if (!expectReply) return@runCatching buildJsonObject { put("ok", JsonPrimitive(true)) }
        val lines = res.stdout.lines().map { it.trim() }.filter { it.startsWith("{") }
        val targetLine = lines.lastOrNull()
            ?: error("Stdio MCP returned no JSON-RPC response. stderr=${res.stderr.take(400)}")
        json.parseToJsonElement(targetLine).jsonObject
    }

    fun decodeTools(raw: String): List<McpToolInfo> =
        runCatching { json.decodeFromString<List<McpToolInfo>>(raw) }.getOrDefault(emptyList())

    companion object {
        const val MAX_TOOLS_PAGES = 30
        private val looseJson = Json { ignoreUnknownKeys = true }

        fun isGatewayIndicator(nameLower: String, descLower: String, totalTools: Int): Boolean {
            if (nameLower in setOf("search", "execute", "search_and_execute", "codemode", "gateway_search")) {
                return true
            }
            if (descLower.contains("codemode") || descLower.contains("code mode")) {
                return true
            }
            return totalTools in 1..25 && (nameLower.startsWith("gateway_") || nameLower.startsWith("portal_") || descLower.contains("catalog") || descLower.contains("gateway"))
        }

        fun analyzeCatalog(
            tools: List<McpToolInfo>,
            metadata: McpDiscoveryMetadata? = null
        ): McpCatalogInsight {
            val directCount = tools.size
            val taskerCount = maxOf(
                metadata?.taskerToolCount ?: 0,
                tools.count { it.name.startsWith("tasker_", ignoreCase = true) }
            )
            val isGateway = (metadata?.hasGatewayOrCodeMode == true) || tools.any {
                isGatewayIndicator(it.name.lowercase(), it.description.lowercase(), directCount)
            }
            val summary = when {
                directCount == 0 -> "tools: 0 (run connection check or verify credentials)"
                isGateway && directCount <= 25 ->
                    "direct facades: $directCount (gateway / Code Mode catalog)"
                taskerCount > 0 ->
                    "tools: $directCount (including Tasker: $taskerCount)"
                else ->
                    "tools: $directCount"
            }
            return McpCatalogInsight(
                directToolCount = directCount,
                isGatewayOrCodeMode = isGateway,
                taskerToolCount = taskerCount,
                resourceCount = metadata?.resourceCount ?: 0,
                promptCount = metadata?.promptCount ?: 0,
                summaryLine = summary
            )
        }

        fun sanitizeToolSegment(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "srv" }.take(20)

        fun buildExposedToolName(serverName: String, toolName: String): String =
            "mcp__${sanitizeToolSegment(serverName)}__${sanitizeToolSegment(toolName)}"

        fun resolveEndpointUrl(baseSseUrl: String, endpointData: String): String {
            val raw = endpointData.trim()
            if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                return raw
            }
            return runCatching { URI(baseSseUrl.trim()).resolve(raw).toString() }
                .getOrElse { "${baseSseUrl.trimEnd('/')}/${raw.trimStart('/')}" }
        }

        fun parseJsonOrSseResponse(raw: String, contentType: String = ""): JsonObject {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                return looseJson.parseToJsonElement(trimmed).jsonObject
            }
            if (contentType.contains("event-stream") || trimmed.contains("data:")) {
                val candidates = mutableListOf<JsonObject>()
                val buf = StringBuilder()
                for (line in trimmed.lines()) {
                    if (line.isBlank()) {
                        val s = buf.toString().trim()
                        buf.clear()
                        if (s.startsWith("{")) {
                            runCatching {
                                candidates += looseJson.parseToJsonElement(s).jsonObject
                            }
                        }
                    } else if (line.startsWith("data:")) {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(line.removePrefix("data:").trimStart())
                    }
                }
                val rem = buf.toString().trim()
                if (rem.startsWith("{")) {
                    runCatching {
                        candidates += looseJson.parseToJsonElement(rem).jsonObject
                    }
                }
                return candidates.lastOrNull { it.containsKey("result") || it.containsKey("error") }
                    ?: candidates.lastOrNull()
                    ?: error("MCP SSE stream contained no JSON-RPC response")
            }
            error("Unexpected MCP response: ${trimmed.take(200)}")
        }
    }
}
