package com.forgemind.app.llm

import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.WireProtocol
import kotlinx.coroutines.flow.Flow

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val replyPreview: String = "",
    val errorMessage: String = ""
)

class LlmRouter(
    private val openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    private val openAiResponsesClient: OpenAiResponsesClient = OpenAiResponsesClient(),
    private val anthropicClient: AnthropicClient = AnthropicClient(),
    private val geminiClient: GeminiClient = GeminiClient(),
    private val ollamaClient: OllamaNativeClient = OllamaNativeClient()
) {
    fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent> {
        return when (config.effectiveWireProtocol()) {
            WireProtocol.OPENAI_COMPAT -> openAiClient.streamChat(config, messages, tools)
            WireProtocol.OPENAI_RESPONSES -> openAiResponsesClient.streamChat(config, messages, tools)
            WireProtocol.ANTHROPIC -> anthropicClient.streamChat(config, messages, tools)
            WireProtocol.GEMINI -> geminiClient.streamChat(config, messages, tools)
            WireProtocol.OLLAMA_NATIVE -> ollamaClient.streamChat(config, messages, tools)
        }
    }

    suspend fun completeOnce(
        config: ProviderConfig,
        systemPrompt: String,
        userPrompt: String
    ): Result<String> {
        val sb = StringBuilder()
        var errMsg: String? = null
        val noThinkCfg = config.copy(
            enableThinking = false,
            maxTokens = config.maxTokens.coerceIn(1024, 4096)
        )
        val msgs = listOf(
            LlmWireMessage(role = LlmRole.SYSTEM, content = systemPrompt),
            LlmWireMessage(role = LlmRole.USER, content = userPrompt)
        )
        streamChat(noThinkCfg, msgs, emptyList()).collect { ev ->
            when (ev) {
                is LlmStreamEvent.TextDelta -> sb.append(ev.text)
                is LlmStreamEvent.Error -> errMsg = ev.message
                else -> Unit
            }
        }
        return if (errMsg != null && sb.isBlank()) {
            Result.failure(IllegalStateException(errMsg))
        } else {
            Result.success(sb.toString().trim())
        }
    }

    suspend fun testConnection(config: ProviderConfig): ConnectionTestResult {
        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()
        val thinkSb = StringBuilder()
        var errMsg: String? = null
        val probeCfg = config.copy(
            enableThinking = false,
            maxTokens = 64,
            readTimeoutSec = config.readTimeoutSec.coerceIn(15, 45)
        )
        val msgs = listOf(
            LlmWireMessage(role = LlmRole.USER, content = "Reply with OK.")
        )
        runCatching {
            streamChat(probeCfg, msgs, emptyList()).collect { ev ->
                when (ev) {
                    is LlmStreamEvent.TextDelta -> if (sb.length < 160) sb.append(ev.text)
                    is LlmStreamEvent.ThinkingDelta -> if (thinkSb.length < 160) thinkSb.append(ev.text)
                    is LlmStreamEvent.Error -> errMsg = ev.message
                    else -> Unit
                }
            }
        }.onFailure { e ->
            errMsg = e.message ?: e::class.java.simpleName
        }
        val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        val preview = sb.toString().trim().ifBlank { thinkSb.toString().trim() }
            .replace('\n', ' ')
            .take(90)
        return if (errMsg != null && preview.isEmpty()) {
            ConnectionTestResult(
                success = false,
                latencyMs = elapsed,
                errorMessage = errMsg.orEmpty().take(260)
            )
        } else {
            ConnectionTestResult(
                success = true,
                latencyMs = elapsed,
                replyPreview = preview.ifBlank { config.modelId }
            )
        }
    }
}
