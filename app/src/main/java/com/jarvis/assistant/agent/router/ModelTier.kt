package com.jarvis.assistant.agent.router

enum class ModelTier(val description: String, val typicalLatencyMs: Int) {
    TIER_0_LOCAL("Локальное мгновенное действие Android (Zero-LLM)", 10),
    TIER_1_FAST("Быстрая голосовая модель (Groq / Gemini Flash)", 200),
    TIER_2_REASONING("Глубокая модель рассуждений (GPT-4o / Claude / DeepSeek)", 1500),
    TIER_3_SEARCH_OSINT("Поиск в реальном времени и OSINT-анализ", 2000)
}

data class TaskRoutingDecision(
    val tier: ModelTier,
    val targetModelId: String,
    val requiresWebSearch: Boolean = false,
    val reason: String = ""
)
