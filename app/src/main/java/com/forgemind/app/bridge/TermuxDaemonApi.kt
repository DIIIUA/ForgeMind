package com.forgemind.app.bridge

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.forgemind.termuxbridge.api.ITermuxBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Local authenticated transport implementing the existing terminal/file contract. */
class TermuxDaemonApi(private val config: Config, private val readTimeoutMs: Int = 15000) : ITermuxBridge.Stub() {
    data class Config(val port: Int, val token: String)
    companion object {
        fun configFile(context: Context) = File(context.noBackupFilesDir, "termux-helper.json")
        fun configured(context: Context) = configFile(context).isFile
        @Synchronized fun config(context: Context): Config {
            val file = configFile(context)
            if (!file.exists()) {
                val random = SecureRandom()
                val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                val json = JSONObject().put("port", 40000 + random.nextInt(20000)).put("token", token)
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json.toString()); check(temp.renameTo(file))
            }
            val json = JSONObject(file.readText())
            return Config(json.getInt("port"), json.getString("token")).also {
                require(it.port in 1024..65535 && it.token.matches(Regex("[a-f0-9]{64}")))
            }
        }
        private fun json(bundle: Bundle): JSONObject = JSONObject().apply {
            bundle.keySet().forEach { key ->
                @Suppress("DEPRECATION") val value = bundle.get(key)
                put(key, when (value) { is Bundle -> json(value); is Array<*> -> JSONArray(value.toList()); else -> value })
            }
        }
        private fun bundle(json: JSONObject): Bundle = Bundle().apply {
            json.keys().forEach { key -> when (val value = json.get(key)) {
                is JSONObject -> putBundle(key, bundle(value))
                is JSONArray -> putParcelableArrayList(key, ArrayList((0 until value.length()).map { bundle(value.getJSONObject(it)) }))
                is Boolean -> putBoolean(key, value)
                is Number -> if (key in setOf("size", "modified", "created")) putLong(key, value.toLong()) else putInt(key, value.toInt())
                is String -> putString(key, value)
            } }
        }
    }
    private fun socket(method: String, data: JSONObject): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", config.port), 2000)
            socket.soTimeout = readTimeoutMs; socket.tcpNoDelay = true
            val request = data.put("token", config.token).put("method", method).toString() + "\n"
            socket.getOutputStream().write(request.toByteArray())
            return socket
        } catch (failure: Exception) { socket.close(); throw failure }
    }
    private fun response(socket: Socket): JSONObject {
        val input = socket.getInputStream()
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            check(next >= 0) { "Helper closed before replying; inspect operation before retrying" }
            if (next == 10) break
            check(output.size() < 1_500_000) { "Helper response exceeds limit" }
            output.write(next)
        }
        val reply = JSONObject(output.toString("UTF-8"))
        check(reply.optBoolean("ok")) { reply.optString("error", "Helper rejected request") }
        return reply.getJSONObject("result")
    }
    private fun call(method: String, request: Bundle = Bundle()): Bundle = socket(method, json(request)).use { bundle(response(it)) }
    override fun info() = call("info")
    override fun tasks(request: Bundle) = call("tasks", request)
    override fun sessions(request: Bundle) = call("sessions", request)
    override fun files(request: Bundle) = call("files", request)
    override fun exec(request: Bundle): Bundle = throw UnsupportedOperationException("Use tracked tasks")
    override fun startService(request: Bundle): Bundle = throw UnsupportedOperationException("Start services through Termux RUN_COMMAND")
    override fun ensureServices(request: Bundle): Bundle = throw UnsupportedOperationException("Configure each service explicitly")
    override fun openFile(path: String, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Use acknowledged writeFile for writes" }
        val socket = socket("fileRead", JSONObject().put("path", path))
        try {
            response(socket); socket.soTimeout = 60000
            return ParcelFileDescriptor.fromSocket(socket)
        } finally { socket.close() }
    }
    fun writeFile(path: String, bytes: ByteArray) {
        var offset = 0
        do {
            val end = minOf(bytes.size, offset + 64 * 1024)
            val data = Base64.encodeToString(bytes.copyOfRange(offset, end), Base64.NO_WRAP)
            val result = files(Bundle().apply {
                putString("action", "write"); putString("path", path); putString("data", data); putInt("offset", offset)
            })
            check(result.getInt("written") == end - offset) { "File write not confirmed" }
            offset = end
        } while (offset < bytes.size)
    }
    override fun openPty(request: Bundle): Bundle {
        val name = request.getStringArray("argv")!!.last().removePrefix("=")
        val result = call("openPty", Bundle().apply { putString("name", name); putBundle("env", request.getBundle("env")) })
        val id = result.getString("id")!!
        val socket = socket("ptyStream", JSONObject().put("id", id))
        try {
            response(socket); socket.soTimeout = 0
            result.putParcelable("descriptor", ParcelFileDescriptor.fromSocket(socket))
            return result
        } catch (failure: Exception) { runCatching { closePty(id) }; throw failure }
        finally { socket.close() }
    }
    override fun resizePty(id: String, rows: Int, columns: Int) { call("resizePty", Bundle().apply { putString("id",id); putInt("rows",rows); putInt("columns",columns) }) }
    override fun signalPty(id: String, signal: Int) { require(signal in setOf(1,9,15)); closePty(id) }
    override fun waitForPty(id: String, timeoutMs: Long) = call("waitForPty", Bundle().apply { putString("id",id); putLong("timeoutMs",timeoutMs.coerceIn(0,1000)) })
    override fun closePty(id: String) { call("closePty", Bundle().apply { putString("id",id) }) }
}
