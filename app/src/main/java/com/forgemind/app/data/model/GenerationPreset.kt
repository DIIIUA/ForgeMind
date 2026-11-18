package com.forgemind.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GenerationPreset(val temperature: Float? = null, val maxTokens: Int? = null, val reasoning: String? = null) {
    fun validate() {
        require(temperature == null || temperature.isFinite() && temperature in 0f..2f) { "Температура: 0–2" }
        require(maxTokens == null || maxTokens in 128..128000) { "Длина ответа: 128–128000 токенов" }
        require(reasoning == null || ReasoningEffort.entries.any { it.id == reasoning }) { "Неизвестный режим размышлений" }
    }
    fun apply(config: ProviderConfig): ProviderConfig {
        validate()
        val base = config.copy(temperature = temperature ?: config.temperature, maxTokens = maxTokens ?: config.maxTokens)
        val result=reasoning?.let { id -> base.withReasoningEffort(ReasoningEffort.entries.first { it.id == id }) } ?: base
        // The explicit output cap wins. Keep thinking within that cap as well.
        return if(maxTokens==null) result else result.copy(maxTokens=maxTokens,
            thinkingBudget=result.thinkingBudget.coerceAtMost((maxTokens-64).coerceAtLeast(0)))
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(raw: String): GenerationPreset = json.decodeFromString<GenerationPreset>(raw).also { it.validate() }
    }
}
