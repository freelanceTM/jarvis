package com.jarvis.assistant.domain.usecases

import android.content.Context
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.PrivacyClassifier
import com.jarvis.assistant.agent.decision.PrivacyContent
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.agent.pipeline.AgentPipeline
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
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
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val memoryManager: JarvisMemoryManager,
    private val agentPipeline: AgentPipeline
) {
    /**
     * @param source откуда пришёл запрос — голос (STT) или текстовый чат.
     *               Участвует в решении [com.jarvis.assistant.agent.decision.ExecutionDecisionEngine].
     * @param privacyLevel только hint вызывающего слоя. По умолчанию UNKNOWN;
     *               локальный classifier обязан завершиться до routing.
     */
    suspend operator fun invoke(
        userPrompt: String,
        source: RequestSource = RequestSource.CHAT,
        privacyLevel: PrivacyLevel = PrivacyLevel.UNKNOWN,
        cloudExplicitlyAllowed: Boolean = false
    ): Resource<PromptExecutionResult> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException(context.getString(R.string.pustoy_zapros)), context.getString(R.string.zapros_ne_mozhet_byt_pustym))
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
        val systemPrompt = settingsRepository.systemPromptFlow.first()
        val classification = PrivacyClassifier.classifySafely(
            PrivacyContent(
                text = resolvedPrompt,
                relatedContent = listOf(systemPrompt) + history.map(Message::text)
            )
        )
        val result = agentPipeline.process(
            ExecutionRequest(
                text = resolvedPrompt,
                source = source,
                privacyLevel = privacyLevel,
                cloudExplicitlyAllowed = cloudExplicitlyAllowed,
                history = history,
                privacyClassification = classification
            )
        )

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
            saveAssistantMessage(result.message ?: context.getString(R.string.oshibka_vypolneniya_zaprosa))
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
