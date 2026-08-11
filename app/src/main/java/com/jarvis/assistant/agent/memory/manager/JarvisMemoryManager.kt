package com.jarvis.assistant.agent.memory.manager

import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.memory.dao.FactDao
import com.jarvis.assistant.agent.memory.dao.MemoryDao
import com.jarvis.assistant.agent.memory.dao.PreferenceDao
import com.jarvis.assistant.agent.memory.dao.ProcedureDao
import com.jarvis.assistant.agent.memory.entity.*
import com.jarvis.assistant.agent.memory.extractor.AutonomousMemoryExtractor
import com.jarvis.assistant.agent.memory.model.ForgetResult
import com.jarvis.assistant.agent.memory.model.MemoryItem
import com.jarvis.assistant.agent.memory.model.MemoryType
import com.jarvis.assistant.agent.memory.vector.VectorEmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Memory Governance Engine 2.0 (JARVIS v0.5)
 * Реализует полный цикл управления памятью:
 * Extractor ──► Deduplication ──► Multi-Type Storage ──► Vector Scored Retrieval ──► Semantic Forget/Deletion.
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
     * Сохранение нового воспоминания с вычислением вектора, дедупликацией и разрешением конфликтов
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
        val cleanKey = key?.trim()?.lowercase()
        val cleanContent = content.trim()

        // 1. Векторное представление текста
        val vector = vectorEngine.createEmbedding(cleanContent)
        val vectorStr = vectorEngine.serializeVector(vector)

        // 2. Дедупликация и обновление в специализированных таблицах (Facts / Preferences)
        if (!cleanKey.isNullOrBlank()) {
            when (type) {
                MemoryType.FACT -> {
                    factDao.insertFact(
                        FactEntity(
                            factKey = cleanKey,
                            factValue = value ?: cleanContent,
                            confidence = confidence,
                            updatedAt = now
                        )
                    )
                }
                MemoryType.PREFERENCE -> {
                    preferenceDao.insertPreference(
                        PreferenceEntity(
                            prefKey = cleanKey,
                            prefValue = value ?: cleanContent,
                            updatedAt = now
                        )
                    )
                }
                else -> Unit
            }
            // Удаляем устаревшее воспоминание по этому же ключу в общей таблице для исключения дубликатов
            memoryDao.deleteMemoryByKey(cleanKey)
        }

        // 3. Сохранение в общую таблицу воспоминаний с важностью и уверенностью
        val entity = MemoryEntity(
            type = type.name,
            keyName = cleanKey,
            content = cleanContent,
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
     * Семантический поиск воспоминаний по формуле взвешенного ранжирования:
     * Score = (CosineSim * 0.40) + (Importance * 0.25) + (Recency * 0.20) + (Frequency * 0.15)
     */
    suspend fun recall(query: String, limit: Int = 4): List<MemoryItem> = withContext(Dispatchers.IO) {
        val queryVector = vectorEngine.createEmbedding(query)
        val allMemories = memoryDao.getAllMemoriesForVectorSearch()

        if (allMemories.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val scoredList = mutableListOf<Pair<MemoryEntity, Float>>()

        for (mem in allMemories) {
            val memVector = vectorEngine.deserializeVector(mem.embeddingVector)
            val cosineSim = vectorEngine.computeCosineSimilarity(queryVector, memVector)

            val daysAgo = (now - mem.lastAccessedAt) / (1000f * 60 * 60 * 24)
            val recencyFactor = exp(-daysAgo / 30.0f)
            val frequencyFactor = (mem.accessCount / 10.0f).coerceAtMost(1.0f)

            val totalScore = (cosineSim * 0.40f) +
                    (mem.importance * 0.25f) +
                    (recencyFactor * 0.20f) +
                    (frequencyFactor * 0.15f)

            if (cosineSim > 0.20f || mem.importance >= 0.8f) {
                scoredList.add(mem to totalScore)
            }
        }

        val topResults = scoredList
            .sortedByDescending { it.second }
            .take(limit)

        topResults.forEach { (entity, _) ->
            memoryDao.recordAccess(entity.id, now)
        }

        topResults.map { (entity, score) -> entity.toDomain(score) }
    }

    /**
     * Семантическое удаление / забывание факта («Джарвис, забудь, что я хотел BMW»)
     */
    suspend fun forgetMemory(targetQuery: String): ForgetResult = withContext(Dispatchers.IO) {
        val cleanTarget = targetQuery.lowercase().trim()
        if (cleanTarget.isEmpty() || cleanTarget == "всё" || cleanTarget == "все") {
            // Полная очистка памяти
            val all = memoryDao.getAllMemoriesForVectorSearch()
            all.forEach { memoryDao.deleteMemoryById(it.id) }
            return@withContext ForgetResult(
                isSuccess = true,
                deletedCount = all.size,
                deletedSummaries = listOf("Вся память очищена"),
                confirmationMessage = "Хорошо, сэр. Я полностью очистил память о вас."
            )
        }

        val queryVector = vectorEngine.createEmbedding(cleanTarget)
        val allMemories = memoryDao.getAllMemoriesForVectorSearch()
        val toDelete = mutableListOf<MemoryEntity>()

        for (mem in allMemories) {
            val memVector = vectorEngine.deserializeVector(mem.embeddingVector)
            val similarity = vectorEngine.computeCosineSimilarity(queryVector, memVector)
            val containsWord = mem.content.contains(cleanTarget, ignoreCase = true) ||
                    (mem.keyName?.contains(cleanTarget, ignoreCase = true) == true)

            if (similarity >= 0.40f || containsWord) {
                toDelete.add(mem)
            }
        }

        val deletedSummaries = mutableListOf<String>()
        for (mem in toDelete) {
            memoryDao.deleteMemoryById(mem.id)
            mem.keyName?.let { key ->
                factDao.deleteFact(key)
                preferenceDao.deletePreference(key)
            }
            deletedSummaries.add(mem.content)
        }

        if (toDelete.isNotEmpty()) {
            ForgetResult(
                isSuccess = true,
                deletedCount = toDelete.size,
                deletedSummaries = deletedSummaries,
                confirmationMessage = "Хорошо, сэр. Я стёр информацию о \"$cleanTarget\" из памяти."
            )
        } else {
            ForgetResult(
                isSuccess = false,
                deletedCount = 0,
                deletedSummaries = emptyList(),
                confirmationMessage = "Я не нашёл в памяти записей о \"$cleanTarget\"."
            )
        }
    }

    /**
     * Фоновый конвейер Governance: извлекает и сохраняет только важные факты, либо стирает по запросу
     */
    suspend fun processTurnGovernance(userMessage: String) = withContext(Dispatchers.IO) {
        val extracted = memoryExtractor.extractFromTurn(userMessage) ?: return@withContext

        if (extracted.governanceAction == "DELETE_FORGET") {
            forgetMemory(extracted.key ?: userMessage)
        } else if (extracted.shouldRemember) {
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
     * Формирует сжатый контекст из 3-4 самых релевантных фактов для системного промпта
     */
    suspend fun buildPromptMemoryContext(userQuery: String): String = withContext(Dispatchers.IO) {
        val relevant = recall(userQuery, limit = 4)
        val sb = StringBuilder()

        val working = workingMemory.getWorkingContextSummary()
        if (working.isNotBlank()) {
            sb.append("$working\n")
        }

        if (relevant.isNotEmpty()) {
            sb.append("Долговременная память о пользователе:\n")
            relevant.forEach { item ->
                sb.append("• ${item.content}\n")
            }
        }

        return@withContext sb.toString().trim()
    }
}
