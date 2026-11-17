package com.forgemind.app.data.memory

import kotlin.math.sqrt

object SemanticMath {
    fun normalize(values: FloatArray): FloatArray {
        require(values.isNotEmpty() && values.all { it.isFinite() })
        val norm = sqrt(values.sumOf { it.toDouble() * it }.toFloat())
        require(norm > 1e-12f) { "Модель вернула нулевой вектор" }
        return FloatArray(values.size) { values[it] / norm }
    }
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty())
        return a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat().coerceIn(-1f,1f)
    }
    fun score(query: List<FloatArray>, document: List<FloatArray>): Float =
        query.map { q -> document.maxOf { cosine(q, it) } }.average().toFloat()
}
data class SemanticDocument(val id: String, val text: String)
data class SemanticHit(val id: String, val score: Float)
