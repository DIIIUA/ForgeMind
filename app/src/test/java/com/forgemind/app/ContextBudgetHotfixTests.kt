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

    @Test fun recognizesOpenAiVllmOpenRouterAnthropicAndGeminiContextErrors() {
        // OpenAI chat/completions
        val openai = "This model's maximum context length is 128000 tokens. However, your messages resulted in 127000 tokens."
        assertEquals(968, ContextBudgetRecovery.outputLimit(openai, 4096))

        // OpenAI legacy / vLLM
        val vllm = "This model's maximum context length is 32768 tokens. However, you requested 35000 tokens (30000 in the messages, 5000 in the completion)."
        assertEquals(2736, ContextBudgetRecovery.outputLimit(vllm, 5000))

        // OpenRouter
        val openrouter = "This endpoint's maximum context length is 131072 tokens. However, you requested about 135000 tokens (120000 of text input, 15000 in the output)."
        assertEquals(11040, ContextBudgetRecovery.outputLimit(openrouter, 15000))

        val openrouterSum = "Prompt tokens limit exceeded: 125000 + 8192 > 131072"
        assertEquals(6040, ContextBudgetRecovery.outputLimit(openrouterSum, 8192))

        // Anthropic & Gemini when prompt itself exceeds window -> must return null (cannot recover by shrinking output)
        assertNull(ContextBudgetRecovery.outputLimit("prompt is too long: 205000 tokens > 200000 maximum", 4096))
        assertNull(ContextBudgetRecovery.outputLimit("The input token count (1050000) exceeds the maximum number of tokens allowed (1048576).", 4096))
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
