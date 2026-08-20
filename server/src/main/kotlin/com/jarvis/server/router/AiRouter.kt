package com.jarvis.server.router

import com.jarvis.server.api.AiExecutionRequest
import com.jarvis.server.api.ApiErrorCode
import com.jarvis.server.api.ApiPrivacyLevel
import com.jarvis.server.auth.AuthenticatedClient
import com.jarvis.server.config.AiGenerationConfig
import com.jarvis.server.config.PrivacyPolicyConfig
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.observability.LogSanitizer
import com.jarvis.server.observability.Metrics
import com.jarvis.server.observability.StructuredLogger
import com.jarvis.server.provider.ManagerOutcome
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderRequirements
import com.jarvis.server.privacy.PromptPrivacyClassifier
import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.UsageRepository
import java.time.Instant

/** Результат работы роутера — уже нормализован для отдачи клиенту. */
sealed class RouterResult {
    data class Success(val text: String, val requestId: String) : RouterResult()
    data class Failure(val code: ApiErrorCode, val requestId: String) : RouterResult()
}

/**
 * AI Router (пункт 9 ТЗ).
 *
 * Ответственность:
 *  1. валидация запроса;
 *  2. применение privacy-политики;
 *  3. определение требований к провайдеру;
 *  4. делегирование в [ProviderManager];
 *  5. нормализация ошибок провайдеров в коды API;
 *  6. usage tracking.
 *
 * Внутри НЕТ ни строчки provider-specific HTTP-кода — это принципиально
 * (пункт 9 ТЗ): роутер знает «какая стратегия», менеджер знает «какие
 * провайдеры», провайдер знает «какой HTTP».
 */
