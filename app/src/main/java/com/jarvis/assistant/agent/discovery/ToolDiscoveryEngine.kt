package com.jarvis.assistant.agent.discovery

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Tool Discovery Engine 2.0 (Высокоточный гибридный семантический фильтр)
 * Использует:
 * 1. BM25 / TF-IDF частотное взвешивание с обратной документной частотой (IDF) — 45% веса
 * 2. Расширенный SynonymDictionary (RU + EN) — 35% веса
 * 3. Fuzzy Levenshtein Matching для устойчивости к опечаткам — 15% веса
 * 4. Концептуальный Cosine Similarity — 5% веса
 * Время выполнения: < 5 мс.
 */
@Singleton
class ToolDiscoveryEngine @Inject constructor(
    private val featureEngine: SemanticTextMatcher
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

        // 1. Глубокий анализ: является ли запрос чистой беседой/теорией (без действий с телефоном)
        val isPureConversation = isConversationalQuery(q)

        // 2. Векторный семантический поиск
        val queryVector = featureEngine.featurize(q)
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

        // Пункт аудита #15: один переиспользуемый буфер для векторов инструментов —
        // queryVector живёт отдельно (выделен выше), его не трогаем.
        val toolVectorBuffer = FloatArray(SemanticTextMatcher.VECTOR_DIM)

        for ((tool, docTokens) in toolCorpus) {
            val toolText = docTokens.joinToString(" ")

            // A. BM25 / TF-IDF Scoring (Основной статистический компонент)
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

            // B. Synonym Boost (Семантическое попадание по словарю действий)
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

            // C. Fuzzy Levenshtein Matching (Устойчивость к голосовым опечаткам)
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

            // D. Vector Cosine Similarity (Фоновый семантический компонент)
            // Пункт аудита #15: zero-аллокация — заполняем переиспользуемый буфер.
            val toolVector = featureEngine.featurizeInto(toolText, toolVectorBuffer)
            val semanticScore = featureEngine.computeCosineSimilarity(queryVector, toolVector)

            // Защита от ложных срабатываний: если нет ни одного лексического/синонимического/fuzzy совпадения -> скор 0
            val hasExplicitMatch = (bm25Score > 0f || synonymBoost > 0f || fuzzyBoost > 0f)
            if (!hasExplicitMatch) {
                continue
            }

            // E. Взвешенный итоговый балл (BM25: 45%, Синонимы: 35%, Fuzzy: 15%, Вектор: 5%)
            val normalizedBm25 = (bm25Score / 2.0f).coerceAtMost(1.0f)
            val normalizedSynonym = (synonymBoost / 0.35f).coerceAtMost(1.0f)
            val normalizedFuzzy = (fuzzyBoost / 0.25f).coerceAtMost(1.0f)

            val totalScore = (normalizedBm25 * 0.45f) +
                    (normalizedSynonym * 0.35f) +
                    (normalizedFuzzy * 0.15f) +
                    (semanticScore * 0.05f)

            scoredTools.add(tool to totalScore)
        }

        // 4. Отбираем инструменты, преодолевшие строгий порог релевантности
        val threshold = if (isPureConversation) 0.55f else 0.25f
        val filtered = scoredTools
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxTools)

        return filtered
    }

    private fun isConversationalQuery(query: String): Boolean {
        val q = query.lowercase().trim()

        // Если запрос содержит глаголы/команды управления устройством -> это НЕ чистая беседа
        val hasDeviceAction = q.contains("включи") || q.contains("выключи") || q.contains("погаси") ||
                q.contains("зажги") || q.contains("открой") || q.contains("запусти") ||
                q.contains("позвони") || q.contains("набери") || q.contains("отправь") ||
                q.contains("напиши") || q.contains("поставь") || q.contains("громк") ||
                q.contains("звук") || q.contains("тиш") || q.contains("тише") || q.contains("потише") ||
                q.contains("громче") || q.contains("погромче") || q.contains("убав") || q.contains("прибав") ||
                q.contains("фонарик") || q.contains("батаре") ||
                q.contains("заряд") || q.contains("скриншот") || q.contains("блютуз") ||
                q.contains("вайфай") || q.contains("маршрут") || q.contains("навигатор") ||
                q.contains("экран") || q.contains("кликни") || q.contains("нажми") ||
                q.contains("запомни") || q.contains("забудь")

        if (hasDeviceAction) return false

        // Вопросительные/энциклопедические/генеративные запросы
        return q.startsWith("почему") ||
                q.startsWith("зачем") ||
                q.startsWith("объясни") ||
                q.startsWith("расскажи") ||
                q.startsWith("что такое") ||
                q.startsWith("кто такой") ||
                q.startsWith("кто такая") ||
                q.startsWith("в чем разница") ||
                q.startsWith("как работает") ||
                q.startsWith("как приготовить") ||
                q.startsWith("переведи") ||
                q.startsWith("напиши стих") ||
                q.startsWith("придумай") ||
                q.startsWith("посоветуй фильм") ||
                q.startsWith("скажи") ||
                q.contains("смысл жизни")
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
