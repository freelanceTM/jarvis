package com.jarvis.assistant.agent.memory.manager

import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.memory.dao.FactDao
import com.jarvis.assistant.agent.memory.dao.MemoryDao
import com.jarvis.assistant.agent.memory.dao.PreferenceDao
import com.jarvis.assistant.agent.memory.dao.ProcedureDao
import com.jarvis.assistant.agent.memory.entity.*
import com.jarvis.assistant.agent.memory.extractor.AutonomousMemoryExtractor
import com.jarvis.assistant.agent.memory.model.MemoryItem
import com.jarvis.assistant.agent.memory.model.MemoryType
import com.jarvis.assistant.agent.memory.vector.VectorEmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Единый когнитивный менеджер гибридной памяти JARVIS v0.3
 * (Room DB + Векторный семантический поиск + Автономный экстрактор + Разрешение конфликтов).
 */
@Singleton
class JarvisMemoryManager @Inject constructor(
    val workingMemory: WorkingMemory,
    private val memoryDao: MemoryDao,
    private val factDao: FactDao,
    private val preferenceDao: PreferenceDao,
    private val procedureDao: ProcedureDao,
    private val vectorEngine: VectorEmbeddingEngine,
    private val memoryExtractor: AutonomousMemoryExtractor
) {
    /**
     * Сохранение нового воспоминания с вычислением вектора и разрешением конфликтов
     */
    suspend fun remember(
        type: MemoryType,
        content: String,
        key: String? = null,
        value: String? = null,
        importance: Float = 0.5f,
        confidence: Float = 1.0f
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 1. Вычисляем нормализованный 64-мерный вектор эмбеддинга текста
        val vector = vectorEngine.createEmbedding(content)
        val vectorStr = vectorEngine.serializeVector(vector)

        // 2. Разрешение конфликтов для структурированных фактов (Facts / Preferences)
        if (!key.isNullOrBlank()) {
            when (type) {
                MemoryType.FACT -> {
                    factDao.insertFact(
                        FactEntity(
                            factKey = key.trim().lowercase(),
                            factValue = value ?: content,
                            confidence = confidence,
                            updatedAt = now
                        )
                    )
                }
                MemoryType.PREFERENCE -> {
                    preferenceDao.insertPreference(
                        PreferenceEntity(
                            prefKey = key.trim().lowercase(),
                            prefValue = value ?: content,
                            updatedAt = now
                        )
                    )
                }
                else -> Unit
            }
        }

        // 3. Сохранение в общую таблицу воспоминаний
        val entity = MemoryEntity(
            type = type.name,
            keyName = key?.trim()?.lowercase(),
            content = content.trim(),
            importance = importance.coerceIn(0.1f, 1.0f),
            confidence = confidence.coerceIn(0.1f, 1.0f),
            accessCount = 1,
            embeddingVector = vectorStr,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now
        )

        memoryDao.insertMemory(entity)
    }

    /**
     * Семантический поиск по косинусному сходству + формула веса важности/актуальности
     * Score = (CosineSim * 0.40) + (Importance * 0.25) + (Recency * 0.20) + (Frequency * 0.15)
     */
    suspend fun recall(query: String, limit: Int = 5): List<MemoryItem> = withContext(Dispatchers.IO) {
        val queryVector = vectorEngine.createEmbedding(query)
        val allMemories = memoryDao.getAllMemoriesForVectorSearch()

        if (allMemories.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val scoredList = mutableListOf<Pair<MemoryEntity, Float>>()

        for (mem in allMemories) {
            val memVector = vectorEngine.deserializeVector(mem.embeddingVector)
            val cosineSim = vectorEngine.computeCosineSimilarity(queryVector, memVector)

            // Фактор давности (затухание по времени: 30 дней)
            val daysAgo = (now - mem.lastAccessedAt) / (1000f * 60 * 60 * 24)
            val recencyFactor = exp(-daysAgo / 30.0f)

            // Фактор частоты обращений
            val frequencyFactor = (mem.accessCount / 10.0f).coerceAtMost(1.0f)

            // Взвешенная формула ранжирования памяти
            val totalScore = (cosineSim * 0.40f) +
                    (mem.importance * 0.25f) +
                    (recencyFactor * 0.20f) +
                    (frequencyFactor * 0.15f)

            scoredList.add(mem to totalScore)
        }

        // Сортируем по финальному баллу и берем топ N
        val topResults = scoredList
            .sortedByDescending { it.second }
            .take(limit)

        // Обновляем метрику доступа для релевантных воспоминаний
        topResults.forEach { (entity, _) ->
            memoryDao.recordAccess(entity.id, now)
        }

        topResults.map { (entity, score) -> entity.toDomain(score) }
    }

    /**
     * Фоновый экстрактор: после каждой реплики определяет, нужно ли зафиксировать новый факт
     */
    suspend fun extractAndRememberInBackground(userMessage: String) = withContext(Dispatchers.IO) {
        val extracted = memoryExtractor.extractFromTurn(userMessage)
        if (extracted != null && extracted.shouldRemember) {
            val type = try { MemoryType.valueOf(extracted.type) } catch (_: Exception) { MemoryType.FACT }
            remember(
                type = type,
                content = extracted.content,
                key = extracted.key,
                value = extracted.value,
                importance = extracted.importance,
                confidence = extracted.confidence
            )
        }
    }

    /**
     * Формирует сжатый контекст из 3-5 самых релевантных фактов для системного промпта
     */
    suspend fun buildPromptMemoryContext(userQuery: String): String = withContext(Dispatchers.IO) {
        val relevant = recall(userQuery, limit = 4)
        val sb = StringBuilder()

        val working = workingMemory.getWorkingContextSummary()
        if (working.isNotBlank()) {
            sb.append("$working\n")
        }

        if (relevant.isNotEmpty()) {
            sb.append("Релевантная долговременная память:\n")
            relevant.forEach { item ->
                sb.append("• ${item.content}\n")
            }
        }

        return@withContext sb.toString().trim()
    }

    suspend fun deleteMemoryByKey(key: String) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryByKey(key.trim().lowercase())
        factDao.deleteFact(key.trim().lowercase())
        preferenceDao.deletePreference(key.trim().lowercase())
    }
}
