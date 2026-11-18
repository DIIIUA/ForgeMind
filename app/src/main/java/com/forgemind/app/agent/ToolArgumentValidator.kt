package com.forgemind.app.agent

import kotlinx.serialization.json.*

object ToolArgumentValidator {
    fun validate(raw: String, schema: JsonObject?): String? = try {
        require(raw.length <= 256_000) { "Аргументы слишком велики" }
        val value = Json.parseToJsonElement(raw)
        require(value is JsonObject) { "Аргументы должны быть JSON-объектом" }
        if (schema != null) check(value, schema, "$", 0)
        null
    } catch (failure: Exception) { failure.message ?: "Неверные аргументы" }

    private fun check(value: JsonElement, schema: JsonObject, path: String, depth: Int) {
        require(depth <= 24) { "$path: слишком глубокая вложенность" }
        (schema["enum"] as? JsonArray)?.let { require(value in it) { "$path: значение отсутствует в enum" } }
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
        val primitive = value as? JsonPrimitive
        val matches = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> primitive?.isString == true
            "boolean" -> primitive?.isString == false && primitive.booleanOrNull != null
            "integer" -> primitive?.isString == false && primitive.longOrNull != null
            "number" -> primitive?.isString == false && primitive.doubleOrNull?.isFinite() == true
            "null" -> value == JsonNull
            else -> true
        }
        require(matches) { "$path: ожидается $type" }
        if (value is JsonObject) {
            (schema["required"] as? JsonArray)?.forEach {
                require((it as JsonPrimitive).content in value) { "$path: отсутствует ${it.content}" }
            }
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false)
                require(value.keys.all { it in properties }) { "$path: неизвестный параметр" }
            value.forEach { (key, item) -> (properties[key] as? JsonObject)?.let { check(item, it, "$path.$key", depth + 1) } }
        }
        if (value is JsonArray) (schema["items"] as? JsonObject)?.let { child ->
            value.forEachIndexed { index, item -> check(item, child, "$path[$index]", depth + 1) }
        }
        if (primitive?.isString == false) primitive.doubleOrNull?.let { n ->
            (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { require(n >= it) { "$path: меньше $it" } }
            (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { require(n <= it) { "$path: больше $it" } }
        }
    }
}
