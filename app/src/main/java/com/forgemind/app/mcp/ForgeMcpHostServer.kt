package com.forgemind.app.mcp

import com.forgemind.app.accessibility.ForgeAccessibilityService
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.McpHostRuntimeState
import com.forgemind.app.data.model.McpHostSettings
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in Streamable HTTP MCP Server (`127.0.0.1:<port>/mcp`) running natively inside ForgeMind.
 * Exposes ForgeMind's real-time Accessibility Window-Tag DOM (`screen_read_windows`, `screen_act_tag`,
 * `screen_act_batch`, `screen_capture`), Embedded Shell, Workspace files, and Agent Browser
 * to local Termux servers, the Embedded Physical Shell (`fm-mcp`), and PC IDEs over `adb forward`.
 */
class ForgeMcpHostServer(
    private val toolRegistryProvider: () -> AgentToolRegistry,
    private val searchSettingsProvider: () -> SearchSettings = { SearchSettings() },
    private val defaultEnvProvider: () -> WorkspaceEnvType = { WorkspaceEnvType.INTERNAL },
    private val defaultProjectProvider: () -> String = { "default" }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var activeSettings: McpHostSettings = McpHostSettings()

    private val requestCounter = AtomicLong(0L)
    private val sessionId = "fm-host-${UUID.randomUUID().toString().take(12)}"

    private val _runtimeState = MutableStateFlow(McpHostRuntimeState())
    val runtimeState: StateFlow<McpHostRuntimeState> = _runtimeState.asStateFlow()

    val boundPort: Int
        get() = _runtimeState.value.boundPort

    fun applySettings(settings: McpHostSettings) {
        synchronized(lock) {
            activeSettings = settings
            if (!settings.enabled) {
                stopLocked()
                return
            }
            val currentSocket = serverSocket
            val desiredPort = settings.port
            if (currentSocket != null && !currentSocket.isClosed &&
                (desiredPort == 0 || currentSocket.localPort == desiredPort)
            ) {
                _runtimeState.value = _runtimeState.value.copy(
                    isRunning = true,
                    boundPort = currentSocket.localPort,
                    lastError = null
                )
                return
            }
            stopLocked()
            startLocked(desiredPort)
        }
    }

    fun start(port: Int = 8765, requireAuth: Boolean = false, authToken: String = "", exposeShellAndWrite: Boolean = true): Result<Int> {
        synchronized(lock) {
            activeSettings = McpHostSettings(
                enabled = true,
                port = port,
                requireAuth = requireAuth,
                authToken = authToken,
                exposeShellAndWriteTools = exposeShellAndWrite
            )
            stopLocked()
            return startLocked(port)
        }
    }

    fun stop() {
        synchronized(lock) {
            activeSettings = activeSettings.copy(enabled = false)
            stopLocked()
        }
    }

    private fun startLocked(port: Int): Result<Int> = runCatching {
        val ss = ServerSocket()
        ss.reuseAddress = true
        val targetPort = if (port == 0) 0 else port.coerceIn(1024, 65535)
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 32)
        val actualPort = ss.localPort
        serverSocket = ss
        _runtimeState.value = McpHostRuntimeState(
            isRunning = true,
            boundPort = actualPort,
            requestsHandled = requestCounter.get(),
            lastToolCalled = _runtimeState.value.lastToolCalled,
            lastError = null
        )
        acceptJob = scope.launch {
            while (isActive && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                launch {
                    runCatching { handleClient(client) }
                        .onFailure { runCatching { client.close() } }
                }
            }
        }
        actualPort
    }.onFailure { err ->
        _runtimeState.value = McpHostRuntimeState(
            isRunning = false,
            boundPort = 0,
            requestsHandled = requestCounter.get(),
            lastError = err.message ?: err::class.java.simpleName
        )
    }

    private fun stopLocked() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _runtimeState.value = _runtimeState.value.copy(
            isRunning = false,
            boundPort = 0
        )
    }

    private suspend fun handleClient(socket: Socket) {
        socket.soTimeout = 60_000
        socket.tcpNoDelay = true
        socket.use { sock ->
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val req = readHttpRequest(input) ?: return
            val path = req.target.substringBefore('?').ifBlank { "/" }
            val method = req.method.uppercase()

            when {
                method == "OPTIONS" -> {
                    writeHttpResponse(
                        out = out,
                        statusCode = 204,
                        statusText = "No Content",
                        contentType = "text/plain; charset=utf-8",
                        body = ByteArray(0),
                        extraHeaders = mapOf("Allow" to "GET, POST, DELETE, OPTIONS")
                    )
                }

                method == "GET" && path == "/health" -> {
                    val cfg = activeSettings
                    val specsCount = runCatching {
                        toolRegistryProvider().buildHostServerToolSpecs(cfg.exposeShellAndWriteTools).size
                    }.getOrDefault(0)
                    val healthJson = buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("server", JsonPrimitive(SERVER_NAME))
                        put("version", JsonPrimitive(SERVER_VERSION))
                        put("port", JsonPrimitive(_runtimeState.value.boundPort))
                        put("requireAuth", JsonPrimitive(cfg.requireAuth && cfg.authToken.isNotBlank()))
                        put("exposeShellAndWriteTools", JsonPrimitive(cfg.exposeShellAndWriteTools))
                        put("a11yConnected", JsonPrimitive(ForgeAccessibilityService.instance != null))
                        put("toolsCount", JsonPrimitive(specsCount))
                        put("requestsHandled", JsonPrimitive(requestCounter.get()))
                    }.toString()
                    writeJsonResponse(out, 200, "OK", healthJson)
                }

                method == "GET" && (path == "/mcp" || path == "/") -> {
                    // Per MCP Streamable HTTP 2025-03-26 §2.2: 405 tells clients to use stateless/session POST
                    writeHttpResponse(
                        out = out,
                        statusCode = 405,
                        statusText = "Method Not Allowed",
                        contentType = "application/json; charset=utf-8",
                        body = """{"error":"Use HTTP POST /mcp for JSON-RPC 2.0 Streamable HTTP requests"}""".toByteArray(Charsets.UTF_8),
                        extraHeaders = mapOf("Allow" to "POST, DELETE, OPTIONS")
                    )
                }

                method == "DELETE" && (path == "/mcp" || path == "/") -> {
                    writeHttpResponse(
                        out = out,
                        statusCode = 204,
                        statusText = "No Content",
                        contentType = "text/plain; charset=utf-8",
                        body = ByteArray(0)
                    )
                }

                method == "POST" && (path == "/mcp" || path == "/") -> {
                    val cfg = activeSettings
                    if (cfg.requireAuth && cfg.authToken.isNotBlank()) {
                        val authHdr = req.headers["authorization"].orEmpty().trim()
                        val provided = if (authHdr.startsWith("Bearer ", ignoreCase = true)) {
                            authHdr.substring(7).trim()
                        } else ""
                        val ok = provided.isNotEmpty() && MessageDigest.isEqual(
                            provided.toByteArray(Charsets.UTF_8),
                            cfg.authToken.toByteArray(Charsets.UTF_8)
                        )
                        if (!ok) {
                            val unauthorized = rpcError(
                                id = JsonNull,
                                code = -32001,
                                message = "Unauthorized: valid Authorization: Bearer <token> header required"
                            ).toString()
                            writeHttpResponse(
                                out = out,
                                statusCode = 401,
                                statusText = "Unauthorized",
                                contentType = "application/json; charset=utf-8",
                                body = unauthorized.toByteArray(Charsets.UTF_8),
                                extraHeaders = mapOf("WWW-Authenticate" to "Bearer realm=\"ForgeMind-MCP\"")
                            )
                            return
                        }
                    }

                    val bodyStr = req.body.toString(Charsets.UTF_8).trim()
                    if (bodyStr.isEmpty()) {
                        val err = rpcError(JsonNull, -32700, "Parse error: empty request body").toString()
                        writeJsonResponse(out, 400, "Bad Request", err)
                        return
                    }

                    val parsed = runCatching { json.parseToJsonElement(bodyStr) }.getOrElse { e ->
                        val err = rpcError(JsonNull, -32700, "Parse error: ${e.message}").toString()
                        writeJsonResponse(out, 400, "Bad Request", err)
                        return
                    }

                    val count = requestCounter.incrementAndGet()
                    _runtimeState.value = _runtimeState.value.copy(requestsHandled = count)

                    when (parsed) {
                        is JsonObject -> {
                            val reply = handleSingleRpc(parsed, cfg)
                            if (reply == null) {
                                writeHttpResponse(
                                    out = out,
                                    statusCode = 202,
                                    statusText = "Accepted",
                                    contentType = "text/plain; charset=utf-8",
                                    body = ByteArray(0),
                                    extraHeaders = mapOf("Mcp-Session-Id" to sessionId)
                                )
                            } else {
                                writeJsonResponse(
                                    out = out,
                                    statusCode = 200,
                                    statusText = "OK",
                                    jsonBody = reply.toString(),
                                    extraHeaders = mapOf("Mcp-Session-Id" to sessionId)
                                )
                            }
                        }

                        is JsonArray -> {
                            val replies = mutableListOf<JsonObject>()
                            for (item in parsed) {
                                val obj = item as? JsonObject
                                if (obj == null) {
                                    replies += rpcError(JsonNull, -32600, "Invalid Request in batch")
                                } else {
                                    handleSingleRpc(obj, cfg)?.let { replies += it }
                                }
                            }
                            if (replies.isEmpty()) {
                                writeHttpResponse(
                                    out = out,
                                    statusCode = 202,
                                    statusText = "Accepted",
                                    contentType = "text/plain; charset=utf-8",
                                    body = ByteArray(0),
                                    extraHeaders = mapOf("Mcp-Session-Id" to sessionId)
                                )
                            } else {
                                writeJsonResponse(
                                    out = out,
                                    statusCode = 200,
                                    statusText = "OK",
                                    jsonBody = JsonArray(replies).toString(),
                                    extraHeaders = mapOf("Mcp-Session-Id" to sessionId)
                                )
                            }
                        }

                        else -> {
                            val err = rpcError(JsonNull, -32600, "Invalid Request: expected JSON object or array").toString()
                            writeJsonResponse(out, 400, "Bad Request", err)
                        }
                    }
                }

                else -> {
                    writeJsonResponse(
                        out = out,
                        statusCode = 404,
                        statusText = "Not Found",
                        jsonBody = """{"error":"Not Found: use POST /mcp or GET /health"}"""
                    )
                }
            }
        }
    }

    private suspend fun handleSingleRpc(req: JsonObject, cfg: McpHostSettings): JsonObject? {
        val hasId = req.containsKey("id")
        val idElement: JsonElement = req["id"] ?: JsonNull
        val method = (req["method"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (method.isEmpty()) {
            return if (hasId) rpcError(idElement, -32600, "Invalid Request: missing 'method'") else null
        }

        // JSON-RPC notifications (e.g. notifications/initialized) do not carry an id and expect no JSON-RPC reply
        if (!hasId || method.startsWith("notifications/")) {
            return null
        }

        val params = (req["params"] as? JsonObject) ?: buildJsonObject {}

        return when (method) {
            "initialize" -> {
                val clientVer = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val negotiatedVer = if (clientVer in SUPPORTED_PROTOCOL_VERSIONS) clientVer else DEFAULT_PROTOCOL_VERSION
                rpcResult(
                    id = idElement,
                    result = buildJsonObject {
                        put("protocolVersion", JsonPrimitive(negotiatedVer))
                        put("capabilities", buildJsonObject {
                            put("tools", buildJsonObject {
                                put("listChanged", JsonPrimitive(false))
                            })
                        })
                        put("serverInfo", buildJsonObject {
                            put("name", JsonPrimitive(SERVER_NAME))
                            put("version", JsonPrimitive(SERVER_VERSION))
                        })
                        put("instructions", JsonPrimitive(buildHostInstructions(cfg)))
                    }
                )
            }

            "ping" -> rpcResult(idElement, buildJsonObject {})

            "resources/list" -> rpcResult(
                idElement,
                buildJsonObject { put("resources", buildJsonArray {}) }
            )

            "prompts/list" -> rpcResult(
                idElement,
                buildJsonObject { put("prompts", buildJsonArray {}) }
            )

            "tools/list" -> {
                val specs = toolRegistryProvider().buildHostServerToolSpecs(cfg.exposeShellAndWriteTools)
                val toolsJson = buildJsonArray {
                    for (spec in specs) {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(spec.name))
                                put("description", JsonPrimitive(spec.description))
                                put("inputSchema", spec.parametersSchema)
                            }
                        )
                    }
                }
                rpcResult(
                    id = idElement,
                    result = buildJsonObject {
                        put("tools", toolsJson)
                    }
                )
            }

            "tools/call" -> {
                val toolName = (params["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (toolName.isEmpty()) {
                    return rpcError(idElement, -32602, "Invalid params: missing tool 'name'")
                }
                val argsJson = when (val rawArgs = params["arguments"]) {
                    is JsonObject -> rawArgs.toString()
                    is JsonPrimitive -> rawArgs.contentOrNull?.ifBlank { "{}" } ?: "{}"
                    else -> "{}"
                }

                val disallowedWriteOrJs = !cfg.exposeShellAndWriteTools && (
                    toolName in AgentToolRegistry.SHELL_AND_WRITE_HOST_TOOLS ||
                        (toolName == "browser_agent_batch" && AgentToolRegistry.hasBrowserEvalJsStep(argsJson)) ||
                        ((toolName == "web_scrape_site" || toolName == "browser_agent_markdown") && AgentToolRegistry.hasNonBlankSavePath(argsJson))
                    )
                if (disallowedWriteOrJs) {
                    return rpcError(
                        idElement,
                        -32603,
                        "Tool '$toolName' (or its write/JS arguments) is disabled in ForgeMind MCP Host settings (enable 'Expose Shell & File Write tools' to allow)."
                    )
                }

                val registry = toolRegistryProvider()
                if (registry.isDangerousCommand(toolName, argsJson)) {
                    return rpcResult(
                        id = idElement,
                        result = buildJsonObject {
                            put("isError", JsonPrimitive(true))
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive("Blocked potentially destructive shell command via MCP Host ($toolName)."))
                                })
                            })
                        }
                    )
                }

                _runtimeState.value = _runtimeState.value.copy(lastToolCalled = toolName)

                val execSettings = AgentSettings(
                    enableTools = true,
                    autoApproveAll = cfg.exposeShellAndWriteTools,
                    autoApproveSafe = true,
                    autoApproveRead = true,
                    autoApproveFileWrite = cfg.exposeShellAndWriteTools,
                    autoApproveShell = cfg.exposeShellAndWriteTools,
                    autoApproveBrowser = true,
                    autoApproveBrowserJs = cfg.exposeShellAndWriteTools,
                    autoApproveMcp = cfg.exposeShellAndWriteTools,
                    autoApproveChatManage = true
                )

                val output = registry.execute(
                    toolName = toolName,
                    argsJson = argsJson,
                    defaultEnv = defaultEnvProvider(),
                    internalProject = defaultProjectProvider(),
                    sessionId = "mcp_host_session",
                    messageId = "mcp_call_${requestCounter.get()}",
                    agentSettings = execSettings,
                    searchSettings = searchSettingsProvider()
                )

                val contentBlocks = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(output.text))
                        }
                    )
                    for (att in output.outgoingAttachments) {
                        if (att.kind == AttachmentKind.IMAGE) {
                            val imgFile = File(att.localPath)
                            if (imgFile.exists() && imgFile.length() in 1..(16 * 1024 * 1024)) {
                                runCatching {
                                    val b64 = Base64.getEncoder().encodeToString(imgFile.readBytes())
                                    add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("image"))
                                            put("data", JsonPrimitive(b64))
                                            put("mimeType", JsonPrimitive(att.mimeType.ifBlank { "image/webp" }))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                rpcResult(
                    id = idElement,
                    result = buildJsonObject {
                        put("content", contentBlocks)
                        put("isError", JsonPrimitive(output.isError))
                    }
                )
            }

            else -> rpcError(idElement, -32601, "Method not found: $method")
        }
    }

    private fun buildHostInstructions(cfg: McpHostSettings): String = buildString {
        val a11yUp = ForgeAccessibilityService.instance != null
        appendLine("ForgeMind Native Android MCP Host (127.0.0.1:${_runtimeState.value.boundPort}).")
        appendLine("- Accessibility Window-Tag DOM: ${if (a11yUp) "ACTIVE" else "INACTIVE (enable ForgeMind in Android Accessibility Settings)"}.")
        appendLine("- Fast real-time UI control: call `screen_read_windows` to inspect [W1..Wn] windows (foreground APP windows prioritized first; floating chat automatically yields focus) and [#1..#k] tags in <15ms, then call `screen_act_batch` or `screen_act_tag` to execute clicks, Unicode/Cyrillic `set_text`, swipes, `back` (with native + system nav + edge-gesture fallback), `wait_text`, and `open_app`.")
        appendLine("- Multi-Tab Parallel Background Browser & Shared ⚡ Turbo Mode: call `browser_agent_tabs(action='open_parallel', urls=[...])` to open up to 8 sites simultaneously in background WebViews, `inspect_all` / `markdown_all` to read them all, and pass `tab_id` to any `browser_agent_*` tool (or per-step `tab_id` in `browser_agent_batch`) to interact across multiple sites freely. Toggle shared `⚡ Turbo` via `browser_agent_mode(action='turbo_on'|'turbo_off')` (blocks ad/tracker/font/heavy-media payloads, 0s CSS animations, and early DOM-ready return on ad-heavy pages; lights up a visible `⚡ Turbo` badge for the user; does not bypass raw server latency or JS Markdown traversal).")
        appendLine("- Site-to-Markdown Crawler: call `web_scrape_site` to crawl an entire site into GFM Markdown (`INDEX.md` + `FULL_SITE.md`), and `browser_agent_markdown` to dump rendered SPA pages into Markdown.")
        append("- On-demand hardware screen frame: call `screen_capture(format='webp', max_edge=1280)` only when visual pixels are needed. Shell & file write tools exposed: ${cfg.exposeShellAndWriteTools}.")
    }

    private fun rpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("error", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        })
    }

    private data class RawHttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun readHttpRequest(input: BufferedInputStream): RawHttpRequest? {
        val reqLine = readAsciiLine(input, maxBytes = 8192) ?: return null
        if (reqLine.isEmpty()) return null
        val parts = reqLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].trim()
        val target = parts[1].trim()

        val headers = LinkedHashMap<String, String>()
        var headerCount = 0
        while (headerCount < 128) {
            val line = readAsciiLine(input, maxBytes = 16_384) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                headers[k] = v
            }
            headerCount++
        }

        val transferEnc = headers["transfer-encoding"].orEmpty().lowercase()
        val bodyBytes = when {
            transferEnc.contains("chunked") -> readChunkedBody(input)
            else -> {
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                if (len == 0) ByteArray(0) else readExactBytes(input, len)
            }
        }
        return RawHttpRequest(method, target, headers, bodyBytes)
    }

    private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
        val buf = ByteArrayOutputStream(128)
        var gotAny = false
        while (buf.size() < maxBytes) {
            val b = input.read()
            if (b == -1) {
                return if (gotAny) buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r') else null
            }
            gotAny = true
            if (b == '\n'.code) {
                break
            }
            buf.write(b)
        }
        return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY_BYTES) {
            val sizeLine = readAsciiLine(input, 256)?.substringBefore(';')?.trim() ?: break
            val chunkSize = sizeLine.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                // Consume trailing CRLF / footers
                while (true) {
                    val tr = readAsciiLine(input, 1024) ?: break
                    if (tr.isEmpty()) break
                }
                break
            }
            val safeChunk = chunkSize.coerceAtMost(MAX_BODY_BYTES - out.size())
            val chunkBytes = readExactBytes(input, safeChunk)
            out.write(chunkBytes)
            // Consume CRLF after chunk data
            readAsciiLine(input, 16)
        }
        return out.toByteArray()
    }

    private fun writeJsonResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        jsonBody: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeHttpResponse(
            out = out,
            statusCode = statusCode,
            statusText = statusText,
            contentType = "application/json; charset=utf-8",
            body = jsonBody.toByteArray(Charsets.UTF_8),
            extraHeaders = extraHeaders
        )
    }

    private fun writeHttpResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val hdr = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: http://127.0.0.1\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id\r\n")
            append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
            extraHeaders.forEach { (k, v) ->
                append("$k: $v\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    companion object {
        const val SERVER_NAME = "ForgeMind-Android-MCP"
        const val SERVER_VERSION = "1.0.0"
        const val DEFAULT_PROTOCOL_VERSION = "2025-03-26"
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf("2025-03-26", "2024-11-05")

        fun buildClientJsonConfig(port: Int, requireAuth: Boolean, token: String): String {
            val url = "http://127.0.0.1:$port/mcp"
            return buildJsonObject {
                put("mcpServers", buildJsonObject {
                    put("forgemind-phone", buildJsonObject {
                        put("url", JsonPrimitive(url))
                        if (requireAuth && token.isNotBlank()) {
                            put("headers", buildJsonObject {
                                put("Authorization", JsonPrimitive("Bearer $token"))
                            })
                        }
                    })
                })
            }.toString()
        }
    }
}
