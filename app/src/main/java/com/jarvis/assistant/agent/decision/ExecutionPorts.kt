package com.jarvis.assistant.agent.decision

import com.jarvis.assistant.agent.planner.ExecutionPlan
import com.jarvis.assistant.core.result.Resource

/**
 * Порты Execution Decision Engine.
 *
 * Это НЕ новые подсистемы: каждый порт — тонкий адаптер над УЖЕ существующим
 * механизмом проекта (см. реализации в этом же пакете). Порты нужны ровно для
 * двух вещей:
 *  1. decision engine не должен знать деталей выполнения (пункт 10 ТЗ);
 *  2. пути выполнения можно проверить unit-тестами без Android/сети.
 */

/**
 * Локальный (офлайн) слой обработки.
 *
 * ЧЕСТНАЯ ФОРМУЛИРОВКА: нейросетевой локальной LLM в проекте нет
 * (см. README и docs/ANDROID_CAPABILITIES.md — локальных моделей и embeddings
 * не подключено). Реальный локальный слой — процедурная память
 * ([com.jarvis.assistant.agent.memory.procedural.WorkflowExecutor]):
 * сохранённые пользовательские сценарии, выполняемые полностью офлайн.
 *
 * Поэтому [hasWebCapability] == false: при `requiresWeb == true` decision
 * engine обязан пропустить локальный путь, а не позволить ему изобразить успех.
 */
interface LocalAiExecutor {

    /** Способен ли локальный слой получать актуальные данные из сети. */
    val hasWebCapability: Boolean

    suspend fun tryHandle(request: ExecutionRequest): LocalAiOutcome
}

/** Исход попытки локальной обработки. */
sealed class LocalAiOutcome {

    /** Локальный слой уверенно обработал запрос. */
    data class Handled(val text: String) : LocalAiOutcome()

    /** Локальный слой не берётся за запрос — нужен следующий уровень. */
    data object Uncertain : LocalAiOutcome()

    /** Локальный слой взялся, но выполнение не удалось (это не исключение). */
    data class Failed(val message: String) : LocalAiOutcome()
}

/**
 * Облачный AI. Адаптер над существующим
 * [com.jarvis.assistant.domain.repository.AIRepository] → `UniversalAIClient`.
 * Новый HTTP/LLM-клиент на этом этапе НЕ создаётся.
 */
interface CloudAiExecutor {

    /** Доступна ли сеть прямо сейчас (существующий NetworkMonitor). */
    fun isAvailable(): Boolean

    suspend fun complete(request: ExecutionRequest): Resource<String>
}

/**
 * Агент. Адаптер над существующими
 * [com.jarvis.assistant.agent.planner.CognitivePlanner] и
 * [com.jarvis.assistant.agent.engine.AgentCognitiveLoop].
 *
 * Сам AgentCognitiveLoop не переписан: адаптер только маппит
 * `PlanExecutionSummary` → [ExecutionResult] (пункт 9 ТЗ).
 */
interface AgentExecutor {

    /**
     * @return план, если запрос действительно многошаговый, иначе null.
     * @param llmRawOutput сырой ответ облачной модели с tool_calls (если есть).
     */
    fun planFor(request: ExecutionRequest, llmRawOutput: String? = null): ExecutionPlan?

    suspend fun run(plan: ExecutionPlan): ExecutionResult
}
