package com.jarvis.server.http

import com.jarvis.server.api.AiExecutionRequest
import com.jarvis.server.api.AiExecutionResponse
import com.jarvis.server.api.ApiErrorCode
import com.jarvis.server.auth.AuthResult
import com.jarvis.server.auth.Authenticator
import com.jarvis.server.auth.Authorizer
import com.jarvis.server.auth.Permission
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.observability.Metrics
import com.jarvis.server.observability.StructuredLogger
import com.jarvis.server.ratelimit.RateLimitDecision
import com.jarvis.server.ratelimit.RateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.router.RouterResult
import kotlinx.serialization.json.Json
import kotlin.text.Charsets
import java.util.UUID
/** Входящий HTTP-запрос, независимый от конкретного HTTP-сервера. */
data class HttpRequestContext(
    val method: String,
    val path: String,
    val authorizationHeader: String?,
    val body: String,
    val contentLength: Long,
    val headers: Map<String, String> = emptyMap(),
    val remoteAddress: String? = null,
    /** Raw query string (после '?', без него). null = query отсутствует. */
    val rawQuery: String? = null,
    val scheme: String = "http",
    val host: String? = null,
    val viaTrustedProxy: Boolean = false,
    /**
     * CR-06: wall-clock deadline (epoch ms) до которого обработчик должен
     * завершиться. null — использовать серверный default. Провайдер-менеджер
     * использует это значение для early-out при исчерпании бюджета между
     * провайдерами/попытками.
     */
    val deadlineEpochMs: Long? = null
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
}

/** Ответ, готовый к сериализации. */
data class HttpResponseContext(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = mapOf("Cache-Control" to "no-store")
)

/**
 * Обработчик JARVIS API.
 *
 * Порядок middleware строго соответствует ТЗ (пункт 6: auth ДО AI Router):
 *
 * ```
 * Request
 *   → размер тела
 *   → Authentication  (кто это)
 *   → Authorization   (что можно)
 *   → Rate Limit      (сколько можно)
 *   → AI Router
 * ```
 *
 * Выделен из транспортного слоя, чтобы весь конвейер тестировался без
 * поднятия реального сокета.
 */
class JarvisApiHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val rateLimiter: RateLimiter,
    private val router: AiRouter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val json: Json,
    private val healthProvider: () -> String = { "{}" },
    private val metricsProvider: () -> String = { "{}" },
    /** P1-1: Prometheus text format для PATH_ADMIN_METRICS_PROMETHEUS. */
    private val prometheusMetricsProvider: () -> String = { "" },
    private val entitlementChecker: (com.jarvis.server.auth.AuthenticatedClient) -> Boolean = { true },
    private val extensionHandler: suspend (HttpRequestContext) -> HttpResponseContext? = { null },
    /**
     * CR-15: внешняя ссылка на ту же health-лямбду, чтобы отдельный
     * health-kickoff в Main мог использовать её без DB/AI-пула. По умолчанию
     * совпадает с healthProvider (нужно для тестов, которые создают
     * JarvisApiHandler напрямую).
     */
    private val healthProviderFunc: (() -> String)? = null
) {
    /** Внешний (main-kickoff) вход в health — то же самое, что и PATH_HEALTH. */
    fun healthSnapshot(): String = (healthProviderFunc ?: healthProvider)()
    companion object {
        const val PATH_EXECUTE = "/v1/ai/execute"
        const val PATH_HEALTH = "/v1/health"
        const val PATH_ADMIN_METRICS = "/v1/admin/metrics"
        const val PATH_ADMIN_METRICS_PROMETHEUS = "/v1/admin/metrics/prometheus"
    }

    suspend fun handle(request: HttpRequestContext): HttpResponseContext {
        // Порядок middleware тот же, но для POST /v1/ai/execute парсим
        // тело ровно один раз (M-01): requestId берём из уже
        // распарсенного объекта, size-check идёт по body.length (строка
        // уже в памяти, вторая конвертация в ByteArray бессмысленна).
        val path = request.path
        val method = request.method

        return when {
            path == PATH_HEALTH && method == "GET" ->
                HttpResponseContext(200, healthProvider())

            path == PATH_ADMIN_METRICS && method == "GET" ->
                handleAdminMetrics(request, newRequestId())

            path == PATH_ADMIN_METRICS_PROMETHEUS && method == "GET" ->
                handleAdminMetricsPrometheus(request)

            path == PATH_EXECUTE && method == "POST" ->
                handleExecute(request)

            path == PATH_EXECUTE ||
                path == PATH_ADMIN_METRICS ||
                path == PATH_ADMIN_METRICS_PROMETHEUS ||
                path == PATH_HEALTH ->
                error(ApiErrorCode.INVALID_REQUEST, newRequestId(), 405)

            else -> extensionHandler(request)
                ?: error(ApiErrorCode.INVALID_REQUEST, newRequestId(), 404)
        }
    }

    private suspend fun handleExecute(
        request: HttpRequestContext
    ): HttpResponseContext {
        // ------------------------------------------------- 0. Размер тела
        // M-01: content-length доверяем как upper-bound. Реальную проверку
        // делаем по UTF-8 байтам — String.length считает UTF-16 code units
        // (для CJK/кириллицы занижает размер в 2-3 раза), поэтому для
        // accurate size-check нужна разовая конвертация в UTF-8 byte count.
        // ByteArray создаётся один раз и отпускается; парсинг идёт
        // напрямую из строки без повторного выделения.
        val bodySize = request.body.toByteArray(Charsets.UTF_8).size.toLong()
        if (request.contentLength > validation.maxBodyBytes ||
            bodySize > validation.maxBodyBytes
        ) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, newRequestId())
        }

        // ------------------------------------------------- 1. Парсинг тела (M-01: ОДИН раз)
        // Anti-DoS: parse выполняется сразу, но 400 возвращается только ПОСЛЕ
        // rate limit — malformed-запросы расходуют бюджет клиента, а не
        // бесплатный parse-flood (тест: malformed requests consume rate
        // budget but never reach provider).
        var parseError: String? = null
        val parsed: AiExecutionRequest? = try {
            json.decodeFromString(AiExecutionRequest.serializer(), request.body)
        } catch (e: Exception) {
            parseError = e.javaClass.simpleName
            null
        }

        // Сквозной requestId: клиентский (если валидный), либо свой.
        val requestId = parsed?.requestId
            ?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: newRequestId()

        // ------------------------------------------------- 2. Authentication
        val client = when (val auth = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> auth.client
            AuthResult.MissingCredentials, AuthResult.InvalidCredentials -> {
                metrics.recordUnauthorized()
                logger.warn("unauthorized request", "requestId" to requestId, "path" to request.path)
                return error(ApiErrorCode.UNAUTHORIZED, requestId)
            }
        }

        // ------------------------------------------------- 3. Authorization
        if (!authorizer.isAllowed(client, Permission.EXECUTE_AI)) {
            logger.warn(
                "forbidden request",
                "requestId" to requestId,
                "clientId" to client.clientId,
                "tier" to client.tier.name
            )
            return error(ApiErrorCode.FORBIDDEN, requestId)
        }

        // Entitlement is server-side. Authentication alone never proves payment.
        if (client.tier !in setOf(
                com.jarvis.server.auth.ClientTier.ADMIN,
                com.jarvis.server.auth.ClientTier.INTERNAL
            ) && !entitlementChecker(client)
        ) {
            return error(ApiErrorCode.PAYMENT_REQUIRED, requestId)
        }

        // ------------------------------------------------- 4. Rate limit
        when (val decision = rateLimiter.check(client.clientId)) {
            is RateLimitDecision.Limited -> {
                metrics.recordRateLimited()
                logger.warn(
                    "rate limited",
                    "requestId" to requestId,
                    "clientId" to client.clientId,
                    "scope" to decision.scope
                )
                return error(
                    ApiErrorCode.RATE_LIMITED,
                    requestId,
                    headers = mapOf("Retry-After" to decision.retryAfterSeconds.toString())
                )
            }
            RateLimitDecision.Allowed -> Unit
        }

        // 400 отдаём только после rate limit (см. комментарий к парсингу выше).
        if (parsed == null) {
            logger.warn(
                "malformed request body",
                "requestId" to requestId,
                "error" to (parseError ?: "unknown")
            )
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }

        // ------------------------------------------------- 5. AI Router
        // CR-06: прокидываем deadline из HttpRequestContext (выставлен
        // Main.kt на основании X-Request-Deadline / server default).
        return when (val result = router.execute(parsed, client, requestId, request.deadlineEpochMs)) {
            is RouterResult.Success -> {
                val payload = AiExecutionResponse(text = result.text, requestId = result.requestId)
                HttpResponseContext(
                    status = 200,
                    body = json.encodeToString(AiExecutionResponse.serializer(), payload)
                )
            }

            is RouterResult.Failure -> error(result.code, result.requestId)
        }
    }

    private fun handleAdminMetrics(
        request: HttpRequestContext,
        requestId: String
    ): HttpResponseContext {
        val client = when (val auth = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> auth.client
            else -> {
                metrics.recordUnauthorized()
                return error(ApiErrorCode.UNAUTHORIZED, requestId)
            }
        }

        if (!authorizer.isAllowed(client, Permission.VIEW_ADMIN)) {
            return error(ApiErrorCode.FORBIDDEN, requestId)
        }

        return HttpResponseContext(200, metricsProvider())
    }

    /**
     * P1-1: метрики в Prometheus text format. Тот же уровень защиты, что и
     * JSON-вариант: Bearer + VIEW_ADMIN. Content-Type задаётся явно —
     * Prometheus-парсер требует text/plain; version=0.0.4.
     */
    private fun handleAdminMetricsPrometheus(
        request: HttpRequestContext
    ): HttpResponseContext {
        val client = when (val auth = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> auth.client
            else -> {
                metrics.recordUnauthorized()
                return error(ApiErrorCode.UNAUTHORIZED, newRequestId())
            }
        }

        if (!authorizer.isAllowed(client, Permission.VIEW_ADMIN)) {
            return error(ApiErrorCode.FORBIDDEN, newRequestId())
        }

        return HttpResponseContext(
            200,
            prometheusMetricsProvider(),
            headers = mapOf(
                "Cache-Control" to "no-store",
                "Content-Type" to "text/plain; version=0.0.4; charset=utf-8"
            )
        )
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    private fun error(
        code: ApiErrorCode,
        requestId: String,
        statusOverride: Int? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponseContext = HttpResponseContext(
        status = statusOverride ?: code.httpStatus,
        body = json.encodeToString(
            com.jarvis.server.api.ApiErrorResponse.serializer(),
            code.toResponse(requestId)
        ),
        headers = mapOf("Cache-Control" to "no-store") + headers
    )
}
