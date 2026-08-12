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
import kotlin.math.ln

/**
 * Memory Governance Engine 2.0 (JARVIS v0.5)
 * Реализует полный цикл управления памятью:
 * Extractor ──► Deduplication ──► Multi-Type Storage ──► Vector + TF-IDF Retrieval ──► Semantic Forget/Deletion.
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

        // 2. Дедупликация: поиск и удаление семантических дубликатов (>85% overlap слов или >0.88 векторное сходство)
        val allExisting = memoryDao.getAllMemoriesForVectorSearch()
        for (existing in allExisting) {
            val overlap = calculateWordOverlap(existing.content, cleanContent)
            val existingVector = vectorEngine.deserializeVector(existing.embeddingVector)
            val vectorSim = vectorEngine.computeCosineSimilarity(vector, existingVector)

            if (overlap >= 0.85f || vectorSim >= 0.88f || (!cleanKey.isNullOrBlank() && existing.keyName == cleanKey)) {
                // Удаляем старый дубликат в пользу новейшей записи
                memoryDao.deleteMemoryById(existing.id)
            }
        }

        // 3. Сохранение в специализированных таблицах (Facts / Preferences)
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
        }

        // 4. Сохранение в общую таблицу воспоминаний с важностью и уверенностью
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
     * Семантический поиск воспоминаний по гибридной формуле (Cosine Vector + TF-IDF + Importance + Recency + Frequency):
     * Score = (CosineSim * 0.35) + (TfIdf * 0.25) + (Importance * 0.20) + (Recency * 0.10) + (Frequency * 0.10)
     */
    suspend fun recall(query: String, limit: Int = 3): List<MemoryItem> = withContext(Dispatchers.IO) {
        val q = query.lowercase().trim()
        val queryVector = vectorEngine.createEmbedding(q)
        val queryTokens = q.split(Regex("[\\s,?.!]+")).filter { it.length >= 2 }
        val allMemories = memoryDao.getAllMemoriesForVectorSearch()

        if (allMemories.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val totalDocs = allMemories.size.toDouble()
        val scoredList = mutableListOf<Pair<MemoryEntity, Float>>()

        for (mem in allMemories) {
            val memText = "${mem.keyName.orEmpty()} ${mem.content}".lowercase()
            val memTokens = memText.split(Regex("[\\s,?.!]+")).filter { it.length >= 2 }

            // 1. Vector Cosine Similarity
            val memVector = vectorEngine.deserializeVector(mem.embeddingVector)
            val cosineSim = vectorEngine.computeCosineSimilarity(queryVector, memVector)

            // 2. TF-IDF Lexical Match
            var tfIdfScore = 0f
            for (token in queryTokens) {
                if (memTokens.any { it.contains(token) || token.contains(it) }) {
                    val docFrequency = allMemories.count { it.content.contains(token, ignoreCase = true) }.coerceAtLeast(1)
                    val idf = ln(1.0 + (totalDocs / docFrequency.toDouble()))
                    tfIdfScore += idf.toFloat()
                }
            }
            val normalizedTfIdf = (tfIdfScore / 2.5f).coerceAtMost(1.0f)

            // 3. Временные и частотные факторы
            val daysAgo = (now - mem.lastAccessedAt) / (1000f * 60 * 60 * 24)
            val recencyFactor = exp(-daysAgo / 30.0f)
            val frequencyFactor = (mem.accessCount / 10.0f).coerceAtMost(1.0f)

            // 4. Итоговый скор
            val totalScore = (cosineSim * 0.35f) +
                    (normalizedTfIdf * 0.25f) +
                    (mem.importance * 0.20f) +
                    (recencyFactor * 0.10f) +
                    (frequencyFactor * 0.10f)

            if (cosineSim > 0.18f || normalizedTfIdf > 0.20f || mem.importance >= 0.85f) {
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
     * Формирует сжатый контекст из top-3 самых релевантных фактов для системного промпта
     * Лимит: максимум 200 токенов (не более ~800 символов), чтобы не раздувать промпт AI.
     */
    suspend fun buildPromptMemoryContext(userQuery: String): String = withContext(Dispatchers.IO) {
        val relevant = recall(userQuery, limit = 3)
        val sb = StringBuilder()

        val working = workingMemory.getWorkingContextSummary()
        if (working.isNotBlank()) {
            sb.append("$working\n")
        }

        if (relevant.isNotEmpty()) {
            sb.append("Память о пользователе:\n")
            var tokenBudget = 180 // ~180 слов/токенов
            for (item in relevant) {
                val wordsCount = item.content.split(Regex("\\s+")).size
                if (tokenBudget - wordsCount >= 0) {
                    sb.append("• ${item.content}\n")
                    tokenBudget -= wordsCount
                } else {
                    break
                }
            }
        }

        val result = sb.toString().trim()
        return@withContext if (result.length > 800) result.take(800) + "..." else result
    }

    private fun calculateWordOverlap(s1: String, s2: String): Float {
        val w1 = s1.lowercase().split(Regex("[\\s,?.!]+")).filter { it.length >= 3 }.toSet()
        val w2 = s2.lowercase().split(Regex("[\\s,?.!]+")).filter { it.length >= 3 }.toSet()
        if (w1.isEmpty() || w2.isEmpty()) return 0f
        val intersection = w1.intersect(w2).size
        val union = w1.union(w2).size
        return intersection.toFloat() / union.toFloat()
    }
}
