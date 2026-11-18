package com.forgemind.app.setup

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds the pairing library's blocking TLS sockets without inspecting encrypted traffic. */
internal class PairingRelay(targetPort: Int) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
    val port: Int = listener.localPort
    init {
        require(targetPort in 1..65535)
        Thread({
            try {
                val client = track(listener.accept())
                listener.close()
                val server = track(Socket())
                server.connect(InetSocketAddress("127.0.0.1", targetPort), 2_000)
                Thread({ pump(client, server) }, "ForgeMind-pair-out").apply { isDaemon = true; start() }
                pump(server, client)
            } catch (_: Exception) { close() }
        }, "ForgeMind-pair-in").apply { isDaemon = true; start() }
    }
    private fun track(socket: Socket): Socket {
        sockets.add(socket)
        if (closed.get()) { socket.close(); error("Pairing cancelled") }
        socket.soTimeout = 12_000
        socket.tcpNoDelay = true
        return socket
    }
    private fun pump(input: Socket, output: Socket) {
        try { input.getInputStream().copyTo(output.getOutputStream(), 16 * 1024) }
        catch (_: Exception) { /* The pairing client reports a transport failure. */ }
        finally { close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
