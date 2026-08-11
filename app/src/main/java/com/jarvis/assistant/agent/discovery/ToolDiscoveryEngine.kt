package com.jarvis.assistant.agent.discovery

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.memory.vector.VectorEmbeddingEngine
import com.jarvis.assistant.agent.model.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool Discovery Engine (Семантический фильтр инструментов)
 * Динамически подбирает только 3–5 самых релевантных инструментов под конкретный запрос пользователя.
 * Предотвращает раздувание промпта, галлюцинации моделей и снижает задержку LLM.
 */
@Singleton
class ToolDiscoveryEngine @Inject constructor(
    private val vectorEngine: VectorEmbeddingEngine
) {
    /**
     * Выбирает топ релевантных инструментов для запроса
     */
    fun discoverTools(
        userQuery: String,
        allTools: List<JarvisTool>,
        maxTools: Int = 4
    ): List<JarvisTool> {
        val q = userQuery.lowercase().trim()
        if (q.isEmpty() || allTools.isEmpty()) return emptyList()

        // 1. Проверяем, является ли запрос чистым теоретическим вопросом (без действий с телефоном)
        val isPureConversation = q.startsWith("почему") ||
                q.startsWith("объясни") ||
                q.startsWith("расскажи о") ||
                q.startsWith("что такое") && !q.contains("телефон") && !q.contains("батаре") ||
                q.startsWith("кто такой") ||
                q.startsWith("как работает")

        // 2. Векторный семантический поиск по описаниям инструментов
        val queryVector = vectorEngine.createEmbedding(q)
        val scoredTools = mutableListOf<Pair<JarvisTool, Float>>()

        for (tool in allTools) {
            val toolText = "${tool.name} ${tool.description} ${tool.category.displayName}".lowercase()
            val toolVector = vectorEngine.createEmbedding(toolText)
            val semanticScore = vectorEngine.computeCosineSimilarity(queryVector, toolVector)

            // Лексический буст за прямое совпадение ключевых слов
            var lexicalBoost = 0f
            val words = q.split(Regex("[\\s,?.!]+")).filter { it.length >= 3 }
            for (word in words) {
                if (toolText.contains(word)) {
                    lexicalBoost += 0.35f
                }
            }

            val totalScore = (semanticScore * 0.5f) + (lexicalBoost.coerceAtMost(0.5f))
            scoredTools.add(tool to totalScore)
        }

        // 3. Отбираем инструменты, преодолевшие порог релевантности
        val threshold = if (isPureConversation) 0.45f else 0.25f
        val filtered = scoredTools
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxTools)

        return filtered
    }

    /**
     * Генерирует компактный системный промпт ТОЛЬКО для отобранных инструментов
     */
    fun buildTargetedToolsPrompt(discoveredTools: List<JarvisTool>): String {
        if (discoveredTools.isEmpty()) {
            return "Отвечай кратко и емко (1-2 предложения) живым разговорным языком."
        }

        val sb = StringBuilder()
        sb.append("Отобранные системные инструменты для текущей задачи:\n")

        discoveredTools.forEach { tool ->
            val offline = if (tool.isOffline) "[ОФЛАЙН]" else "[ОНЛАЙН]"
            sb.append("- \"${tool.toolId}\" $offline (Риск: ${tool.riskLevel}): ${tool.description}. Схема: ${tool.parametersSchema}\n")
        }

        sb.append("\nЕсли требуется действие, верни JSON:\n")
        sb.append("{\"tool_calls\": [{\"tool\": \"идентификатор_инструмента\", \"arguments\": { ... }}]}\n")
        sb.append("Если действие не требуется — отвечай кратко в 1-2 предложения.\n")

        return sb.toString()
    }
}
