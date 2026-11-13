package com.forgemind.app.bridge

import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.llm.LlmHttpFactory
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

data class CloudflareAccessCredentials(
    val clientId: String,
    val clientSecret: String,
    val sourceServerName: String = ""
) {
    val isComplete: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/**
 * Helpers for connecting JSch SSH sessions through a Cloudflare Access WebSocket tunnel
 * (the in-app equivalent of `cloudflared access ssh --hostname <host>`).
 */
object CloudflareAccessSshSupport {

    const val HEADER_CLIENT_ID = "CF-Access-Client-Id"
    const val HEADER_CLIENT_SECRET = "CF-Access-Client-Secret"

    fun defaultWebSocketClient(): OkHttpClient =
        LlmHttpFactory.baseClientBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Long-lived bidirectional SSH WebSocket stream
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /**
     * Strips scheme (`ssh://`, `https://`, `wss://`, etc.), optional `user@`, path, and `:port`
     * so JSch always receives a clean hostname.
     */
    fun normalizeHost(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val withoutScheme = trimmed
            .replaceFirst(Regex("^(?i)(ssh|https?|wss?)://"), "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val withoutUser = if ('@' in withoutScheme) withoutScheme.substringAfterLast('@') else withoutScheme
        if (withoutUser.startsWith("[")) {
            val endBracket = withoutUser.indexOf(']')
            if (endBracket > 0) return withoutUser.substring(1, endBracket).trim()
        }
        val colonIdx = withoutUser.lastIndexOf(':')
        return if (colonIdx > 0 && withoutUser.indexOf(':') == colonIdx) {
            withoutUser.substring(0, colonIdx).trim()
        } else {
            withoutUser.trim()
        }
    }

    /**
     * Extracts explicit `:port` if supplied inside URL/host, or falls back to `443` for TLS WebSockets
     * (or `80` when `http://`/`ws://` is passed explicitly, e.g. in local tests).
     */
    fun resolvePort(rawHost: String, fallbackPort: Int = 443): Int {
        val trimmed = rawHost.trim()
        if (trimmed.isBlank()) return fallbackPort
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val parsed = runCatching { URI(withScheme) }.getOrNull()
        if (parsed != null && parsed.port in 1..65535) return parsed.port
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("ws://", ignoreCase = true) -> 80
            else -> 443
        }
    }

    /**
     * Builds the `wss://` (or `ws://` for explicit local URLs) endpoint expected by `cloudflared access`.
     */
    fun buildWebSocketUrl(rawHost: String, port: Int = 443): String {
        val trimmed = rawHost.trim()
        if (trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true)
        ) {
            val scheme = if (trimmed.startsWith("ws://", ignoreCase = true)) "ws://" else "wss://"
            val rest = trimmed.substring(scheme.length).trimEnd('/')
            val authority = rest.substringBefore('/')
            return "$scheme$authority/"
        }
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            val rest = trimmed.substring("http://".length).trimEnd('/')
            val authority = rest.substringBefore('/')
            return "ws://$authority/"
        }
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            val rest = trimmed.substring("https://".length).trimEnd('/')
            val authority = rest.substringBefore('/')
            return "wss://$authority/"
        }
        val cleanHost = normalizeHost(trimmed)
        if (cleanHost.isBlank()) return ""
        val hostPart = if (':' in cleanHost) "[$cleanHost]" else cleanHost
        val explicitPort = resolvePort(trimmed, fallbackPort = 443)
        val suffix = if (explicitPort != 443 && trimmed.contains(':')) ":$explicitPort" else ""
        return "wss://$hostPart$suffix/"
    }

    /**
     * Extracts Cloudflare Access Service Token headers (`CF-Access-Client-Id` / `CF-Access-Client-Secret`)
     * from MCP servers already configured in ForgeMind.
     */
    fun extractFromMcpServers(
        servers: List<McpServerEntity>,
        preferredHost: String = ""
    ): CloudflareAccessCredentials? {
        if (servers.isEmpty()) return null
        val targetDomain = rootDomain(normalizeHost(preferredHost))

        data class RankedCandidate(
            val score: Int,
            val creds: CloudflareAccessCredentials
        )

        val candidates = mutableListOf<RankedCandidate>()
        for (srv in servers) {
            val rawHeaders = srv.headersJson.trim()
            if (rawHeaders.isBlank() || rawHeaders == "{}") continue
            val obj = runCatching {
                LlmHttpFactory.json.parseToJsonElement(rawHeaders).jsonObject
            }.getOrNull() ?: continue

            var clientId = ""
            var clientSecret = ""
            for ((k, v) in obj) {
                val normKey = k.trim().lowercase().replace('_', '-')
                val valStr = (runCatching { v.jsonPrimitive.content }.getOrNull() ?: v.toString()).trim()
                if (valStr.isBlank()) continue
                when (normKey) {
                    "cf-access-client-id" -> clientId = valStr
                    "cf-access-client-secret" -> clientSecret = valStr
                }
            }
            if (clientId.isBlank() && clientSecret.isBlank()) continue

            val srvHost = normalizeHost(srv.endpoint)
            val srvDomain = rootDomain(srvHost)
            var score = 0
            if (clientId.isNotBlank() && clientSecret.isNotBlank()) score += 100
            if (srv.enabled) score += 20
            if (targetDomain.isNotBlank() && srvDomain == targetDomain) score += 50
            if (srvHost.equals(normalizeHost(preferredHost), ignoreCase = true) && srvHost.isNotBlank()) score += 10

            candidates += RankedCandidate(
                score = score,
                creds = CloudflareAccessCredentials(
                    clientId = clientId,
                    clientSecret = clientSecret,
                    sourceServerName = srv.name
                )
            )
        }
        return candidates.maxByOrNull { it.score }?.creds
    }

    /**
     * Resolves missing CF Access token fields on [profile] from in-app [mcpServers] when
     * `profile.useCloudflareAccess` is enabled.
     */
    fun resolveEffectiveProfile(
        profile: SshProfileEntity,
        mcpServers: List<McpServerEntity>
    ): SshProfileEntity {
        if (!profile.useCloudflareAccess) return profile
        val hasId = profile.cfAccessClientId.isNotBlank()
        val hasSecret = profile.cfAccessClientSecret.isNotBlank()
        if (hasId && hasSecret) return profile

        val fromMcp = extractFromMcpServers(mcpServers, profile.host) ?: return profile
        return profile.copy(
            cfAccessClientId = profile.cfAccessClientId.ifBlank { fromMcp.clientId },
            cfAccessClientSecret = profile.cfAccessClientSecret.ifBlank { fromMcp.clientSecret }
        )
    }

    private fun rootDomain(host: String): String {
        val parts = host.trim().lowercase().split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host.trim().lowercase()
    }
}

