package com.forgemind.app

import com.forgemind.app.data.model.*
import com.forgemind.app.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TextToolWireTests {
    @Test fun forcedXmlModeSendsCatalogWithoutApiToolsThroughEveryWireProtocol() = runBlocking {
        for (wire in WireProtocol.entries) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: [DONE]\n\n"))
                server.start()
                val cfg=ProviderConfig("custom_wire_qa",baseUrl=server.url("/").toString(),modelId="qa",apiKeysRaw="synthetic-test-key",
                    rawEndpointUrl=true,wireProtocolOverride=wire,toolCallingMode=ToolCallingMode.XML_JSON,readTimeoutSec=5)
                val specs=listOf(LlmToolSpec("probe_tool","synthetic tool",buildJsonObject { put("type","object");putJsonObject("properties") {} }))
                LlmRouter().streamChat(cfg,listOf(LlmWireMessage(LlmRole.USER,"hello")),specs).collect { }
                val request=server.takeRequest(5,TimeUnit.SECONDS) ?: error("No request for $wire")
                val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertFalse("API tools for $wire",body.containsKey("tools"))
                assertTrue("Missing XML protocol for $wire",body.toString().contains("tool_call"))
                assertTrue("Missing catalog for $wire",body.toString().contains("probe_tool"))
            }
        }
    }
}
