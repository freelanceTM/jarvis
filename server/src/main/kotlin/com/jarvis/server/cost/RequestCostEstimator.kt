package com.jarvis.server.cost

/**
 * Cost Control (пункт спецификации: Request → Cost estimation → Budget policy
 * → Provider).
 *
 * Класс сложности запроса определяется ДЕТЕРМИНИРОВАННО по его форме
 * (длина промпта, объём истории, requiresWeb) — не моделью и не случайностью.
 *
 * Соответствие классов полосам обработки:
 *
 * ```
 * SIMPLE → по возможности LOCAL на устройстве ($0 cloud; телефонные полосы
 *          LOCAL TOOL / LOCAL AI вообще не доходят до сервера) + здесь:
 *          самый дешёвый провайдер и урезанный бюджет ответа
 * MEDIUM → cheapest acceptable (баланс цены и качества)
 * HARD   → best quality (research / длинный контекст; цена не в score)
 * ```
 */
enum class CostClass {
    SIMPLE,
    MEDIUM,
    HARD
}

/**
 * Чистая оценка стоимости/сложности запроса (Cost estimation).
 *
 * Эвристика по форме запроса — осознанная: классификация без LLM бесплатна
 * и детерминирована; пороги — константы, их легко подкрутить после
 * наблюдений (это тюнинг, а не политика доступа).
 */
object RequestCostEstimator {

    /** Порог «короткая реплика» (SIMPLE): до ~140 символов. */
    const val SIMPLE_PROMPT_MAX_CHARS = 140

    /** Порог «длинный промпт» (HARD): от ~2000 символов. */
    const val HARD_PROMPT_MIN_CHARS = 2_000

    /** Порог «большой контекст» (HARD): суммарная история от ~4000 символов. */
    const val HARD_HISTORY_MIN_CHARS = 4_000

    /**
     * Класс сложности по форме запроса.
     *
     * @param promptChars длина текста запроса
     * @param historyChars суммарная длина истории диалога
     * @param requiresWeb запрос требует актуальных данных из сети (research)
     */
    fun classify(promptChars: Int, historyChars: Int, requiresWeb: Boolean): CostClass = when {
        // Research по определению требует лучшей модели.
        requiresWeb -> CostClass.HARD
        // Большой контекст: длинный промпт или объёмная история.
        promptChars >= HARD_PROMPT_MIN_CHARS -> CostClass.HARD
        historyChars >= HARD_HISTORY_MIN_CHARS -> CostClass.HARD
        // Короткая реплика без контекста: кандидат на самый дешёвый путь
        // (на устройстве — $0; на сервере — самый дешёвый провайдер).
        promptChars <= SIMPLE_PROMPT_MAX_CHARS && historyChars == 0 -> CostClass.SIMPLE
        else -> CostClass.MEDIUM
    }

    /**
     * Бюджет токенов ответа для класса (Budget policy, выходная часть):
     * SIMPLE режется жёстче — короткий ответ не должен платить за длинный.
     * Конфигурационный максимум не превышается никогда.
     */
    fun outputBudget(costClass: CostClass, configuredMaxTokens: Int): Int {
        val configured = configuredMaxTokens.coerceAtLeast(1)
        return when (costClass) {
            CostClass.SIMPLE -> minOf(configured, SIMPLE_MAX_OUTPUT_TOKENS)
            CostClass.MEDIUM, CostClass.HARD -> configured
        }
    }

    /** Капа ответа для SIMPLE-класса. */
    const val SIMPLE_MAX_OUTPUT_TOKENS = 256

    /**
     * Грубая оценка входных токенов (~4 символа/токен для смешанного текста).
     * Только для логов/диагностики — НЕ для биллинга (биллинг считает факту
     * от провайдера, см. CostModel).
     */
    fun approximateInputTokens(promptChars: Int, historyChars: Int, systemChars: Int): Long =
        ((promptChars + historyChars + systemChars) / 4.0).toLong().coerceAtLeast(0)
}
