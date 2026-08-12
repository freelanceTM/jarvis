package com.jarvis.assistant.agent.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRouter @Inject constructor() {

    /**
     * Анализирует намерение и автоматически определяет TaskType и оптимальный уровень модели (Tier)
     */
    fun routeTask(userPrompt: String): TaskRoutingDecision {
        val q = userPrompt.lowercase().trim()

        // 1. Локальные мгновенные действия (Fast Local / Tier 0)
        val isFastLocal = q.contains("фонарик") ||
                q.contains("громк") ||
                q.contains("звук") ||
                q.contains("тише") ||
                q.contains("громче") ||
                q.contains("батаре") ||
                q.contains("заряд") ||
                q.contains("время") ||
                q.contains("число") ||
                q.contains("стоп") ||
                q == "привет" ||
                q == "ты тут"

        if (isFastLocal && !q.contains("почему") && !q.contains("объясни")) {
            return TaskRoutingDecision(
                taskType = TaskType.FAST_LOCAL,
                tier = ModelTier.TIER_0_LOCAL,
                targetModelId = "local-nlu",
                requiresWebSearch = false,
                reason = "Мгновенное локальное действие без обращения к сети"
            )
        }

        // 2. Исполнение инструментов устройства (Tool Execution)
        val isTool = q.startsWith("позвони") ||
                q.startsWith("набери") ||
                q.startsWith("открой") ||
                q.startsWith("запусти") ||
                q.startsWith("отправь смс") ||
                q.startsWith("напиши смс") ||
                q.startsWith("маршрут в") ||
                q.startsWith("навигатор в") ||
                q.contains("скриншот") ||
                q.contains("не беспокоить") ||
                q.startsWith("забудь")

        if (isTool) {
            return TaskRoutingDecision(
                taskType = TaskType.TOOL_EXECUTION,
                tier = ModelTier.TIER_1_FAST,
                targetModelId = "llama-3.3-70b-versatile",
                requiresWebSearch = false,
                reason = "Выполнение системного инструмента Android"
            )
        }

        // 3. Поиск в реальном времени / OSINT (Tier 3 - Search)
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
                taskType = TaskType.AI_CONVERSATION,
                tier = ModelTier.TIER_3_SEARCH_OSINT,
                targetModelId = "meta-llama/llama-3.3-70b-instruct:free",
                requiresWebSearch = true,
                reason = "Требуется поиск актуальных данных в реальном времени"
            )
        }

        // 4. Сложные многошаговые планы / аналитика / код (Complex Plan / Tier 2 - Reasoning)
        val isComplex = q.contains("я ухожу") ||
                q.contains("выхожу из дома") ||
                q.contains("я пришел") ||
                q.contains("ночной режим") ||
                q.contains("подготовь ко сну") ||
                q.contains("проанализируй") ||
                q.contains("бизнес-план") ||
                q.contains("бизнес план") ||
                q.contains("бизнес") ||
                q.contains("напиши код") ||
                q.contains("сравни") ||
                q.contains("почему") && q.length > 40 ||
                q.contains("стратеги") ||
                q.contains("инвестиц") ||
                q.contains("архитектур") ||
                q.length > 120

        if (isComplex) {
            return TaskRoutingDecision(
                taskType = TaskType.COMPLEX_PLAN,
                tier = ModelTier.TIER_2_REASONING,
                targetModelId = "gpt-4o-mini",
                requiresWebSearch = false,
                reason = "Сложная аналитическая задача или многошаговый сценарий"
            )
        }

        // 5. Обычный быстрый голосовой диалог (AI Conversation / Tier 1 - Fast LLM)
        return TaskRoutingDecision(
            taskType = TaskType.AI_CONVERSATION,
            tier = ModelTier.TIER_1_FAST,
            targetModelId = "llama-3.3-70b-versatile",
            requiresWebSearch = false,
            reason = "Быстрый разговорный диалог"
        )
    }
}
