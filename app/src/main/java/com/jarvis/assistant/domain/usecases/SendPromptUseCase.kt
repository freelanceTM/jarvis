package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.agent.engine.AgentCognitiveLoop
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.fast.FastRouteResult
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.memory.procedural.WorkflowExecutor
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.parser.ToolCallParser
import com.jarvis.assistant.agent.planner.CognitivePlanner
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.router.TaskRouter
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SendPromptUseCase @Inject constructor(
    private val aiRepository: AIRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry,
    private val toolCallParser: ToolCallParser,
    private val toolExecutor: ToolExecutor,
    private val fastCommandRouter: FastCommandRouter,
    private val workflowExecutor: WorkflowExecutor,
    private val memoryManager: JarvisMemoryManager,
    private val taskRouter: TaskRouter,
    private val networkMonitor: NetworkMonitor,
    private val cognitivePlanner: CognitivePlanner,
    private val agentCognitiveLoop: AgentCognitiveLoop
) {
    suspend operator fun invoke(userPrompt: String): Resource<String> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException("Пустой запрос"))
        }

        // 1. Слой 2 (Episodic Memory): Сохранение входящего запроса в Room
        val userMessage = Message(
            role = MessageRole.USER,
            text = trimmedPrompt,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.insertMessage(userMessage)

        // 2. Автономное извлечение фактов в фоне
        memoryManager.extractAndRememberInBackground(trimmedPrompt)

        // =========================================================================
        // ⚡ ЭТАП 1: TIER 0 FAST BRAIN (Локальный NLU - < 10мс, 100% ОФЛАЙН)
        // =========================================================================
        val fastResult = fastCommandRouter.route(trimmedPrompt)
        if (fastResult is FastRouteResult.HandledLocally) {
            var voiceAnswer = fastResult.immediateVoiceResponse

            if (fastResult.toolCall != null) {
                val executionResult = toolExecutor.execute(fastResult.toolCall)
                if (executionResult.isSuccess) {
                    voiceAnswer = "${executionResult.summary}, сэр."
                }
                memoryManager.workingMemory.setLastAction(fastResult.toolCall.toolId)
            }

            saveAssistantMessage(voiceAnswer)
            return Resource.Success(voiceAnswer)
        }

        // =========================================================================
        // 🧠 ЭТАП 2: ДИНАМИЧЕСКИЙ ПЛАНИРОВЩИК (Intent ──► Plan ──► Execute ──► Observe)
        // Пример: «Джарвис, я ухожу», «Я дома», «Подготовь ко сну»
        // =========================================================================
        val dynamicPlan = cognitivePlanner.planForGoal(trimmedPrompt)
        if (dynamicPlan != null) {
            val planSummary = agentCognitiveLoop.runPlan(dynamicPlan)
            saveAssistantMessage(planSummary.finalVoiceSummary)
            return Resource.Success(planSummary.finalVoiceSummary)
        }

        // =========================================================================
        // ⚡ ЭТАП 3: PROCEDURAL MEMORY (Проверка сохраненных пользовательских макросов)
        // =========================================================================
        val workflowResult = workflowExecutor.tryExecuteWorkflow(trimmedPrompt)
        if (workflowResult != null) {
            val voiceResponse = when {
                workflowResult.isSuccess -> "${workflowResult.summary}, сэр."
                else -> workflowResult.summary
            }
            saveAssistantMessage(voiceResponse)
            return Resource.Success(voiceResponse)
        }

        // =========================================================================
        // 🛡️ ПРОВЕРКА СЕТИ ДЛЯ СЛОЖНЫХ ЗАДАЧ
        // =========================================================================
        if (!networkMonitor.isCurrentlyOnline()) {
            val offlineMsg = "Нет подключения к интернету. Локальные команды и сценарии (фонарик, звук, батарея, приложения) работают офлайн."
            saveAssistantMessage(offlineMsg)
            return Resource.Success(offlineMsg)
        }

        // =========================================================================
        // 🧠 ЭТАП 4: TIER 1-3 AI PLANNING & REASONING С СЕМАНТИЧЕСКОЙ ПАМЯТЬЮ
        // =========================================================================
        val routingDecision = taskRouter.routeTask(trimmedPrompt)
        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()
        val toolsSystemPrompt = toolRegistry.buildSystemPrompt()
        
        // 3-4 релевантных факта по векторному сходству
        val memoryContextPrompt = memoryManager.buildPromptMemoryContext(trimmedPrompt)

        val fullSystemPrompt = buildString {
            append(baseSystemPrompt)
            append("\n\n")
            if (memoryContextPrompt.isNotBlank()) {
                append(memoryContextPrompt)
                append("\n\n")
            }
            append(toolsSystemPrompt)
        }

        val history = messageRepository.getRecentMessages(limit = 4)

        val aiResult = aiRepository.generateResponse(
            prompt = trimmedPrompt,
            systemPrompt = fullSystemPrompt,
            history = history
        )

        if (aiResult is Resource.Success) {
            val rawOutput = aiResult.data.trim()

            // Проверяем, вернул ли AI план выполнения действий
            val llmPlan = cognitivePlanner.planForGoal(trimmedPrompt, rawOutput)
            val finalVoiceAnswer = if (llmPlan != null && llmPlan.steps.isNotEmpty()) {
                val loopSummary = agentCognitiveLoop.runPlan(llmPlan)
                loopSummary.finalVoiceSummary
            } else {
                rawOutput
            }

            saveAssistantMessage(finalVoiceAnswer)
            return Resource.Success(finalVoiceAnswer)
        } else if (aiResult is Resource.Error) {
            val errorMsg = aiResult.message ?: "Не удалось связаться с сервером AI. Проверьте ключ в настройках."
            saveAssistantMessage(errorMsg)
            return Resource.Success(errorMsg)
        }

        return aiResult
    }

    private suspend fun saveAssistantMessage(text: String) {
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.insertMessage(assistantMessage)
    }
}
