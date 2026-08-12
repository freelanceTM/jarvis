package com.jarvis.assistant.agent.memory.vector

import com.jarvis.assistant.agent.discovery.SynonymDictionary
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class VectorEmbeddingEngine @Inject constructor() {

    private val vectorDimensions = 64

    /**
     * Создает семантически взвешенный нормализованный 64-D вектор.
     * Объединяет:
     * 1. Semantic Concept Space Projection (синонимы проецируются в одинаковые базисные координаты)
     * 2. Лексический спектральный отпечаток триграмм слов
     * Время вычисления: < 0.5 мс на процессоре, 100% офлайн.
     */
    fun createEmbedding(text: String): FloatArray {
        val vector = FloatArray(vectorDimensions)
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return vector

        val words = normalized.split(Regex("[\\s,?.!]+")).filter { it.length >= 2 }

        for (word in words) {
            // 1. Семантическая проекция: синонимы активируют одни и те же координаты концептов
            val synonyms = SynonymDictionary.getSynonyms(word)
            if (synonyms.isNotEmpty()) {
                val groupAnchor = synonyms.first()
                val groupHash = groupAnchor.hashCode()
                val primaryDim = (groupHash and 0x7FFFFFFF) % (vectorDimensions / 2)
                val secondaryDim = ((groupHash * 31) and 0x7FFFFFFF) % (vectorDimensions / 2)

                vector[primaryDim] += 3.0f   // Доминантный семантический вес концепта
                vector[secondaryDim] += 2.0f
            }

            // 2. Индивидуальный лексический и триграммный отпечаток (вторая половина вектора)
            val hash = word.hashCode()
            val dim = (vectorDimensions / 2) + ((hash and 0x7FFFFFFF) % (vectorDimensions / 2))
            vector[dim] += 1.0f

            for (i in 0 until (word.length - 2)) {
                val trigram = word.substring(i, i + 3)
                val triDim = (vectorDimensions / 2) + ((trigram.hashCode() and 0x7FFFFFFF) % (vectorDimensions / 2))
                vector[triDim] += 0.3f
            }
        }

        // 3. L2 Нормализация вектора к единичной сфере
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