/**
 * JSch [SocketFactory] tunneling SSH-2.0 TCP bytes over an authenticated Cloudflare Access WebSocket.
 */
class CloudflareAccessSshSocketFactory(
    private val wsUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val connectTimeoutMs: Int = 12_000,
    private val httpClient: OkHttpClient = CloudflareAccessSshSupport.defaultWebSocketClient()
) : SocketFactory {

    override fun createSocket(host: String, port: Int): Socket {
        if (wsUrl.isBlank()) {
            throw IOException("Cloudflare Access SSH: host address is missing")
        }
        val socket = CloudflareAccessWebSocketSocket(
            wsUrl = wsUrl,
            logicalHost = host,
            logicalPort = port,
            clientId = clientId,
            clientSecret = clientSecret,
            connectTimeoutMs = connectTimeoutMs,
            httpClient = httpClient
        )
        socket.connectWebSocket()
        return socket
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private class CloudflareAccessWebSocketSocket(
    private val wsUrl: String,
    private val logicalHost: String,
    private val logicalPort: Int,
    private val clientId: String,
    private val clientSecret: String,
    private val connectTimeoutMs: Int,
    private val httpClient: OkHttpClient
) : Socket() {

    private companion object {
        val EOF_MARKER = ByteArray(0)
        const val MAX_OUTBOUND_CHUNK = 32 * 1024
    }

    private val incomingQueue = LinkedBlockingQueue<ByteArray>()
    private val failureRef = AtomicReference<IOException?>(null)
    private val closed = AtomicBoolean(false)
    private val soTimeoutMs = AtomicInteger(0)
    private val openFuture = CompletableFuture<WebSocket>()

    @Volatile
    private var webSocket: WebSocket? = null

    fun connectWebSocket() {
        val reqBuilder = Request.Builder().url(wsUrl)
        if (clientId.isNotBlank()) {
            reqBuilder.header(CloudflareAccessSshSupport.HEADER_CLIENT_ID, clientId)
        }
        if (clientSecret.isNotBlank()) {
            reqBuilder.header(CloudflareAccessSshSupport.HEADER_CLIENT_SECRET, clientSecret)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@CloudflareAccessWebSocketSocket.webSocket = webSocket
                openFuture.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.isNotEmpty()) incomingQueue.offer(arr)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val arr = text.toByteArray(Charsets.UTF_8)
                if (arr.isNotEmpty()) incomingQueue.offer(arr)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                incomingQueue.offer(EOF_MARKER)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incomingQueue.offer(EOF_MARKER)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val ioErr = buildFailureException(t, response)
                if (!closed.get()) {
                    failureRef.compareAndSet(null, ioErr)
                }
                if (!openFuture.isDone) {
                    openFuture.completeExceptionally(ioErr)
                }
                incomingQueue.offer(EOF_MARKER)
            }
        }

        val effectiveClient = if (httpClient.followRedirects || httpClient.followSslRedirects) {
            httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        } else {
            httpClient
        }
        val ws = effectiveClient.newWebSocket(reqBuilder.build(), listener)
        webSocket = ws
        try {
            openFuture.get(connectTimeoutMs.coerceAtLeast(1_000).toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            close()
            throw SocketTimeoutException("WebSocket SSH timeout to $wsUrl (${connectTimeoutMs}ms)").apply {
                initCause(e)
            }
        } catch (e: ExecutionException) {
            close()
            val cause = e.cause
            if (cause is IOException) throw cause
            throw IOException(cause?.message ?: "WebSocket SSH connection failed to $wsUrl", cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw InterruptedIOException("WebSocket SSH connection interrupted").apply { initCause(e) }
        }
    }

    private fun buildFailureException(t: Throwable, response: Response?): IOException {
        if (response != null) {
            val code = response.code
            val location = response.header("Location").orEmpty()
            if (code == 302 || code == 401 || code == 403 || location.contains("cloudflareaccess.com", ignoreCase = true)) {
                return IOException(
                    "Cloudflare Access rejected connection to $wsUrl (HTTP $code). " +
                        "Check CF-Access-Client-Id and CF-Access-Client-Secret (or MCP settings in the app).",
                    t
                )
            }
            return IOException("WebSocket SSH error ($wsUrl, HTTP $code): ${t.message ?: response.message}", t)
        }
        return IOException("WebSocket SSH error ($wsUrl): ${t.message ?: t::class.java.simpleName}", t)
    }

    private val inputStreamImpl = object : InputStream() {
        private var currentChunk: ByteArray? = null
        private var currentPos: Int = 0
        private var eofReached: Boolean = false
        private val singleByte = ByteArray(1)

        @Synchronized
        override fun read(): Int {
            val n = read(singleByte, 0, 1)
            return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        @Synchronized
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (eofReached) {
                failureRef.get()?.let { if (!closed.get()) throw it }
                return -1
            }

            while (currentChunk == null || currentPos >= currentChunk!!.size) {
                val next: ByteArray = try {
                    val timeout = soTimeoutMs.get()
                    if (timeout > 0) {
                        incomingQueue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
                            ?: throw SocketTimeoutException("WebSocket SSH read timeout")
                    } else {
                        incomingQueue.take()
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("WebSocket SSH read interrupted").apply { initCause(e) }
                }

                if (next === EOF_MARKER) {
                    eofReached = true
                    incomingQueue.offer(EOF_MARKER)
                    failureRef.get()?.let { if (!closed.get()) throw it }
                    return -1
                }
                if (next.isNotEmpty()) {
                    currentChunk = next
                    currentPos = 0
                    break
                }
            }

            val chunk = currentChunk!!
            val avail = chunk.size - currentPos
            val copyLen = minOf(len, avail)
            System.arraycopy(chunk, currentPos, b, off, copyLen)
            currentPos += copyLen
            if (currentPos >= chunk.size) {
                currentChunk = null
            }
            return copyLen
        }

        @Synchronized
        override fun available(): Int {
            val curAvail = currentChunk?.let { it.size - currentPos } ?: 0
            if (curAvail > 0) return curAvail
            val head = incomingQueue.peek() ?: return 0
            return if (head === EOF_MARKER) 0 else head.size
        }

        override fun close() {
            this@CloudflareAccessWebSocketSocket.close()
        }
    }

    private val outputStreamImpl = object : OutputStream() {
        private val singleByte = ByteArray(1)

        override fun write(b: Int) {
            singleByte[0] = b.toByte()
            write(singleByte, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            if (len == 0) return
            if (closed.get()) throw IOException("WebSocket SSH socket is closed")
            val ws = webSocket ?: throw IOException("WebSocket SSH is not open yet")

            var cursor = off
            var remaining = len
            while (remaining > 0) {
                val chunkSize = minOf(remaining, MAX_OUTBOUND_CHUNK)
                val sent = ws.send(b.toByteString(cursor, chunkSize))
                if (!sent) {
                    throw failureRef.get() ?: IOException("Failed to send SSH data over WebSocket ($wsUrl)")
                }
                cursor += chunkSize
                remaining -= chunkSize
            }
        }

        override fun close() {
            this@CloudflareAccessWebSocketSocket.close()
        }
    }

    override fun getInputStream(): InputStream = inputStreamImpl
    override fun getOutputStream(): OutputStream = outputStreamImpl

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!openFuture.isDone || openFuture.isCompletedExceptionally) {
            runCatching { webSocket?.cancel() }
        } else {
            runCatching { webSocket?.close(1000, "SSH closed") }
        }
        incomingQueue.offer(EOF_MARKER)
    }

    override fun shutdownInput() {
        incomingQueue.offer(EOF_MARKER)
    }

    override fun shutdownOutput() {
        // SSH channel close triggers normal WebSocket close
    }

    override fun isClosed(): Boolean = closed.get()
    override fun isConnected(): Boolean = !closed.get() && openFuture.isDone && !openFuture.isCompletedExceptionally

    override fun setSoTimeout(timeout: Int) {
        soTimeoutMs.set(timeout.coerceAtLeast(0))
    }

    override fun getSoTimeout(): Int = soTimeoutMs.get()
    override fun setTcpNoDelay(on: Boolean) {}
    override fun getTcpNoDelay(): Boolean = true
    override fun getPort(): Int = logicalPort
    override fun getLocalPort(): Int = 0
    override fun getInetAddress(): InetAddress? = null
    override fun getRemoteSocketAddress(): SocketAddress =
        InetSocketAddress.createUnresolved(logicalHost, logicalPort.coerceIn(0, 65535))
}
