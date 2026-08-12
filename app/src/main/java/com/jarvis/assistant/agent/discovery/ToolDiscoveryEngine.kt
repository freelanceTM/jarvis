package com.jarvis.assistant.agent.discovery

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.memory.vector.VectorEmbeddingEngine
import com.jarvis.assistant.agent.model.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Tool Discovery Engine 2.0 (Гибридный семантический фильтр)
 * Использует:
 * 1. 64-D On-Device Cosine Vector Similarity
 * 2. BM25 / TF-IDF частотное ранжирование с весами терминов
 * 3. Расширенный SynonymDictionary (RU + EN)
 * 4. Fuzzy Levenshtein Matching для устойчивости к ошибкам распознавания речи
 * Время выполнения: < 10 мс.
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

        // 2. Векторный семантический поиск
        val queryVector = vectorEngine.createEmbedding(q)
        val queryWords = q.split(Regex("[\\s,?.!]+")).filter { it.length >= 2 }

        // 3. Подготовка корпуса документов для BM25
        val toolCorpus = allTools.map { tool ->
            val text = "${tool.toolId} ${tool.name} ${tool.description} ${tool.category.displayName}".lowercase()
            val tokens = text.split(Regex("[\\s,?.!_:-]+")).filter { it.length >= 2 }
            tool to tokens
        }

        val avgDocLen = toolCorpus.map { it.second.size }.average().coerceAtLeast(1.0)
        val totalDocs = toolCorpus.size.toDouble()

        val scoredTools = mutableListOf<Pair<JarvisTool, Float>>()

        for ((tool, docTokens) in toolCorpus) {
            val toolText = docTokens.joinToString(" ")

            // A. Vector Cosine Similarity
            val toolVector = vectorEngine.createEmbedding(toolText)
            val semanticScore = vectorEngine.computeCosineSimilarity(queryVector, toolVector)

            // B. BM25 / TF-IDF Scoring
            var bm25Score = 0f
            val docLen = docTokens.size.toDouble()
            val k1 = 1.2
            val b = 0.75

            for (word in queryWords) {
                val matchingDocsCount = toolCorpus.count { (_, tokens) -> tokens.any { it.contains(word) } }.coerceAtLeast(1)
                val idf = ln(1.0 + (totalDocs - matchingDocsCount + 0.5) / (matchingDocsCount + 0.5)).coerceAtLeast(0.1)

                val termFrequency = docTokens.count { it.contains(word) }.toDouble()
                if (termFrequency > 0) {
                    val tf = (termFrequency * (k1 + 1.0)) / (termFrequency + k1 * (1.0 - b + b * (docLen / avgDocLen)))
                    bm25Score += (idf * tf).toFloat()
                }
            }

            // C. Synonym Boost
            var synonymBoost = 0f
            for (word in queryWords) {
                val synonyms = SynonymDictionary.getSynonyms(word)
                for (syn in synonyms) {
                    if (toolText.contains(syn)) {
                        synonymBoost += 0.35f
                        break
                    }
                }
            }

            // D. Fuzzy Levenshtein Matching (для опечаток голосового ввода)
            var fuzzyBoost = 0f
            for (word in queryWords) {
                if (word.length >= 4) {
                    for (token in docTokens) {
                        if (token.length >= 4) {
                            val similarity = calculateFuzzySimilarity(word, token)
                            if (similarity >= 0.75f) {
                                fuzzyBoost = max(fuzzyBoost, similarity * 0.25f)
                            }
                        }
                    }
                }
            }

            // E. Гибридный итоговый балл
            val normalizedBm25 = (bm25Score / 3.0f).coerceAtMost(0.5f)
            val totalScore = (semanticScore * 0.35f) +
                    (normalizedBm25 * 0.30f) +
                    (synonymBoost.coerceAtMost(0.40f) * 0.25f) +
                    (fuzzyBoost * 0.10f)

            scoredTools.add(tool to totalScore)
        }

        // 4. Отбираем инструменты, преодолевшие порог релевантности
        val threshold = if (isPureConversation) 0.45f else 0.22f
        val filtered = scoredTools
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxTools)

        return filtered
    }

    /**
     * Вычисляет процент схожести двух слов на основе расстояния Левенштейна (0.0 - 1.0)
     */
    fun calculateFuzzySimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        val distance = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0f
        return (1.0f - (distance.toFloat() / maxLen.toFloat())).coerceIn(0.0f, 1.0f)
    }

    /**
     * Алгоритм расстояния Левенштейна (Levenshtein Distance)
     */
    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length

        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1) { 0 }

        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1

                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLen]
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
