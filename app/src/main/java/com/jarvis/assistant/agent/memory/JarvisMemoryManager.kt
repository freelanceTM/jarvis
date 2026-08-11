package com.jarvis.assistant.agent.memory

import com.jarvis.assistant.agent.memory.dao.ProceduralMemoryDao
import com.jarvis.assistant.agent.memory.dao.SemanticMemoryDao
import com.jarvis.assistant.agent.memory.entity.ProceduralMemoryEntity
import com.jarvis.assistant.agent.memory.entity.SemanticMemoryEntity
import com.jarvis.assistant.data.local.dao.MessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый Менеджер Памяти JARVIS (4 слоя памяти).
 * Предоставляет любому AI релевантный контекст (3-5 фактов) без избыточного расхода токенов.
 */
@Singleton
class JarvisMemoryManager @Inject constructor(
    val workingMemory: WorkingMemory,
    private val semanticMemoryDao: SemanticMemoryDao,
    private val proceduralMemoryDao: ProceduralMemoryDao,
    private val messageDao: MessageDao
) {
    /**
     * Слой 3: Сохранение долговременного факта о пользователе
     */
    suspend fun saveFact(key: String, value: String, category: String = "general") = withContext(Dispatchers.IO) {
        semanticMemoryDao.saveMemory(
            SemanticMemoryEntity(
                keyName = key.trim().lowercase(),
                valueText = value.trim(),
                category = category,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Слой 3: Точечный поиск 3-5 наиболее релевантных фактов под текущий запрос
     */
    suspend fun retrieveRelevantMemories(userQuery: String): List<SemanticMemoryEntity> = withContext(Dispatchers.IO) {
        val queryClean = userQuery.lowercase()
        // Ищем совпадения по ключевым словам запроса
        val tokens = queryClean.split(" ", ",", "?", "!").filter { it.length >= 3 }
        val found = mutableSetOf<SemanticMemoryEntity>()

        for (token in tokens) {
            val matches = semanticMemoryDao.searchMemories(token, limit = 3)
            found.addAll(matches)
            if (found.size >= 5) break
        }

        if (found.isEmpty()) {
            // Если явных совпадений нет, берем топ-3 основных профильных факта (имя, предпочтения)
            return@withContext semanticMemoryDao.getRecentMemories(limit = 3)
        }

        return@withContext found.take(5)
    }

    /**
     * Слой 4: Поиск сохраненного пользовательского сценария (Workflow)
     */
    suspend fun findWorkflow(trigger: String): ProceduralMemoryEntity? = withContext(Dispatchers.IO) {
        proceduralMemoryDao.getWorkflowByTrigger(trigger.trim().lowercase())
    }

    /**
     * Слой 4: Сохранение сценария автоматизации
     */
    suspend fun saveWorkflow(trigger: String, actionsJson: String, description: String = "") = withContext(Dispatchers.IO) {
        proceduralMemoryDao.saveWorkflow(
            ProceduralMemoryEntity(
                triggerPhrase = trigger.trim().lowercase(),
                actionsJson = actionsJson,
                description = description
            )
        )
    }

    /**
     * Формирует сжатый блок памяти для вставки в системный промпт модели
     */
    suspend fun buildMemoryContextPrompt(userQuery: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // 1. Слой 1: Working Memory
        val workingContext = workingMemory.getWorkingContextSummary()
        if (workingContext.isNotBlank()) {
            sb.append("$workingContext\n")
        }

        // 2. Слой 3: Semantic Memory (3-5 точных фактов)
        val memories = retrieveRelevantMemories(userQuery)
        if (memories.isNotEmpty()) {
            sb.append("Долговременная память о пользователе:\n")
            memories.forEach { mem ->
                sb.append("- ${mem.keyName}: ${mem.valueText}\n")
            }
        }

        return@withContext sb.toString().trim()
    }
}
