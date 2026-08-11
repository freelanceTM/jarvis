package com.jarvis.assistant.agent.memory.vector

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class VectorEmbeddingEngine @Inject constructor() {

    private val vectorDimensions = 64

    /**
     * Создает нормализованный вектор размерности 64 на основе спектрального хэширования n-грамм текста.
     * Быстро (< 1 мс), работает 100% офлайн прямо на процессоре Android.
     */
    fun createEmbedding(text: String): FloatArray {
        val vector = FloatArray(vectorDimensions)
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return vector

        val words = normalized.split(Regex("[\\s,?.!]+")).filter { it.length >= 2 }

        // 1. Пословное и триграммное хэширование
        for (word in words) {
            val hash = word.hashCode()
            val dim = (hash and 0x7FFFFFFF) % vectorDimensions
            vector[dim] += 1.0f

            // Триграммы
            for (i in 0 until (word.length - 2)) {
                val trigram = word.substring(i, i + 3)
                val triDim = (trigram.hashCode() and 0x7FFFFFFF) % vectorDimensions
                vector[triDim] += 0.5f
            }
        }

        // 2. L2 Нормализация вектора
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)

        if (norm > 0.0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return vector
    }

    /**
     * Вычисляет косинусное сходство (Cosine Similarity) от 0.0 до 1.0
     */
    fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0.0f

        var dotProduct = 0.0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
        }

        return dotProduct.coerceIn(0.0f, 1.0f)
    }

    fun serializeVector(vector: FloatArray): String {
        return vector.joinToString(",") { it.toString() }
    }

    fun deserializeVector(vectorStr: String): FloatArray {
        if (vectorStr.isBlank()) return FloatArray(vectorDimensions)
        return try {
            val parts = vectorStr.split(",")
            val res = FloatArray(parts.size)
            for (i in parts.indices) {
                res[i] = parts[i].toFloat()
            }
            res
        } catch (_: Exception) {
            FloatArray(vectorDimensions)
        }
    }
}
