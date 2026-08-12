package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.agent.engine.AgentCognitiveLoop
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.fast.FastRouteResult
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.memory.procedural.WorkflowExecutor
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.parser.ToolCallParser
import com.jarvis.assistant.agent.planner.CognitivePlanner
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.router.TaskRouter
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.IOException
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
    suspend operator fun invoke(userPrompt: String): Resource<PromptExecutionResult> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException("Пустой запрос"), "Запрос не может быть пустым")
        }

        // 1. Разрешение анафоры и контекста местоимений (Multi-Turn Context Memory)
        // Пример: "Кто президент Франции?" ➔ "Эмманюэль Макрон". След.: "Сколько ему лет?" ➔ "Сколько лет Эмманюэль Макрон"
        val resolvedPrompt = memoryManager.workingMemory.resolveContextualQuery(trimmedPrompt)

        // 2. Слой 2 (Episodic Memory): Сохранение входящего запроса в Room
        val userMessage = Message(
            role = MessageRole.USER,
            text = trimmedPrompt,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.insertMessage(userMessage)

        // 3. Memory 2.0 Governance: Извлечение фактов, дедупликация и обработка команд "Забудь..."
        memoryManager.processTurnGovernance(resolvedPrompt)
        memoryManager.workingMemory.updateEntityFromResponse(trimmedPrompt)

        // =========================================================================
        // ⚡ ЭТАП 1: TIER 0 FAST BRAIN (Локальный NLU - < 10мс, 100% ОФЛАЙН)
        // =========================================================================
        val fastResult = fastCommandRouter.route(resolvedPrompt)
        if (fastResult is FastRouteResult.HandledLocally) {
            var voiceAnswer = fastResult.immediateVoiceResponse

            if (fastResult.toolCall != null) {
                val executionResult = toolExecutor.execute(fastResult.toolCall)
                if (executionResult.status == ToolExecutionStatus.REQUIRES_USER_CONFIRMATION) {
                    saveAssistantMessage(executionResult.summary)
                    return Resource.Success(
                        PromptExecutionResult.ConfirmationRequired(
                            toolCall = fastResult.toolCall,
                            promptMessage = executionResult.summary
                        )
                    )
                }
                if (executionResult.isSuccess) {
                    voiceAnswer = "${executionResult.summary}, сэр."
                }
                memoryManager.workingMemory.setLastAction(fastResult.toolCall.toolId)
            }

            saveAssistantMessage(voiceAnswer)
            memoryManager.workingMemory.updateEntityFromResponse(voiceAnswer)
            return Resource.Success(PromptExecutionResult.DirectAnswer(voiceAnswer))
        }

        // =========================================================================
        // 🧠 ЭТАП 2: ДИНАМИЧЕСКИЙ ПЛАНИРОВЩИК (Intent ──► Plan ──► Execute ──► Observe)
        // =========================================================================
        val dynamicPlan = cognitivePlanner.planForGoal(resolvedPrompt)
        if (dynamicPlan != null) {
            val planSummary = agentCognitiveLoop.runPlan(dynamicPlan)
            saveAssistantMessage(planSummary.finalVoiceSummary)
            memoryManager.workingMemory.updateEntityFromResponse(planSummary.finalVoiceSummary)

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

        // =========================================================================
        // ⚡ ЭТАП 3: PROCEDURAL MEMORY (Проверка сохраненных пользовательских макросов)
        // =========================================================================
        val workflowResult = workflowExecutor.tryExecuteWorkflow(resolvedPrompt)
        if (workflowResult != null) {
            val voiceResponse = when {
                workflowResult.isSuccess -> "${workflowResult.summary}, сэр."
                else -> workflowResult.summary
            }
            saveAssistantMessage(voiceResponse)
            memoryManager.workingMemory.updateEntityFromResponse(voiceResponse)
            return Resource.Success(PromptExecutionResult.DirectAnswer(voiceResponse))
        }

        // =========================================================================
        // 🛡️ УПРЕЖДАЮЩАЯ ПРОВЕРКА СЕТИ ДО AI ЗАПРОСА
        // =========================================================================
        if (!networkMonitor.isCurrentlyOnline()) {
            val offlineMsg = "Нет подключения к интернету. Локальные команды (фонарик, звук, батарея, приложения, память) работают офлайн."
            saveAssistantMessage(offlineMsg)
            return Resource.Error(IOException("Network offline"), offlineMsg)
        }

        // =========================================================================
        // 🔍 ЭТАП 4: TOOL DISCOVERY 2.0 + СЕМАНТИЧЕСКАЯ ПАМЯТЬ 2.0 + AI BRAIN
        // =========================================================================
        val routingDecision = taskRouter.routeTask(resolvedPrompt)
        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()

        // ДИНАМИЧЕСКИЙ TOOL DISCOVERY: Отбираем ТОЛЬКО 2-4 нужных инструмента
        val targetedToolsPrompt = toolRegistry.buildTargetedSystemPrompt(resolvedPrompt)
        
        // Извлекаем только 3-4 релевантных факта по векторному сходству
        val memoryContextPrompt = memoryManager.buildPromptMemoryContext(resolvedPrompt)

        val fullSystemPrompt = buildString {
            append(baseSystemPrompt)
            append("\n\n")
            if (memoryContextPrompt.isNotBlank()) {
                append(memoryContextPrompt)
                append("\n\n")
            }
            append(targetedToolsPrompt)
        }

        // Увеличенное окно контекста: 10 последних реплик (5 раундов диалога)
        val history = messageRepository.getRecentMessages(limit = 10)

        val aiResult = aiRepository.generateResponse(
            prompt = resolvedPrompt,
            systemPrompt = fullSystemPrompt,
            history = history
        )

        if (aiResult is Resource.Success) {
            val rawOutput = aiResult.data.trim()

            val llmPlan = cognitivePlanner.planForGoal(resolvedPrompt, rawOutput)
            if (llmPlan != null && llmPlan.steps.isNotEmpty()) {
                val loopSummary = agentCognitiveLoop.runPlan(llmPlan)
                saveAssistantMessage(loopSummary.finalVoiceSummary)
                memoryManager.workingMemory.updateEntityFromResponse(loopSummary.finalVoiceSummary)

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
            } else {
                saveAssistantMessage(rawOutput)
                memoryManager.workingMemory.updateEntityFromResponse(rawOutput)
                return Resource.Success(PromptExecutionResult.DirectAnswer(rawOutput))
            }
        } else if (aiResult is Resource.Error) {
            // RETRY ЛОГИКА: при сбое связи пробуем 1 автоматический повтор через 2 секунды
            delay(2000)
            val retryResult = aiRepository.generateResponse(
                prompt = resolvedPrompt,
                systemPrompt = fullSystemPrompt,
                history = history
            )

            if (retryResult is Resource.Success) {
                val rawOutput = retryResult.data.trim()
                saveAssistantMessage(rawOutput)
                memoryManager.workingMemory.updateEntityFromResponse(rawOutput)
                return Resource.Success(PromptExecutionResult.DirectAnswer(rawOutput))
            }

            val errorMsg = retryResult.message ?: aiResult.message ?: "Не удалось связаться с сервером AI. Проверьте ключ в настройках."
            saveAssistantMessage("Ошибка: $errorMsg")
            return Resource.Error(
                retryResult.cause ?: aiResult.cause ?: Exception(errorMsg),
                errorMsg
            )
        }

        return Resource.Error(Exception("Неизвестная ошибка выполнения"), "Неизвестная ошибка выполнения")
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
