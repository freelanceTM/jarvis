package com.jarvis.assistant.agent.decision

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.domain.models.Message

/**
 * Источник запроса: голос (STT → VoiceInteractionOrchestrator) или текстовый чат.
 */
enum class RequestSource {
    VOICE,
    CHAT
}

/**
 * Уровень приватности запроса.
 *
 * Правило проекта (см. [ExecutionDecisionEngine]): PRIVATE и SENSITIVE НИКОГДА
 * не уходят в облачную модель без явного разрешения пользователя
 * ([ExecutionRequest.cloudExplicitlyAllowed]).
 *
 * Явная метка вызывающего слоя усиливается локальным [PrivacyClassifier]:
 * автоматически обнаруженный PRIVATE/SENSITIVE нельзя понизить до NORMAL.
 */
enum class PrivacyLevel {
    NORMAL,
    PRIVATE,
    SENSITIVE;

    /** true — облачная обработка запрещена без явного разрешения. */
    val isCloudRestricted: Boolean get() = this != NORMAL
}

/**
 * Единый контракт запроса на выполнение (v0.2).
 *
 * Заменяет «голую строку» в качестве входа агентского конвейера: теперь вместе
 * с текстом передаются ограничения, влияющие на выбор пути выполнения.
 *
 * @param text            распознанный/введённый текст запроса.
 * @param source          голос или чат.
 * @param requiresWeb     запросу нужны актуальные данные из сети. Локальный
 *                        офлайн-слой НЕ имеет права имитировать их выполнение.
 * @param requiresDeviceControl подсказка вызывающего слоя, что запрос — про
 *                        управление устройством. Не подменяет FastCommandRouter,
 *                        а только повышает приоритет device-пути.
 * @param privacyLevel    политика приватности запроса.
 * @param cloudExplicitlyAllowed явное разрешение пользователя отправить
 *                        приватный запрос в облако (по умолчанию — нет).
 * @param history         история диалога для облачной модели (технически
 *                        необходима: существующий AIRepository принимает её).
 */
data class ExecutionRequest(
    val text: String,
    val source: RequestSource,
    val requiresWeb: Boolean = false,
    val requiresDeviceControl: Boolean = false,
    val privacyLevel: PrivacyLevel = PrivacyLevel.NORMAL,
    val cloudExplicitlyAllowed: Boolean = false,
    val history: List<Message> = emptyList()
) {
    /** Автоматически обнаруженный уровень, вычисленный до логирования/роутинга. */
    val detectedPrivacyLevel: PrivacyLevel = PrivacyClassifier.classify(text)

    /** Явный уровень нельзя ослабить, а автоматически найденный — понизить. */
    val effectivePrivacyLevel: PrivacyLevel =
        PrivacyClassifier.strongest(privacyLevel, detectedPrivacyLevel)

    /** Можно ли отправлять этот запрос в облако. */
    val isCloudAllowed: Boolean
        get() = !effectivePrivacyLevel.isCloudRestricted || cloudExplicitlyAllowed

    /**
     * Текст, безопасный для логов: приватные запросы не логируются дословно
     * (пункт 12 ТЗ этапа 1).
     */
    val loggableText: String
        get() = if (effectivePrivacyLevel == PrivacyLevel.NORMAL) {
            text
        } else {
            "<redacted:${text.length} chars>"
        }
}

/**
 * Каким механизмом был выполнен запрос.
 */
enum class ExecutionType {
    /** Локальная команда устройства: FastCommandRouter → ToolExecutor → JarvisTool. */
    DEVICE_TOOL,

    /**
     * Локальный офлайн-слой: on-device Gemma (если модель установлена) и
     * процедурная память / сохранённые пользовательские сценарии.
     */
    LOCAL_AI,

    /** Облачная LLM через AIRepository / JarvisApiAiClient / JARVIS API. */
    CLOUD_AI,

    /** Многошаговый план: CognitivePlanner → AgentCognitiveLoop. */
    AGENT
}

/**
 * Почему был выбран путь выполнения — для структурированных логов и тестов.
 */
enum class DecisionReason {
    FAST_ROUTER_CONFIDENT,
    FAST_ROUTER_UNCERTAIN,
    COMPLEX_MULTI_STEP,
    LOCAL_AI_HANDLED,
    LOCAL_AI_UNCERTAIN,
    LOCAL_AI_NO_WEB_CAPABILITY,
    CLOUD_BLOCKED_BY_PRIVACY,
    CLOUD_FAILED,
    CLOUD_PLAN_DETECTED,
    DEVICE_TOOL_FAILED,
    INVALID_REQUEST,
    CLARIFICATION_REQUIRED,
    UNEXPECTED_ERROR
}

/**
 * Единый результат выполнения (v0.2).
 *
 * [ConfirmationRequired] — необходимое техническое расширение контракта:
 * в проекте уже существует поток подтверждения опасных действий
 * (ToolExecutor → PendingConfirmationRequest → голосовое «да/нет» / UI-карточка).
 * Без этой ветки существующие consumers сломались бы.
 */
sealed class ExecutionResult {

    data class Success(
        val text: String,
        val executionType: ExecutionType,
        /** Технические детали (reason, tool_id, confidence) — не для пользователя. */
        val metadata: Map<String, String> = emptyMap()
    ) : ExecutionResult()

    data class Error(
        val message: String,
        val reason: DecisionReason = DecisionReason.UNEXPECTED_ERROR
    ) : ExecutionResult()

    /** Не хватает объекта/критерия — нужно уточнение, а не выдуманный ответ. */
    data class ClarificationRequired(
        val promptMessage: String
    ) : ExecutionResult()

    /** Действие требует явного подтверждения пользователя (SMS, звонок и т. п.). */
    data class ConfirmationRequired(
        val toolCall: ToolCall,
        val promptMessage: String
    ) : ExecutionResult()
}

/**
 * Конфигурация решения. Вынесена отдельно, потому что в проекте до этого
 * НЕ существовало числового порога уверенности роутинга.
 *
 * TODO: [deviceConfidenceThreshold] требует калибровки на реальных логах.
 *  Текущее значение подобрано так, чтобы полностью сохранить существующее
 *  поведение: FastCommandRouter.HandledLocally(toolCall != null) = 0.95,
 *  HandledLocally без toolCall (реплика-ответ) = 0.80, ForwardToLlm = 0.0.
 */
data class ExecutionDecisionConfig(
    val deviceConfidenceThreshold: Float = 0.75f
)
