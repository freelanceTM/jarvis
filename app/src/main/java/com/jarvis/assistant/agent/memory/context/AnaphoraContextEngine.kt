package com.jarvis.assistant.agent.memory.context

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anaphora & Multi-Turn Context Resolution Engine
 * 
 * Отслеживает цепочку диалога и разрешает местоимения и неявные ссылки:
 * 1. Распознает местоимения: "ему", "ей", "их", "его", "её", "он", "она", "оно", "они", "там", "туда", "оттуда", "про него", "про неё", "у него", "у неё".
 * 2. Извлекает главные именованные сущности (Subject/Entity) из предыдущих реплик пользователя и ассистента.
 * 3. Переписывает запрос для WebSearchTool, ToolDiscovery и LLM (Query Rewriting):
 *    "Кто президент Франции?" ➔ Ответ: "Эмманюэль Макрон"
 *    След. вопрос: "Сколько ему лет?" ➔ Разрешенный запрос: "Сколько Эмманюэль Макрон лет?"
 */
@Singleton
class AnaphoraContextEngine @Inject constructor() {

    private val pronounPatterns = listOf(
        Regex("(?i)\\b(ему|ей|его|её|ее|их|им|ими|нём|нем|ней|них)\\b"),
        Regex("(?i)\\b(он|она|оно|они)\\b"),
        Regex("(?i)\\b(про него|про неё|про нее|про них|о нем|о нём|о ней|о них)\\b"),
        Regex("(?i)\\b(у него|у неё|у нее|у них|к нему|к ней|к ним)\\b"),
        Regex("(?i)\\b(там|туда|оттуда|в этом месте|в том городе)\\b"),
        Regex("(?i)\\b(этот|эта|это|эти|этого|этой|этих)\\b")
    )

    /**
     * Проверяет, содержит ли запрос местоимения или контекстуальные отсылки
     */
    fun hasContextualPronoun(query: String): Boolean {
        return pronounPatterns.any { it.containsMatchIn(query) }
    }

    /**
     * Разрешает местоимения в запросе, подставляя актуальную сущность из контекста
     */
    fun resolveQuery(query: String, lastEntity: String?): String {
        if (lastEntity.isNullOrBlank()) {
            return query
        }

        var resolved = query.trim()

        // 1. Предложные конструкции "про него / о нем"
        resolved = resolved.replace(Regex("(?i)\\b(про него|про неё|про нее|про них|о нем|о нём|о ней|о них)\\b"), "о $lastEntity")

        // 2. Предложные конструкции "у него / у неё"
        resolved = resolved.replace(Regex("(?i)\\b(у него|у неё|у нее|у них)\\b"), "у $lastEntity")

        // 3. Местоимения "ему / ей / их / его / её"
        resolved = resolved.replace(Regex("(?i)\\b(ему|ей|их|его|её|ее)\\b"), lastEntity)

        // 4. Местоимения "он / она / оно / они"
        resolved = resolved.replace(Regex("(?i)\\b(он|она|оно|они)\\b"), lastEntity)

        // 5. Локационные отсылки "там / туда / оттуда"
        resolved = resolved.replace(Regex("(?i)\\b(там|туда|оттуда)\\b"), "в $lastEntity")

        return resolved.trim()
    }

    /**
     * Автоматически извлекает ключевую именованную сущность из ответа ассистента или вопроса
     */
    fun extractEntity(text: String): String? {
        val clean = text.trim()
        if (clean.length < 3) return null

        // 1. Поиск шаблонных ответов: "Президентом Франции является [Эмманюэль Макрон]", "Столицей ... является [Париж]"
        val isMatch = Regex("(?i)(является|это|зовут|называется)\\s+([А-ЯЁA-Z][а-яёa-z]+(?:\\s+[А-ЯЁA-Z][а-яёa-z]+)?)").find(clean)
        if (isMatch != null && isMatch.groupValues.size >= 3) {
            val candidate = isMatch.groupValues[2].trim().replace(Regex("[.,!?]"), "")
            if (candidate.length in 3..40 && !isStopWord(candidate)) {
                return candidate
            }
        }

        // 2. Поиск слов с заглавной буквы (Имена Собственные: Эмманюэль Макрон, Илон Маск, Париж, Apple)
        val properNouns = Regex("\\b[А-ЯЁA-Z][а-яёa-z]+(?:\\s+[А-ЯЁA-Z][а-яёa-z]+)*\\b").findAll(clean)
            .map { it.value.trim().replace(Regex("[.,!?]"), "") }
            .filter { it.length in 3..40 && !isStopWord(it) }
            .toList()

        if (properNouns.isNotEmpty()) {
            return properNouns.maxByOrNull { it.length }
        }

        return null
    }

    private fun isStopWord(word: String): Boolean {
        val w = word.lowercase()
        return w in listOf(
            "джарвис", "jarvis", "да", "нет", "привет", "здравствуйте", "хорошо",
            "конечно", "ладно", "понял", "январь", "февраль", "март", "апрель",
            "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
            "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"
        )
    }
}
