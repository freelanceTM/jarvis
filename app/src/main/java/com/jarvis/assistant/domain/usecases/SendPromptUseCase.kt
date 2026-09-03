package com.jarvis.assistant.domain.usecases

import android.content.Context
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.agent.pipeline.AgentPipeline
import com.jarvis.assistant.agent.tools.accessibility.ScreenContentPrivacy
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
     * @param cloudExplicitlyAllowed явное согласие пользователя на облачную
     *               обработку PRIVATE/SENSITIVE. ДОЛЖЕН быть true после того,
     *               как пользователь ответил «Да» на consent-карточку;
     *               в противном случае (и при effective != NORMAL) use case
     *               НЕ вызывает агентский конвейер и возвращает [Resource.NeedsConsent].
     */
    suspend operator fun invoke(
        userPrompt: String,
        source: RequestSource = RequestSource.CHAT,
        privacyLevel: PrivacyLevel = PrivacyLevel.UNKNOWN,
        cloudExplicitlyAllowed: Boolean = false,
        originTimestampMs: Long? = null
    ): Resource<PromptExecutionResult> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException(context.getString(R.string.pustoy_zapros)), context.getString(R.string.zapros_ne_mozhet_byt_pustym))
        }

        // 1. Фиксируем последнюю реплику (для анафоры) и разрешаем контекст.
        memoryManager.workingMemory.setLastMessage(trimmedPrompt)
        val resolvedPrompt = memoryManager.workingMemory.resolveContextualQuery(trimmedPrompt)

        // 2. Сохраняем сообщение пользователя и обновляем память. Сообщение
        //    пользователя появляется в чате ДО consent-gate — пользователь
        //    видит, что его запрос услышан, и потом решает про облако.
        messageRepository.insertMessage(
            Message(
                role = MessageRole.USER,
                text = trimmedPrompt,
                timestamp = System.currentTimeMillis()
            )
        )
        memoryManager.processTurnGovernance(resolvedPrompt)
        memoryManager.workingMemory.updateEntityFromResponse(trimmedPrompt)

        // H-02 / Refactor #3: ЕДИНСТВЕННОЕ место на запросе, где вызывается
        // PrivacyClassifier.classifySafely с полным контекстом (текст +
        // systemPrompt + история). Результат кладётся в ExecutionRequest и
        // дальше используется ВСЕМИ downstream-слоями — никаких повторных
        // вызовов classifySafely в AIRepository / JarvisApiAiClient /
        // ChatViewModel / VoiceInteractionOrchestrator на том же payload.
        // Серверный AiRouter всё равно переклассифицирует запрос
        // (defense-in-depth — ему нельзя доверять клиенту); это оправдано.
        val history = messageRepository.getRecentMessages(limit = 10)
        val systemPrompt = settingsRepository.systemPromptFlow.first()

        // Строим ExecutionRequest с единой контекстной классификацией:
        // текст + systemPrompt + тексты истории → один вызов classifySafely.
        val effectiveRequest = ExecutionRequest.withContextualClassification(
            text = resolvedPrompt,
            source = source,
            declaredLevel = privacyLevel,
            systemPrompt = systemPrompt,
            relatedContent = history.map(Message::text),
            history = history,
            cloudExplicitlyAllowed = cloudExplicitlyAllowed,
            originTimestampMs = originTimestampMs
        )

        val effective = effectiveRequest.effectivePrivacyLevel

        // C-02: PRIVATE/SENSITIVE без явного согласия — не ходим в агентский
        // конвейер (который полезет в сеть), а возвращаем NeedsConsent.
        // UI/voice показывают карточку/TTS-вопрос и вызывают use case повторно
        // с cloudExplicitlyAllowed=true.
        //
        // NORMAL идёт в pipeline; UNKNOWN после классификации — fail-closed
        // (isCloudRestricted == true), тоже потребует согласия.
        if (effective.isCloudRestricted && !cloudExplicitlyAllowed) {
            val promptResId = when (effective) {
                PrivacyLevel.SENSITIVE -> R.string.cloud_consent_sensitive_prompt
                else -> R.string.cloud_consent_private_prompt
            }
            return Resource.NeedsConsent(
                privacyLevel = effective,
                prompt = context.getString(promptResId),
                retryOnConsentArgs = Resource.NeedsConsent.RetryArgs(
                    userPrompt = trimmedPrompt,
                    source = source,
                    privacyLevel = privacyLevel
                )
            )
        }

        // 4. Единый агентский конвейер — вызывается только после privacy gate
        //    и получает запрос с УЖЕ заполненной privacyClassification.
        val result = agentPipeline.process(effectiveRequest)

        // 4. Сохраняем ответ ассистента.
        if (result is Resource.Success) {
            when (val exec = result.data) {
                is PromptExecutionResult.DirectAnswer -> {
                    // Accessibility Lockdown: экральный контент показывается
                    // и озвучивается, но НЕ сохраняется в историю — иначе
                    // getRecentMessages() вернёт его в следующий облачный
                    // запрос (relatedContent/history). См. ScreenContentPrivacy.
                    val persistedText = if (exec.containsScreenContent) {
                        ScreenContentPrivacy.PLACEHOLDER
                    } else {
                        exec.text
                    }
                    saveAssistantMessage(persistedText)
                    memoryManager.workingMemory.updateEntityFromResponse(persistedText)
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
