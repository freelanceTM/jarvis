package com.jarvis.assistant.agent.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRouter @Inject constructor() {

    /**
     * Анализирует намерение и автоматически выбирает оптимальный уровень модели (Tier)
     */
    fun routeTask(userPrompt: String): TaskRoutingDecision {
        val q = userPrompt.lowercase().trim()

        // 1. Проверка на поиск в реальном времени / OSINT (Tier 3)
        val isSearch = q.contains("найди в интернете") ||
                q.contains("новости") ||
                q.contains("курс доллара") ||
                q.contains("курс биткоина") ||
                q.contains("погода на завтра") ||
                q.contains("кто такой") ||
                q.contains("найди информацию о") ||
                q.contains("погугли") ||
                q.contains("пробей username") ||
                q.contains("osint")

        if (isSearch) {
            return TaskRoutingDecision(
                tier = ModelTier.TIER_3_SEARCH_OSINT,
                targetModelId = "meta-llama/llama-3.3-70b-instruct:free",
                requiresWebSearch = true,
                reason = "Требуется поиск актуальных данных в реальном времени"
            )
        }

        // 2. Проверка на сложное рассуждение, аналитику, код, сравнение (Tier 2 - Reasoning)
        val isComplex = q.contains("проанализируй") ||
                q.contains("составь бизнес-план") ||
                q.contains("напиши код") ||
                q.contains("сравни") ||
                q.contains("почему") && q.length > 40 ||
                q.contains("стратеги") ||
                q.contains("инвестиц") ||
                q.contains("архитектур") ||
                q.length > 120

        if (isComplex) {
            return TaskRoutingDecision(
                tier = ModelTier.TIER_2_REASONING,
                targetModelId = "gpt-4o-mini", // или claude-3-5-sonnet
                requiresWebSearch = false,
                reason = "Сложная аналитическая задача, требующая глубокого мышления"
            )
        }

        // 3. Быстрый диалог / голосовые команды (Tier 1 - Fast LLM)
        return TaskRoutingDecision(
            tier = ModelTier.TIER_1_FAST,
            targetModelId = "llama-3.3-70b-versatile",
            requiresWebSearch = false,
            reason = "Быстрый разговорный диалог"
        )
    }
}
