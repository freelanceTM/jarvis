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
import com.jarvis.server.privacy.PrivacyContent
import com.jarvis.server.privacy.PromptPrivacyClassifier
import com.jarvis.server.privacy.ServerPrivacyClassifier
import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.AsyncUsageTracker
import com.jarvis.server.usage.UsageLimitResult
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
    private val usageRepository: UsageRepository? = null,
    private val usageTracker: AsyncUsageTracker? = null,
    private val validation: ValidationConfig,
    private val privacyPolicy: PrivacyPolicyConfig,
    private val generation: AiGenerationConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val privacyClassifier: ServerPrivacyClassifier = PromptPrivacyClassifier,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(usageRepository != null || usageTracker != null) {
            "AiRouter requires at least one of usageRepository or usageTracker"
        }
    }

    suspend fun execute(
        request: AiExecutionRequest,
        client: AuthenticatedClient,
        requestId: String,
        deadlineEpochMs: Long? = null
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

        // AR-05: быстрый in-memory precheck лимитов (tokens/cost/requests).
        // PostgresRateLimiter остаётся authoritative по perMinute/perDay
        // request-лимиту; эта проверка дополняет его token/cost счётчиками.
        if (usageTracker != null) {
            when (val limit = usageTracker.preflight(client.clientId)) {
                is UsageLimitResult.Limited -> {
                    logger.warn(
                        "usage limit exceeded",
                        "requestId" to requestId,
                        "clientId" to client.clientId,
                        "scope" to limit.scope,
                        "retryAfter" to limit.retryAfterSeconds.toString()
                    )
                    recordUsage(requestId, client, null, null, clock() - startedAt, null, request, 0, "USAGE_LIMIT_${limit.scope.uppercase()}")
                    return RouterResult.Failure(ApiErrorCode.RATE_LIMITED, requestId)
                }
                UsageLimitResult.Allowed -> Unit
            }
        }

        // -------------------------------------------------- 2. Privacy policy
        // Вторая линия защиты: сервер не доверяет клиентской метке NORMAL.
        // Явный уровень усиливается локальной классификацией текста и никогда
        // не понижается автоматически.
        val relatedForClassification = buildList {
            addAll(request.history.map { it.content })
            request.systemContext?.let { add(it) }
        }
        val automaticPrivacy = PromptPrivacyClassifier.classifySafely(
            content = PrivacyContent(
                text = request.text,
                relatedContent = relatedForClassification
            ),
            classifier = privacyClassifier
        )
        val effectivePrivacy = PromptPrivacyClassifier.effective(
            declared = request.privacyLevel,
            automatic = automaticPrivacy
        )
        if (!isPrivacyAllowed(effectivePrivacy, request.cloudExplicitlyAllowed)) {
            logger.warn(
                "privacy policy blocked cloud execution",
                "requestId" to requestId,
                "clientId" to client.clientId,
                "privacyLevel" to effectivePrivacy.name,
                "declaredPrivacyLevel" to request.privacyLevel.name,
                "privacyReasons" to automaticPrivacy.reasons.joinToString("|") { it.name }
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
            history = request.history
                .filter { it.content.isNotBlank() }
                .map { com.jarvis.server.provider.ProviderMessage(role = normalizeRole(it.role), content = it.content) },
            requiresWeb = request.requiresWeb,
            maxTokens = generation.maxTokens,
            temperature = generation.temperature,
            // CR-06: прокидываем общий deadline до менеджера провайдеров,
            // чтобы он мог делать early-out между fallback'ами и подрезать
            // per-provider timeout оставшимся бюджетом.
            deadlineEpochMs = deadlineEpochMs
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
        // CR-03: каждое сообщение истории валидируется по тому же пределу длины,
        // чтобы клиент не мог прислать одного гигантского history[*].content.
        request.history.any { msg ->
            msg.content.isBlank() || msg.content.length > validation.maxTextLength
        } -> ApiErrorCode.INVALID_REQUEST
        // CR-03: белый список ролей в истории — отсекает опечатки / подделки.
        request.history.any { msg -> normalizeRole(msg.role) == null } -> ApiErrorCode.INVALID_REQUEST
        else -> null
    }

    /**
     * CR-03/16: приводит клиентскую роль к каноническому виду
     * (user/assistant/system). Gemini "model" мапится в assistant, и наоборот.
     */
    private fun normalizeRole(raw: String): String? = when (raw.trim().lowercase()) {
        "user" -> "user"
        "assistant", "model" -> "assistant"
        "system" -> "system"
        else -> null
    }

    /**
     * Базовый system prompt сервера ДОПОЛНЯЕТСЯ клиентским контекстом,
     * а не заменяется им: правила ассистента остаются под контролем сервера.
     */
    private fun buildSystemPrompt(request: AiExecutionRequest): String {
        val parts = mutableListOf(generation.systemPrompt)

        val clientContext = request.systemContext?.trim()
        if (!clientContext.isNullOrEmpty()) {
            parts += clientContext
        }

        return parts.joinToString("\n\n")
    }

    private fun isPrivacyAllowed(
        level: ApiPrivacyLevel,
        cloudExplicitlyAllowed: Boolean
    ): Boolean = when (level) {
        ApiPrivacyLevel.UNKNOWN -> false
        ApiPrivacyLevel.NORMAL -> true
        ApiPrivacyLevel.PRIVATE -> cloudExplicitlyAllowed || privacyPolicy.allowPrivate
        ApiPrivacyLevel.SENSITIVE -> cloudExplicitlyAllowed || privacyPolicy.allowSensitive
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
        val record = AiUsageRecord(
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
        // AR-05: предпочитаем асинхронный pipeline; если он не подключён
        // (тесты/старый wiring), используем синхронный repository.
        if (usageTracker != null) {
            usageTracker.record(record)
        } else {
            // fallback path (should be avoided in production)
            usageRepository?.record(record)
        }
    }
}
