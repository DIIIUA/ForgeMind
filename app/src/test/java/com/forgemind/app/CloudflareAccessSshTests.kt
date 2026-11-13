package com.forgemind.app

import com.forgemind.app.bridge.CloudflareAccessSshSocketFactory
import com.forgemind.app.bridge.CloudflareAccessSshSupport
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudflareAccessSshTests {

    @Test
    fun hostNormalizationAndWebSocketUrlBuilding() {
        assertEquals("ssh.example.com", CloudflareAccessSshSupport.normalizeHost("ssh.example.com"))
        assertEquals("ssh.example.com", CloudflareAccessSshSupport.normalizeHost("https://ssh.example.com/"))
        assertEquals("ssh.example.com", CloudflareAccessSshSupport.normalizeHost("ssh://user@ssh.example.com:443/shell"))
        assertEquals("127.0.0.1", CloudflareAccessSshSupport.normalizeHost("http://127.0.0.1:8080/ws"))

        assertEquals("wss://ssh.example.com/", CloudflareAccessSshSupport.buildWebSocketUrl("ssh.example.com", 2222))
        assertEquals("wss://ssh.example.com/", CloudflareAccessSshSupport.buildWebSocketUrl("https://ssh.example.com/", 443))
        assertEquals("ws://127.0.0.1:12345/", CloudflareAccessSshSupport.buildWebSocketUrl("http://127.0.0.1:12345/", 443))
        assertEquals(443, CloudflareAccessSshSupport.resolvePort("ssh.example.com", 2222))
        assertEquals(12345, CloudflareAccessSshSupport.resolvePort("http://127.0.0.1:12345", 443))
    }

    @Test
    fun mcpCredentialExtractionPrefersMatchingDomainAndFillsBlankProfile() {
        val servers = listOf(
            McpServerEntity(
                id = "other",
                name = "Other MCP",
                endpoint = "https://other.example.org/mcp",
                headersJson = """{"CF-Access-Client-Id":"other-id","CF-Access-Client-Secret":"other-secret"}""",
                enabled = true
            ),
            McpServerEntity(
                id = "remote",
                name = "Remote MCP",
                endpoint = "https://mcp.example.com/mcp",
                headersJson = """{"cf-access-client-id":"remote-id.access","cf_access_client_secret":"test_cf_remote_secret"}""",
                enabled = true
            )
        )

        val extracted = CloudflareAccessSshSupport.extractFromMcpServers(servers, "ssh.example.com")
        assertNotNull(extracted)
        assertEquals("remote-id.access", extracted!!.clientId)
        assertEquals("test_cf_remote_secret", extracted.clientSecret)
        assertEquals("Remote MCP", extracted.sourceServerName)

        val blankProfile = SshProfileEntity(
            id = "ext",
            name = "SSH CF",
            host = "ssh.example.com",
            port = 443,
            username = "devuser",
            useCloudflareAccess = true
        )
        val resolved = CloudflareAccessSshSupport.resolveEffectiveProfile(blankProfile, servers)
        assertEquals("remote-id.access", resolved.cfAccessClientId)
        assertEquals("test_cf_remote_secret", resolved.cfAccessClientSecret)

        val explicitProfile = blankProfile.copy(
            cfAccessClientId = "explicit-id",
            cfAccessClientSecret = "explicit-secret"
        )
        val keptExplicit = CloudflareAccessSshSupport.resolveEffectiveProfile(explicitProfile, servers)
        assertEquals("explicit-id", keptExplicit.cfAccessClientId)
        assertEquals("explicit-secret", keptExplicit.cfAccessClientSecret)
    }

    @Test
    fun webSocketSocketFactoryStreamsBinarySshAndSendsAccessHeaders() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val banner = "SSH-2.0-OpenSSH_10.6\r\n".toByteArray(StandardCharsets.UTF_8)
                        webSocket.send(banner.toByteString())
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val reply = ("ECHO:" + bytes.utf8()).toByteArray(StandardCharsets.UTF_8)
                        webSocket.send(reply.toByteString())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
            )

            val wsClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            try {
                val factory = CloudflareAccessSshSocketFactory(
                    wsUrl = "ws://127.0.0.1:${server.port}/",
                    clientId = "id-from-mcp.access",
                    clientSecret = "secret-from-mcp",
                    connectTimeoutMs = 5_000,
                    httpClient = wsClient
                )

                val socket = factory.createSocket("127.0.0.1", server.port)
                socket.soTimeout = 3_000
                val input = factory.getInputStream(socket)
                val output = factory.getOutputStream(socket)

                val buf = ByteArray(128)
                val bannerBytes = input.read(buf)
                assertTrue(bannerBytes > 0)
                assertEquals("SSH-2.0-OpenSSH_10.6\r\n", String(buf, 0, bannerBytes, StandardCharsets.UTF_8))

                output.write("SSH-2.0-JSch\r\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()

                val echoBytes = input.read(buf)
                assertTrue(echoBytes > 0)
                assertEquals("ECHO:SSH-2.0-JSch\r\n", String(buf, 0, echoBytes, StandardCharsets.UTF_8))

                val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(recorded)
                assertEquals("id-from-mcp.access", recorded!!.getHeader("CF-Access-Client-Id"))
                assertEquals("secret-from-mcp", recorded.getHeader("CF-Access-Client-Secret"))

                socket.close()
                assertEquals(-1, input.read(buf))
            } finally {
                wsClient.dispatcher.executorService.shutdown()
                wsClient.connectionPool.evictAll()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun webSocketSocketFactorySurfacesClearCloudflareAuthFailure() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://aged-block.cloudflareaccess.com/cdn-cgi/access/login")
            )
            val wsClient = OkHttpClient()
            try {
                val factory = CloudflareAccessSshSocketFactory(
                    wsUrl = "ws://127.0.0.1:${server.port}/",
                    clientId = "bad-id",
                    clientSecret = "bad-secret",
                    connectTimeoutMs = 3_000,
                    httpClient = wsClient
                )
                try {
                    factory.createSocket("127.0.0.1", server.port)
                    fail("Expected IOException when Cloudflare Access returns HTTP 302")
                } catch (e: IOException) {
                    val msg = e.message.orEmpty()
                    assertTrue("Unexpected error message: $msg", msg.contains("Cloudflare Access"))
                    assertTrue("Unexpected error message: $msg", msg.contains("302"))
                }
            } finally {
                wsClient.dispatcher.executorService.shutdown()
                wsClient.connectionPool.evictAll()
            }
        } finally {
            server.shutdown()
        }
    }
}
