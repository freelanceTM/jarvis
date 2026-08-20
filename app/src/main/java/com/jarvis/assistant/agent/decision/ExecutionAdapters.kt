package com.jarvis.assistant.agent.decision

import android.util.Log
import com.jarvis.assistant.agent.engine.AgentCognitiveLoop
import com.jarvis.assistant.agent.planner.CognitivePlanner
import com.jarvis.assistant.agent.planner.ExecutionPlan
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адаптеры путей выполнения над существующими компонентами.
 *
 * Ни один из адаптеров не содержит новой бизнес-логики: они дословно повторяют
 * то, что раньше делал [com.jarvis.assistant.agent.pipeline.AgentPipeline],
 * но за единым контрактом [ExecutionRequest] / [ExecutionResult].
 */

/**
 * CLOUD AI = JARVIS API (Этап 3).
 *
 * ```
 * CloudAiExecutor → AIRepository → JarvisApiAiClient → JARVIS API
 *                                        → AI Router → Provider Manager
 * ```
 *
 * Клиент НЕ знает, какой провайдер выполнит запрос, и не хранит их ключи.
 * Выбор провайдера, fallback между ними, retry и rate limiting — на сервере.
 *
 * Локальный повтор здесь СНЯТ: сервер уже делает controlled retry и fallback
 * по нескольким провайдерам, поэтому клиентский повтор только удваивал бы
 * нагрузку и расходовал лимит пользователя.
 */
@Singleton
class RepositoryCloudAiExecutor @Inject constructor(
    private val aiRepository: AIRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry,
    private val networkMonitor: NetworkMonitor
) : CloudAiExecutor {

    private companion object {
        const val TAG = "CloudAiExecutor"
    }

    override fun isAvailable(): Boolean = networkMonitor.isCurrentlyOnline()

    override suspend fun complete(request: ExecutionRequest): Resource<String> {
        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()

        // Tool Discovery остаётся на клиенте: сервер не знает, какие
        // инструменты доступны на КОНКРЕТНОМ устройстве.
        val targetedToolsPrompt = toolRegistry.buildTargetedSystemPrompt(request.text)

        val fullSystemPrompt = buildString {
            append(baseSystemPrompt)
            if (targetedToolsPrompt.isNotBlank()) {
                append("\n\n")
                append(targetedToolsPrompt)
            }
        }

        Log.d(TAG, "cloud request → JARVIS API | source=${request.source} | privacy=${request.effectivePrivacyLevel}")

        // Передаём контекст решения: сервер применит privacy-политику как
        // вторую линию защиты, даже если клиент ошибётся.
        return aiRepository.generateResponse(
            prompt = request.text,
            systemPrompt = fullSystemPrompt,
            source = request.source.name,
            privacyLevel = request.effectivePrivacyLevel.name,
            requiresWeb = request.requiresWeb
        )
    }
}

/**
 * AGENT = существующие [CognitivePlanner] + [AgentCognitiveLoop].
 *
 * Адаптер (пункт 9 ТЗ): маппит `PlanExecutionSummary` → [ExecutionResult],
 * не изменяя сам cognitive loop и его существующих consumers.
 */
@Singleton
class CognitiveAgentExecutor @Inject constructor(
    private val cognitivePlanner: CognitivePlanner,
    private val agentCognitiveLoop: AgentCognitiveLoop
) : AgentExecutor {

    override fun planFor(request: ExecutionRequest, llmRawOutput: String?): ExecutionPlan? =
        cognitivePlanner.planForGoal(request.text, llmRawOutput)?.takeIf { it.steps.isNotEmpty() }

    override suspend fun run(plan: ExecutionPlan): ExecutionResult {
        val summary = agentCognitiveLoop.runPlan(plan)

        summary.pendingConfirmation?.let { (call, prompt) ->
            return ExecutionResult.ConfirmationRequired(toolCall = call, promptMessage = prompt)
        }

        return ExecutionResult.Success(
            text = summary.finalVoiceSummary,
            executionType = ExecutionType.AGENT,
            metadata = mapOf(
                "plan_goal" to plan.goal,
                "steps" to plan.steps.size.toString(),
                "all_successful" to summary.isAllSuccessful.toString()
            )
        )
    }
}
