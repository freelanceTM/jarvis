package com.jarvis.assistant.agent.router

/**
 * Типы задач в маршрутизаторе JARVIS
 */
enum class TaskType(val description: String) {
    FAST_LOCAL("Мгновенная локальная команда устройства"),
    TOOL_EXECUTION("Исполнение системного инструмента Android"),
    AI_CONVERSATION("Быстрый голосовой диалог с LLM"),
    COMPLEX_PLAN("Сложная аналитическая задача или многошаговый план")
}

/**
 * Уровни моделей AI (Tier 0-3)
 */
enum class ModelTier(val description: String, val typicalLatencyMs: Int) {
    TIER_0_LOCAL("Локальное мгновенное действие Android (Zero-LLM)", 10),
    TIER_1_FAST("Быстрая голосовая модель (Groq / Gemini Flash)", 200),
    TIER_2_REASONING("Глубокая модель рассуждений (GPT-4o / Claude / DeepSeek)", 1500),
    TIER_3_SEARCH_OSINT("Поиск в реальном времени и OSINT-анализ", 2000)
}

/**
 * Решение маршрутизатора задач
 */
data class TaskRoutingDecision(
    val taskType: TaskType = TaskType.AI_CONVERSATION,
    val tier: ModelTier = ModelTier.TIER_1_FAST,
    val targetModelId: String = "llama-3.3-70b-versatile",
    val requiresWebSearch: Boolean = false,
    val reason: String = ""
)
