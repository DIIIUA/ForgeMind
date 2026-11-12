package com.forgemind.app.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LocalPreviewServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var rootDir: File? = null

    var port: Int = 0
        private set

    fun start(directory: File): Int {
        rootDir = directory
        if (serverSocket?.isClosed == false && port > 0) {
            return port
        }
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        acceptJob = scope.launch {
            while (isActive && !ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                launch { handleClient(sock) }
            }
        }
        return port
    }

    fun updateDirectory(directory: File) {
        rootDir = directory
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val reqLine = reader.readLine() ?: return
            val rawPath = reqLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val relPath = rawPath.trimStart('/').ifBlank { "index.html" }

            val base = rootDir
            val out = s.getOutputStream()

            if (base == null || !base.exists()) {
                sendHtml(out, 404, missingHtml("Рабочая папка не выбрана"))
                return
            }

            val candidate = File(base, relPath)
            val resolved = if (candidate.isDirectory) File(candidate, "index.html") else candidate
            if (!resolved.canonicalPath.startsWith(base.canonicalPath)) {
                sendText(out, 403, "403 Forbidden")
                return
            }
            if (!resolved.exists() || !resolved.isFile) {
                sendHtml(out, 404, missingHtml("Файл <code>$relPath</code> не найден. Запустите dev-сервер в Терминале или создайте <code>index.html</code>."))
                return
            }

            val bytes = resolved.readBytes()
            val mime = guessMime(resolved.name)
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun sendText(out: java.io.OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Text\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtml(out: java.io.OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code HTML\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun missingHtml(msg: String) = """
        <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{background:#0E1117;color:#E6EDF3;font-family:system-ui;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;padding:24px;text-align:center}code{background:#1F2937;padding:2px 6px;border-radius:4px}</style>
        </head><body><h2>Web Preview</h2><p>$msg</p></body></html>
    """.trimIndent()

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
