package com.jarvis.assistant.ai

/**
 * Чистая политика AI-ключей и fallback-решений (без сети — unit-тестируема).
 *
 * Пункт аудита #3 (CRITICAL): ключ пользователя НЕ должен уходить в чужой
 * сервис. Fallback между провайдерами допустим только с ключом, который
 * реально подходит этому провайдеру:
 *
 *   OpenRouter  → ключ обязан начинаться с "sk-or-"
 *   Groq        → "gsk_"
 *   OpenAI      → "sk-"
 *   Gemini      → всё остальное (обычно "AIza...")
 *
 * Если ключ не подходит провайдеру fallback'а — fallback НЕ выполняется,
 * пользователю показывается понятное объяснение.
 */
object AiKeyPolicy {

    const val OPENROUTER_PREFIX = "sk-or-"
    const val GROQ_PREFIX = "gsk_"
    const val OPENAI_PREFIX = "sk-"

    fun isOpenRouterKey(key: String): Boolean = key.startsWith(OPENROUTER_PREFIX)

    fun isGroqKey(key: String): Boolean = key.startsWith(GROQ_PREFIX)

    fun isOpenAiKey(key: String): Boolean = key.startsWith(OPENAI_PREFIX)

    /** Ключ считается Gemini-совместимым, если не подходит ни одному OpenAI-совместимому сервису. */
    fun isGeminiKey(key: String): Boolean =
        !isOpenRouterKey(key) && !isGroqKey(key) && !isOpenAiKey(key)

    /**
     * Разрешён ли fallback к OpenRouter с данным ключом.
     * Единственное допустимое условие — ключ реально OpenRouter-совместимый.
     */
    fun canFallbackToOpenRouter(apiKey: String): Boolean = isOpenRouterKey(apiKey)

    /**
     * Понятное сообщение пользователю при geo-block/отказе Gemini.
     * Упоминает конкретные действия, не раскрывая секретов.
     */
    fun geminiBlockedMessage(httpCode: Int): String = when (httpCode) {
        400, 403 ->
            "Google Gemini заблокирован в вашем регионе (HTTP $httpCode). " +
                "Чтобы JARVIS работал, введите в настройках бесплатный ключ OpenRouter (sk-or-...) " +
                "или Groq (gsk_...)."
        429 ->
            "Лимит запросов Gemini исчерпан. Пожалуйста, подождите 30 секунд."
        else ->
            "Ошибка сервера AI ($httpCode)."
    }
}
