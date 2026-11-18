package com.forgemind.app

import com.forgemind.app.data.model.*
import com.forgemind.app.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetHotfixTests {
    private val exact = "Context budget exceeded: 7287 prompt + 32768 requested output tokens > 8192. Shorten the conversation/output or plan a larger --max-context within the model's position limit."
    @Test fun exactServerErrorUsesActualRemainingBudget() {
        assertEquals(873, ContextBudgetRecovery.outputLimit(exact,32768))
        assertNull(ContextBudgetRecovery.outputLimit("context size 8192",32768))
        assertNull(ContextBudgetRecovery.outputLimit(exact.replace("7287 prompt","9000 prompt"),32768))
    }
    @Test fun manualMillionSurvivesDiscoveryAndSerialization() {
        val cfg=ProviderConfig("custom_local",modelId="local",contextWindow=8192,modelContextOverrides=mapOf("local" to 1_000_000))
        val loaded=Json.decodeFromString<ProviderConfig>(Json.encodeToString(ProviderConfig.serializer(),cfg))
        assertEquals(1_000_000,loaded.resolveContextWindowForModel("local",8192))
        assertEquals(65_536,cfg.copy(modelContextOverrides=emptyMap(),contextWindow=65_536).resolveContextWindowForModel("local"))
    }
    @Test fun explicitOutputIsNotSilentlyHalved() {
        val cfg=ProviderConfig("custom_local",modelId="local",contextWindow=8192,maxTokens=40000)
        val body=OpenAiCompatibleClient.buildRequestBody(cfg,ProviderType.OPENAI,emptyList(),emptyList(),false)
        assertEquals(40000,body["max_tokens"]!!.jsonPrimitive.int)
    }
    @Test fun compatRetriesRejectedBudgetOnceAndOverridesExtraBody() = runBlocking {
        checkRecovery(false)
    }
    @Test fun responsesRetriesRejectedBudgetOnceAndOverridesExtraBody() = runBlocking {
        checkRecovery(true)
    }
    private suspend fun checkRecovery(responses:Boolean) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(buildJsonObject {put("error",buildJsonObject {put("message",exact)})}.toString()))
            val data=if(responses) """{"type":"response.output_text.delta","delta":"OK"}""" else """{"choices":[{"delta":{"content":"OK"}}]}"""
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $data\n\ndata: [DONE]\n\n"))
            server.start()
            val cfg=ProviderConfig("custom_local",modelId="local",baseUrl=server.url("/").toString(),maxTokens=40000,contextWindow=1_000_000,enableThinking=false,
                extraBodyJson=if(responses) """{"max_output_tokens":32768}""" else """{"max_tokens":32768}""")
            val msgs=listOf(LlmWireMessage(LlmRole.USER,"Hello"))
            val events=if(responses)OpenAiResponsesClient().streamChat(cfg,msgs,emptyList()).toList() else OpenAiCompatibleClient().streamChat(cfg,msgs,emptyList()).toList()
            assertFalse(events.any {it is LlmStreamEvent.Error})
            val field=if(responses)"max_output_tokens" else "max_tokens"
            val first=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val second=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(32768,first[field]!!.jsonPrimitive.int)
            assertEquals(873,second[field]!!.jsonPrimitive.int)
            assertEquals(2,server.requestCount)
        }
    }
}
