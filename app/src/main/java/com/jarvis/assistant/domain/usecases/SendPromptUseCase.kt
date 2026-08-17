package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.pipeline.AgentPipeline
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.repository.MessageRepository
import javax.inject.Inject

/**
 * SendPromptUseCase — тонкая обвязка над [AgentPipeline].
 *
 * Здесь остаётся «диалоговая» часть: анафора, память, сохранение сообщений.
 * Вся агентская логика (FastCommandRouter → AgentCognitiveLoop →
 * PLAN/REPLAN → ToolDiscovery → ToolExecutor → Observation → VERIFY → SUCCESS)
 * — в едином конвейере [AgentPipeline].
 */
class SendPromptUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val memoryManager: JarvisMemoryManager,
    private val agentPipeline: AgentPipeline
) {
    suspend operator fun invoke(userPrompt: String): Resource<PromptExecutionResult> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException("Пустой запрос"), "Запрос не может быть пустым")
        }

        // 1. Фиксируем последнюю реплику (для анафоры) и разрешаем контекст.
        memoryManager.workingMemory.setLastMessage(trimmedPrompt)
        val resolvedPrompt = memoryManager.workingMemory.resolveContextualQuery(trimmedPrompt)

        // 2. Сохраняем сообщение пользователя и обновляем память.
        messageRepository.insertMessage(
            Message(
                role = MessageRole.USER,
                text = trimmedPrompt,
                timestamp = System.currentTimeMillis()
            )
        )
        memoryManager.processTurnGovernance(resolvedPrompt)
        memoryManager.workingMemory.updateEntityFromResponse(trimmedPrompt)

        // 3. Единый агентский конвейер.
        val history = messageRepository.getRecentMessages(limit = 10)
        val result = agentPipeline.process(resolvedPrompt, history)

        // 4. Сохраняем ответ ассистента.
        if (result is Resource.Success) {
            when (val exec = result.data) {
                is PromptExecutionResult.DirectAnswer -> {
                    saveAssistantMessage(exec.text)
                    memoryManager.workingMemory.updateEntityFromResponse(exec.text)
                }
                is PromptExecutionResult.ConfirmationRequired -> {
                    saveAssistantMessage(exec.promptMessage)
                }
            }
        } else if (result is Resource.Error) {
            saveAssistantMessage(result.message ?: "Ошибка выполнения запроса")
        }

        return result
    }

    private suspend fun saveAssistantMessage(text: String) {
        messageRepository.insertMessage(
            Message(
                role = MessageRole.ASSISTANT,
                text = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