class AiRouter(
    private val providerManager: ProviderManager,
    private val usageRepository: UsageRepository,
    private val validation: ValidationConfig,
    private val privacyPolicy: PrivacyPolicyConfig,
    private val generation: AiGenerationConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun execute(
        request: AiExecutionRequest,
        client: AuthenticatedClient,
        requestId: String
    ): RouterResult {
        val startedAt = clock()
        metrics.recordRequest()

        // ------------------------------------------------------- 1. Валидация
        val validationError = validate(request)
        if (validationError != null) {
            logger.warn(
                "request validation failed",
                "requestId" to requestId,
                "clientId" to client.clientId,
                "code" to validationError.name
            )
            metrics.recordFailure()
            recordUsage(requestId, client, null, null, clock() - startedAt, null, request, 0, validationError.name)
            return RouterResult.Failure(validationError, requestId)
        }

        // -------------------------------------------------- 2. Privacy policy
        // Вторая линия защиты: сервер не доверяет клиентской метке NORMAL.
        // Явный уровень усиливается локальной классификацией текста и никогда
        // не понижается автоматически.
        val detectedPrivacy = PromptPrivacyClassifier.classify(request.text)
        val effectivePrivacy = PromptPrivacyClassifier.strongest(
            explicit = request.privacyLevel,
            detected = detectedPrivacy
        )
        if (!isPrivacyAllowed(effectivePrivacy)) {
            logger.warn(
                "privacy policy blocked cloud execution",
                "requestId" to requestId,
                "clientId" to client.clientId,
                "privacyLevel" to effectivePrivacy.name,
                "declaredPrivacyLevel" to request.privacyLevel.name,
                "automaticallyDetected" to (detectedPrivacy != ApiPrivacyLevel.NORMAL).toString()
            )
            metrics.recordFailure()
            metrics.recordPrivacyBlocked()
            recordUsage(
                requestId, client, null, null, clock() - startedAt, null, request, 0,
                ApiErrorCode.PRIVACY_POLICY_VIOLATION.name
            )
            return RouterResult.Failure(ApiErrorCode.PRIVACY_POLICY_VIOLATION, requestId)
        }

        // ------------------------------------------------ 3. Execution policy
        val requirements = ProviderRequirements(requiresWeb = request.requiresWeb)

        logger.info(
            "ai request accepted",
            "requestId" to requestId,
            "clientId" to client.clientId,
            "source" to request.source.name,
            "privacyLevel" to effectivePrivacy.name,
            "requiresWeb" to request.requiresWeb.toString(),
            // Текст промпта НЕ логируется — только его размер.
            "promptSize" to LogSanitizer.describeText(request.text)
        )

        val providerRequest = ProviderRequest(
            requestId = requestId,
            prompt = request.text,
            systemPrompt = buildSystemPrompt(request),
            maxTokens = generation.maxTokens,
            temperature = generation.temperature
        )

        // ----------------------------------------------- 4. Provider Manager
        val outcome = providerManager.execute(providerRequest, requirements)
        val totalLatency = clock() - startedAt

        return when (outcome) {
            is ManagerOutcome.Success -> {
                metrics.recordSuccess(outcome.totalTokens)
                recordUsage(
                    requestId = requestId,
                    client = client,
                    provider = outcome.providerId.name,
                    model = outcome.model,
                    latencyMs = totalLatency,
                    tokens = Triple(outcome.inputTokens, outcome.outputTokens, outcome.totalTokens),
                    request = request,
                    responseChars = outcome.text.length,
                    errorCode = null
                )
                logger.info(
                    "ai request completed",
                    "requestId" to requestId,
                    "clientId" to client.clientId,
                    "provider" to outcome.providerId.name,
                    "model" to outcome.model,
                    "latencyMs" to totalLatency.toString(),
                    "totalTokens" to (outcome.totalTokens?.toString() ?: "-"),
                    "status" to "success"
                )
                RouterResult.Success(outcome.text, requestId)
            }

            is ManagerOutcome.Failure -> {
                val code = mapFailure(outcome)
                metrics.recordFailure()
                recordUsage(
                    requestId = requestId,
                    client = client,
                    provider = outcome.attempted.lastOrNull()?.name,
                    model = null,
                    latencyMs = totalLatency,
                    tokens = null,
                    request = request,
                    responseChars = 0,
                    errorCode = code.name
                )
                logger.error(
                    "ai request failed",
                    "requestId" to requestId,
                    "clientId" to client.clientId,
                    "attemptedProviders" to outcome.attempted.joinToString("|") { it.name },
                    "errorCode" to code.name,
                    "latencyMs" to totalLatency.toString(),
                    "status" to "failure"
                )
                RouterResult.Failure(code, requestId)
            }
        }
    }

    private fun validate(request: AiExecutionRequest): ApiErrorCode? = when {
        request.text.isBlank() -> ApiErrorCode.INVALID_REQUEST
        request.text.length > validation.maxTextLength -> ApiErrorCode.INVALID_REQUEST
        // Клиентский контекст валидируется наравне с основным текстом,
        // иначе он стал бы способом обойти лимит размера.
        (request.systemContext?.length ?: 0) > validation.maxTextLength ->
            ApiErrorCode.INVALID_REQUEST
        else -> null
    }

    /**
     * Базовый system prompt сервера ДОПОЛНЯЕТСЯ клиентским контекстом,
     * а не заменяется им: правила ассистента остаются под контролем сервера.
     */
    private fun buildSystemPrompt(request: AiExecutionRequest): String {
        val clientContext = request.systemContext?.trim()
        return if (clientContext.isNullOrEmpty()) {
            generation.systemPrompt
        } else {
            generation.systemPrompt + "\n\n" + clientContext
        }
    }

    private fun isPrivacyAllowed(level: ApiPrivacyLevel): Boolean = when (level) {
        ApiPrivacyLevel.NORMAL -> true
        ApiPrivacyLevel.PRIVATE -> privacyPolicy.allowPrivate
        ApiPrivacyLevel.SENSITIVE -> privacyPolicy.allowSensitive
    }

    /**
     * Нормализация сбоев провайдеров (пункт 22 ТЗ).
     *
     * Наружу уходит только код из [ApiErrorCode]; ни статусы провайдеров,
     * ни их сообщения клиенту не показываются.
     */
    private fun mapFailure(failure: ManagerOutcome.Failure): ApiErrorCode {
        if (failure.noCandidates) return ApiErrorCode.ALL_PROVIDERS_UNAVAILABLE

        // Пробовали нескольких и все упали — это ALL_PROVIDERS_UNAVAILABLE.
        if (failure.attempted.size > 1) return ApiErrorCode.ALL_PROVIDERS_UNAVAILABLE

        return when (failure.lastKind) {
            ProviderFailureKind.TIMEOUT -> ApiErrorCode.PROVIDER_TIMEOUT
            ProviderFailureKind.RATE_LIMITED -> ApiErrorCode.RATE_LIMITED
            ProviderFailureKind.CONNECTION,
            ProviderFailureKind.SERVER_ERROR,
            ProviderFailureKind.NOT_CONFIGURED -> ApiErrorCode.PROVIDER_UNAVAILABLE
            ProviderFailureKind.AUTH,
            ProviderFailureKind.BAD_REQUEST,
            ProviderFailureKind.UNKNOWN -> ApiErrorCode.PROVIDER_ERROR
            null -> ApiErrorCode.ALL_PROVIDERS_UNAVAILABLE
        }
    }

    /** Usage пишется всегда — и на успех, и на ошибку (пункт 32 ТЗ). */
    private suspend fun recordUsage(
        requestId: String,
        client: AuthenticatedClient,
        provider: String?,
        model: String?,
        latencyMs: Long,
        tokens: Triple<Long?, Long?, Long?>?,
        request: AiExecutionRequest,
        responseChars: Int,
        errorCode: String?
    ) {
        usageRepository.record(
            AiUsageRecord(
                requestId = requestId,
                clientId = client.clientId,
                provider = provider,
                model = model,
                latencyMs = latencyMs,
                inputTokens = tokens?.first,
                outputTokens = tokens?.second,
                totalTokens = tokens?.third,
                success = errorCode == null,
                errorCode = errorCode,
                // Сохраняем только размеры, не сам текст (privacy).
                promptChars = request.text.length,
                responseChars = responseChars,
                timestamp = Instant.ofEpochMilli(clock())
            )
        )
    }
}
