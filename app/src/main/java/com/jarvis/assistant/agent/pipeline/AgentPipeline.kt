package com.jarvis.assistant.agent.pipeline

import com.jarvis.assistant.agent.engine.AgentCognitiveLoop
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.fast.FastRouteResult
import com.jarvis.assistant.agent.memory.procedural.WorkflowExecutor
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.planner.CognitivePlanner
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.router.TaskRouter
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AgentPipeline — единый конвейер JARVIS (ядро агента).
 *
 * ```
 *                JARVIS
 *                   │
 *          FastCommandRouter        TIER 0: быстрый офлайн-роутинг (<10 мс)
 *                   │
 *          Agent Cognitive Loop     TIER 1: PLAN → EXECUTE → OBSERVE → VERIFY → REPLAN
 *                   │
 *             PLAN / REPLAN          (CognitivePlanner)
 *                   │
 *           Tool Discovery           (ToolRegistry.discoverRelevantTools)
 *                   │
 *            Tool Executor
 *                   │
 *             Observation            (AgentObservationEngine)
 *                   │
 *                VERIFY
 *                   │
 *                SUCCESS
 * ```
 *
 * Порядок фаз:
 *  1. FastCommandRouter — локальная команда → выполнение + Observation;
 *  2. CognitivePlanner → AgentCognitiveLoop (сценарные планы, содержит
 *     OBSERVE → VERIFY → REPLAN, Tool Discovery для LLM);
 *  3. Procedural Memory — пользовательские макросы;
 *  4. LLM Brain + Tool Discovery — AI-планы и свободный диалог.
 *
 * Pipeline возвращает типизированный [PromptExecutionResult], а «обвязка»
 * (сохранение сообщений, память, анафора) остаётся в вызывающем слое.
 */
@Singleton
class AgentPipeline @Inject constructor(
    private val fastCommandRouter: FastCommandRouter,
    private val cognitivePlanner: CognitivePlanner,
    private val agentCognitiveLoop: AgentCognitiveLoop,
    private val workflowExecutor: WorkflowExecutor,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val taskRouter: TaskRouter,
    private val aiRepository: AIRepository,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val workingMemory: WorkingMemory
) {

    suspend fun process(query: String, history: List<com.jarvis.assistant.domain.models.Message> = emptyList()): Resource<PromptExecutionResult> {
        // =============================================================
        // TIER 0: FAST BRAIN (локальный NLU, <10 мс, 100% офлайн)
        // =============================================================
        val fastResult = fastCommandRouter.route(query)
        if (fastResult is FastRouteResult.HandledLocally) {
            var voiceAnswer = fastResult.immediateVoiceResponse

            if (fastResult.toolCall != null) {
                val executionResult = toolExecutor.execute(fastResult.toolCall)
                if (executionResult.status == ToolExecutionStatus.REQUIRES_USER_CONFIRMATION) {
                    return Resource.Success(
                        PromptExecutionResult.ConfirmationRequired(
                            toolCall = fastResult.toolCall,
                            promptMessage = executionResult.summary
                        )
                    )
                }
                // Никакого fake success: оптимистичная фраза роутера заменяется
                // реальным итогом, если Android заблокировал действие.
                voiceAnswer = when {
                    executionResult.isSuccess -> "${executionResult.summary}, сэр."
                    executionResult.isBlockedByAndroid -> executionResult.summary
                    else -> executionResult.summary
                }
                workingMemory.setLastAction(fastResult.toolCall.toolId)
            }

            return Resource.Success(PromptExecutionResult.DirectAnswer(voiceAnswer))
        }

        // =============================================================
        // TIER 1: ДИНАМИЧЕСКИЙ ПЛАНИРОВЩИК (PLAN → EXECUTE → OBSERVE → VERIFY → REPLAN)
        // =============================================================
        val dynamicPlan = cognitivePlanner.planForGoal(query)
        if (dynamicPlan != null) {
            val planSummary = agentCognitiveLoop.runPlan(dynamicPlan)

            if (planSummary.pendingConfirmation != null) {
                val (call, promptMsg) = planSummary.pendingConfirmation
                return Resource.Success(
                    PromptExecutionResult.ConfirmationRequired(
                        toolCall = call,
                        promptMessage = promptMsg
                    )
                )
            }

            return Resource.Success(PromptExecutionResult.DirectAnswer(planSummary.finalVoiceSummary))
        }

        // =============================================================
        // TIER 2: PROCEDURAL MEMORY (пользовательские макросы)
        // =============================================================
        val workflowResult = workflowExecutor.tryExecuteWorkflow(query)
        if (workflowResult != null) {
            val voiceResponse = when {
                workflowResult.isSuccess -> "${workflowResult.summary}, сэр."
                else -> workflowResult.summary
            }
            return Resource.Success(PromptExecutionResult.DirectAnswer(voiceResponse))
        }

        // =============================================================
        // TIER 3: AI BRAIN + TOOL DISCOVERY (сеть)
        // =============================================================
        if (!networkMonitor.isCurrentlyOnline()) {
            val offlineMsg = "Нет подключения к интернету. Локальные команды (фонарик, звук, батарея, приложения, память) работают офлайн."
            return Resource.Error(java.io.IOException("Network offline"), offlineMsg)
        }

        taskRouter.routeTask(query)
        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()

        // Динамический Tool Discovery: только 2-4 релевантных инструмента.
        val targetedToolsPrompt = toolRegistry.buildTargetedSystemPrompt(query)

        val fullSystemPrompt = buildString {
            append(baseSystemPrompt)
            if (targetedToolsPrompt.isNotBlank()) {
                append("\n\n")
                append(targetedToolsPrompt)
            }
        }

        val aiResult = aiRepository.generateResponse(
            prompt = query,
            systemPrompt = fullSystemPrompt,
            history = history
        )

        if (aiResult is Resource.Success) {
            val rawOutput = aiResult.data.trim()

            val llmPlan = cognitivePlanner.planForGoal(query, rawOutput)
            if (llmPlan != null && llmPlan.steps.isNotEmpty()) {
                val loopSummary = agentCognitiveLoop.runPlan(llmPlan)

                if (loopSummary.pendingConfirmation != null) {
                    val (call, promptMsg) = loopSummary.pendingConfirmation
                    return Resource.Success(
                        PromptExecutionResult.ConfirmationRequired(
                            toolCall = call,
                            promptMessage = promptMsg
                        )
                    )
                }

                return Resource.Success(PromptExecutionResult.DirectAnswer(loopSummary.finalVoiceSummary))
            }

            return Resource.Success(PromptExecutionResult.DirectAnswer(rawOutput))
        }

        if (aiResult is Resource.Error) {
            // RETRY: один автоматический повтор через 2 секунды.
            delay(2000)
            val retryResult = aiRepository.generateResponse(
                prompt = query,
                systemPrompt = fullSystemPrompt,
                history = history
            )

            if (retryResult is Resource.Success) {
                return Resource.Success(PromptExecutionResult.DirectAnswer(retryResult.data.trim()))
            }

            val errorMsg = if (retryResult is Resource.Error) {
                retryResult.message ?: aiResult.message ?: "Не удалось связаться с сервером AI. Проверьте ключ в настройках."
            } else {
                aiResult.message ?: "Не удалось связаться с сервером AI. Проверьте ключ в настройках."
            }
            val errorException = if (retryResult is Resource.Error) retryResult.exception else aiResult.exception
            return Resource.Error(errorException, errorMsg)
        }

        return Resource.Error(Exception("Неизвестная ошибка выполнения"), "Неизвестная ошибка выполнения")
    }
}
