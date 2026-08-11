package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.fast.FastRouteResult
import com.jarvis.assistant.agent.memory.JarvisMemoryManager
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.parser.ToolCallParser
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.router.TaskRouter
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
    private val memoryManager: JarvisMemoryManager,
    private val taskRouter: TaskRouter
) {
    suspend operator fun invoke(userPrompt: String): Resource<String> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException("Пустой запрос"))
        }

        // 1. Слой 2: Сохраняем входящее событие в Episodic Memory (Room)
        val userMessage = Message(
            role = MessageRole.USER,
            text = trimmedPrompt,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.insertMessage(userMessage)

        // =========================================================================
        // ⚡ TIER 0: FAST BRAIN (Мгновенное локальное действие без сети и без LLM)
        // =========================================================================
        val fastResult = fastCommandRouter.route(trimmedPrompt)
        if (fastResult is FastRouteResult.HandledLocally) {
            val executionResult = toolExecutor.execute(fastResult.toolCall)
            val voiceAnswer = when (executionResult) {
                is ToolResult.Success -> {
                    fastResult.immediateVoiceResponse ?: "${executionResult.message}, сэр."
                }
                is ToolResult.RequiresConfirmation -> executionResult.message
                is ToolResult.Error -> "Не удалось: ${executionResult.message}"
            }

            // Фиксация в памяти
            memoryManager.workingMemory.setLastAction(fastResult.toolCall.name)

            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                text = voiceAnswer,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.insertMessage(assistantMessage)

            return Resource.Success(voiceAnswer)
        }

        // =========================================================================
        // 🧠 TIER 1–3: MULTI-MODEL ROUTER + 4-СЛОЙНАЯ ПАМЯТЬ JARVIS
        // =========================================================================
        val routingDecision = taskRouter.routeTask(trimmedPrompt)

        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()
        val toolsSystemPrompt = toolRegistry.buildSystemPrompt()
        val memoryContextPrompt = memoryManager.buildMemoryContextPrompt(trimmedPrompt)

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

        // Запрос к соответствующей модели через роутер
        val aiResult = aiRepository.generateResponse(
            prompt = trimmedPrompt,
            systemPrompt = fullSystemPrompt,
            history = history
        )

        if (aiResult is Resource.Success) {
            val rawOutput = aiResult.data.trim()
            val finalVoiceAnswer = processLlmActionPipeline(rawOutput, trimmedPrompt)

            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                text = finalVoiceAnswer,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.insertMessage(assistantMessage)

            return Resource.Success(finalVoiceAnswer)
        }

        return aiResult
    }

    private suspend fun processLlmActionPipeline(rawLlmOutput: String, userPrompt: String): String {
        val toolCalls = toolCallParser.parse(rawLlmOutput, userPrompt)
        if (toolCalls.isEmpty()) {
            return rawLlmOutput
        }

        val executionResults = toolExecutor.executeAll(toolCalls)
        val summaries = mutableListOf<String>()
        var requiresConfirmation = false

        for (res in executionResults) {
            when (res) {
                is ToolResult.Success -> {
                    summaries.add(res.message)
                }
                is ToolResult.RequiresConfirmation -> {
                    requiresConfirmation = true
                    summaries.add(res.message)
                }
                is ToolResult.Error -> {
                    summaries.add("Не удалось: ${res.message}")
                }
            }
        }

        val combinedSummary = summaries.joinToString(". ")
        return if (requiresConfirmation) combinedSummary else "$combinedSummary, сэр."
    }
}
