package com.forgemind.app.setup

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairingRelayTest {
    @Test fun forwardsBothDirectionsAndCloseUnblocksClient() {
        ServerSocket(0).use { target ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val server = executor.submit<Socket> { target.accept() }
                PairingRelay(target.localPort).use { relay ->
                    Socket("127.0.0.1", relay.port).use { client ->
                        client.soTimeout = 2000
                        server.get(2, TimeUnit.SECONDS).use { remote ->
                            remote.soTimeout = 2000
                            client.getOutputStream().write(65)
                            assertEquals(65, remote.getInputStream().read())
                            remote.getOutputStream().write(66)
                            assertEquals(66, client.getInputStream().read())
                            relay.close()
                            assertEquals(-1, client.getInputStream().read())
                            assertEquals(-1, remote.getInputStream().read())
                        }
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }
}
