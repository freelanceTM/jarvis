package com.jarvis.assistant.agent.pipeline

import com.jarvis.assistant.agent.decision.ExecutionDecisionEngine
import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.ExecutionResult
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.PromptExecutionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AgentPipeline — точка входа агентского конвейера JARVIS.
 *
 * v0.2 (Этап 1): вся логика ВЫБОРА способа выполнения вынесена в
 * [ExecutionDecisionEngine]. Pipeline остался тонким адаптером и сохраняет
 * прежний публичный контракт (`process(query, history): Resource<PromptExecutionResult>`),
 * поэтому существующие consumers (SendPromptUseCase → ChatViewModel /
 * VoiceInteractionOrchestrator) не тронуты.
 *
 * ```
 * Voice / Chat
 *      ↓
 *     STT
 *      ↓
 * FastCommandRouter          (внутри ExecutionDecisionEngine)
 *      ↓
 * ExecutionDecisionEngine
 *      ├── Device Tool  → ToolExecutor → JarvisTool
 *      ├── Local AI     → on-device Gemma / WorkflowExecutor (офлайн)
 *      ├── Cloud AI     → AIRepository → JarvisApiAiClient → JARVIS API
 *      └── Agent        → CognitivePlanner → AgentCognitiveLoop
 *      ↓
 * ExecutionResult → PromptExecutionResult → Response / TTS
 * ```
 */
@Singleton
class AgentPipeline @Inject constructor(
    private val decisionEngine: ExecutionDecisionEngine
) {

    /**
     * Совместимый со старым кодом вход (источник по умолчанию — чат/текст).
     *
     * H-02: использует text-only дефолтную классификацию конструктора
     * [ExecutionRequest] (без systemPrompt/relatedContent). Продукционные
     * вызовы идут через [SendPromptUseCase], который строит контекстную
     * классификацию через [ExecutionRequest.withContextualClassification].
     */
    suspend fun process(
        query: String,
        history: List<Message> = emptyList()
    ): Resource<PromptExecutionResult> = process(
        ExecutionRequest(text = query, source = RequestSource.CHAT, history = history)
    )

    /**
     * Явный вход с указанием источника и подсказки о приватности.
     *
     * Классификация — text-only (дефолт из конструктора ExecutionRequest,
     * доступен только текст и история, без systemPrompt). Продукционные
     * вызовы с полным контекстом идут через [SendPromptUseCase].
     */
    suspend fun process(
        query: String,
        history: List<Message>,
        source: RequestSource,
        privacyLevel: PrivacyLevel = PrivacyLevel.UNKNOWN,
        requiresWeb: Boolean = false,
        cloudExplicitlyAllowed: Boolean = false
    ): Resource<PromptExecutionResult> = process(
        ExecutionRequest(
            text = query,
            source = source,
            requiresWeb = requiresWeb,
            privacyLevel = privacyLevel,
            cloudExplicitlyAllowed = cloudExplicitlyAllowed,
            history = history
        )
    )

    /** Основной вход на едином контракте v0.2. */
    suspend fun process(request: ExecutionRequest): Resource<PromptExecutionResult> =
        when (val result = decisionEngine.execute(request)) {
            is ExecutionResult.Success ->
                Resource.Success(PromptExecutionResult.DirectAnswer(result.text))

            is ExecutionResult.ClarificationRequired ->
                Resource.Success(PromptExecutionResult.DirectAnswer(result.promptMessage))

            is ExecutionResult.ConfirmationRequired ->
                Resource.Success(
                    PromptExecutionResult.ConfirmationRequired(
                        toolCall = result.toolCall,
                        promptMessage = result.promptMessage
                    )
                )

            is ExecutionResult.Error ->
                Resource.Error(ExecutionFailure(result.reason.name), result.message)
        }
}

/**
 * Ошибка выполнения без stack trace внутренних компонентов: наружу уходит
 * только код причины решения (пункт 11 ТЗ — не раскрывать детали пользователю).
 */
class ExecutionFailure(reason: String) : Exception("Execution failed: $reason")
