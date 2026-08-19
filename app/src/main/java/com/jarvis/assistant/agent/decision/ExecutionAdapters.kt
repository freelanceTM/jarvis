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
import kotlinx.coroutines.delay
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
 * CLOUD AI = существующий [AIRepository] (UniversalAIClient: OpenAI/Groq/
 * OpenRouter/Gemini) + Tool Discovery-промпт из [ToolRegistry].
 *
 * Один автоматический повтор через 2 секунды сохранён из AgentPipeline —
 * это существующее поведение, а не новый retry-framework.
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
        const val RETRY_DELAY_MS = 2000L
    }

    override fun isAvailable(): Boolean = networkMonitor.isCurrentlyOnline()

    override suspend fun complete(request: ExecutionRequest): Resource<String> {
        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()
        val targetedToolsPrompt = toolRegistry.buildTargetedSystemPrompt(request.text)

        val fullSystemPrompt = buildString {
            append(baseSystemPrompt)
            if (targetedToolsPrompt.isNotBlank()) {
                append("\n\n")
                append(targetedToolsPrompt)
            }
        }

        val first = aiRepository.generateResponse(
            prompt = request.text,
            systemPrompt = fullSystemPrompt,
            history = request.history
        )
        if (first !is Resource.Error) return first

        Log.w(TAG, "Cloud request failed, single retry in ${RETRY_DELAY_MS}ms")
        delay(RETRY_DELAY_MS)

        return aiRepository.generateResponse(
            prompt = request.text,
            systemPrompt = fullSystemPrompt,
            history = request.history
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
