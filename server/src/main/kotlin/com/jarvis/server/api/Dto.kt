package com.jarvis.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Публичный контракт JARVIS API v1.
 *
 * Контракт НЕ привязан к конкретному AI-провайдеру: клиент не знает и не может
 * выбрать, кто выполнит запрос. Выбор провайдера — исключительно server-side.
 */

/** Источник запроса (зеркалит Android `RequestSource`). */
enum class ApiRequestSource { VOICE, CHAT }

/** Уровень приватности (зеркалит Android `PrivacyLevel`). */
enum class ApiPrivacyLevel { UNKNOWN, NORMAL, PRIVATE, SENSITIVE }

/** Как был выполнен запрос (зеркалит Android `ExecutionType`). */
enum class ApiExecutionType { CLOUD_AI }

/**
 * Одно сообщение диалога (CR-03).
 *
 * `role` использует OpenAI-совместимые строки: `user` / `assistant` / `system`.
 * Пустая история (`emptyList()`) эквивалентна поведению до CR-03.
 */
@Serializable
data class MessageDto(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

/**
 * Запрос на выполнение.
 *
 * Намеренно НЕТ поля `provider`: клиент не имеет права выбирать провайдера
 * (пункт 29 ТЗ). Любое такое поле было бы проигнорировано.
 */
@Serializable
data class AiExecutionRequest(
    @SerialName("text") val text: String,

    @SerialName("source") val source: ApiRequestSource = ApiRequestSource.CHAT,

    @SerialName("privacyLevel") val privacyLevel: ApiPrivacyLevel = ApiPrivacyLevel.UNKNOWN,

    /**
     * P2-cleanup (Этап 5): серверная инфраструктура requiresWeb уже
     * подключена (ProviderManager.capabilities.supportsWeb +
     * google_search_retrieval у Gemini). Android-клиент ОТПРАВЛЯЕТ поле
     * в DTO, но UI-кнопки «искать в вебе» пока нет (v1) — фактически
     * всегда false. Оставляем поле в контракте как forward-compatible:
     * при появлении кнопки отправки контекста из UI его достаточно
     * просто выставить в true без нового контракта.
     */
    @SerialName("requiresWeb") val requiresWeb: Boolean = false,

    /** Per-request user consent. It never overrides UNKNOWN or classifier failure. */
    @SerialName("cloudExplicitlyAllowed") val cloudExplicitlyAllowed: Boolean = false,

    /**
     * Клиентский correlation id. Если не передан — сервер сгенерирует свой.
     * Позволяет сопоставить логи Android и сервера.
     */
    @SerialName("requestId") val requestId: String? = null,

    /**
     * Дополнительный контекст ассистента от клиента (Tool Discovery, персона).
     *
     * Зачем это нужно: Android-агент подмешивает описания доступных на КОНКРЕТНОМ
     * устройстве инструментов, чтобы модель могла вернуть tool_calls. Сервер
     * физически не знает набор инструментов данного телефона, поэтому контекст
     * приходит от клиента.
     *
     * Ограничения безопасности:
     *  - это НЕ выбор провайдера и НЕ выбор модели (они остаются server-side);
     *  - длина валидируется наравне с [text];
     *  - сервер добавляет свой базовый system prompt поверх, а не заменяет его.
     */
    @SerialName("systemContext") val systemContext: String? = null,

    /**
     * История диалога (CR-03). Последние сообщения пользователя и ассистента,
     * которые проксируются в провайдера как messages, а не как одиночный prompt.
     *
     * Порядок: от старых к новым. Текущий пользовательский запрос ([text])
     * добавляется последним и НЕ должен дублироваться в history.
     *
     * При `history == emptyList()` поведение идентично прежнему.
     */
    @SerialName("history") val history: List<MessageDto> = emptyList()
) {
    companion object {
        // P2-cleanup (Этап 5): компактный memory-контекст (AR-04) был полуготовым
        // контрактом, который ни Android-клиент ни server-side memory system
        // никогда не заполняли. Поле и валидация удалены; при появлении
        // реального источника фактов (Room memory) — вернуть отдельным PR
        // вместе с клиентской отправкой.
    }
}

/**
 * Успешный ответ.
 *
 * Поле `provider` намеренно отсутствует: какой именно провайдер отработал —
 * это server-side telemetry (пункт 3 ТЗ). Клиенту эта деталь не нужна и её
 * раскрытие только привязало бы Android к инфраструктуре.
 */
@Serializable
data class AiExecutionResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("text") val text: String,
    @SerialName("executionType") val executionType: ApiExecutionType = ApiExecutionType.CLOUD_AI,
    @SerialName("requestId") val requestId: String
)

/** Тело ошибки — единый формат для всех сбоев. */
@Serializable
data class ApiErrorBody(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
    @SerialName("requestId") val requestId: String
)

@Serializable
data class ApiErrorResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("error") val error: ApiErrorBody
)

/**
 * Machine-readable коды ошибок.
 *
 * Клиенту НИКОГДА не уходят: stack trace, ключи провайдеров, внутренние
 * сообщения исключений, ошибки БД (пункт 5 ТЗ). [message] — это заранее
 * заданный безопасный текст, а не `e.message`.
 */
enum class ApiErrorCode(val httpStatus: Int, val safeMessage: String) {
    INVALID_REQUEST(400, "Request validation failed"),
    UNAUTHORIZED(401, "Authentication required"),
    FORBIDDEN(403, "Client is not allowed to perform this operation"),
    RATE_LIMITED(429, "Rate limit exceeded"),
    PRIVACY_POLICY_VIOLATION(403, "Request privacy level forbids cloud execution"),
    LICENSE_NOT_REDEEMABLE(404, "License cannot be redeemed"),
    LICENSE_EXPIRED(410, "License expired"),
    LICENSE_REVOKED(403, "License revoked or disabled"),
    LICENSE_INVALID_STATE(409, "License state does not allow this operation"),
    LICENSE_WRONG_DEVICE(403, "License is bound to another device"),
    PAYMENT_REQUIRED(402, "Active billing entitlement required"),
    BILLING_PROVIDER_UNAVAILABLE(503, "Billing provider unavailable"),
    BILLING_EVENT_INVALID(401, "Billing event verification failed"),
    PROVIDER_UNAVAILABLE(503, "AI provider unavailable"),
    PROVIDER_TIMEOUT(504, "AI provider timeout"),
    PROVIDER_ERROR(502, "AI provider error"),
    ALL_PROVIDERS_UNAVAILABLE(503, "No AI provider is currently available"),
    PAYLOAD_TOO_LARGE(413, "Request payload too large"),
    INTERNAL_ERROR(500, "Internal server error");

    fun toResponse(requestId: String, overrideMessage: String? = null) = ApiErrorResponse(
        error = ApiErrorBody(
            code = name,
            message = overrideMessage ?: safeMessage,
            requestId = requestId
        )
    )
}
