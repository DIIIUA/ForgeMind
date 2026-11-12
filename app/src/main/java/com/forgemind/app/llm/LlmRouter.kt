package com.forgemind.app.llm

import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.WireProtocol
import kotlinx.coroutines.flow.Flow

class LlmRouter(
    private val openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    private val anthropicClient: AnthropicClient = AnthropicClient(),
    private val geminiClient: GeminiClient = GeminiClient()
) {
    fun streamChat(
        config: ProviderConfig,
        messages: List<LlmWireMessage>,
        tools: List<LlmToolSpec>
    ): Flow<LlmStreamEvent> {
        val type = ProviderType.fromId(config.providerId)
        return when (type.wireProtocol) {
            WireProtocol.OPENAI_COMPAT -> openAiClient.streamChat(config, messages, tools)
            WireProtocol.ANTHROPIC -> anthropicClient.streamChat(config, messages, tools)
            WireProtocol.GEMINI -> geminiClient.streamChat(config, messages, tools)
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
}
