package com.forgemind.app.llm

import kotlinx.serialization.json.*

data class ModelCapabilities(val vision: Boolean? = null, val tools: Boolean? = null, val reasoning: Boolean? = null) {
    fun label(): String {
        fun mark(value: Boolean?) = when (value) { true -> "✓"; false -> "−"; null -> "?" }
        return "Зрение ${mark(vision)} · Инструменты ${mark(tools)} · Размышления ${mark(reasoning)}"
    }
    companion object {
        /** Unknown stays unknown: model names are not evidence of capabilities. */
        fun fromCatalog(model: JsonObject): ModelCapabilities {
            val architecture = model["architecture"] as? JsonObject
            val modalities = architecture?.get("input_modalities") as? JsonArray
                ?: model["input_modalities"] as? JsonArray
            val parameters = model["supported_parameters"] as? JsonArray
            val caps = model["capabilities"]
            val capNames = (caps as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val capObject = caps as? JsonObject
            fun flag(key: String) = (capObject?.get(key) as? JsonPrimitive)?.booleanOrNull
                ?: (model[key] as? JsonPrimitive)?.booleanOrNull
            fun supported(vararg keys: String): Boolean? = parameters?.let { array ->
                val names = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                keys.any { it in names }
            }
            return ModelCapabilities(
                flag("vision") ?: modalities?.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
                    ?: capNames?.contains("vision"),
                flag("tools") ?: flag("supports_tools") ?: supported("tools", "tool_choice") ?: capNames?.contains("tools"),
                flag("reasoning") ?: supported("reasoning", "reasoning_effort", "include_reasoning") ?: capNames?.contains("thinking")
            )
        }
    }
}
