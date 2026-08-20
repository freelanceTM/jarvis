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
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.router.RouterResult
import kotlinx.serialization.json.Json
import java.util.UUID

/** Входящий HTTP-запрос, независимый от конкретного HTTP-сервера. */
data class HttpRequestContext(
    val method: String,
    val path: String,
    val authorizationHeader: String?,
    val body: String,
    val contentLength: Long
)

/** Ответ, готовый к сериализации. */
data class HttpResponseContext(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
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
    private val rateLimiter: SlidingWindowRateLimiter,
    private val router: AiRouter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val json: Json,
    private val healthProvider: () -> String = { "{}" },
    private val metricsProvider: () -> String = { "{}" }
) {
    companion object {
        const val PATH_EXECUTE = "/v1/ai/execute"
        const val PATH_HEALTH = "/v1/health"
        const val PATH_ADMIN_METRICS = "/v1/admin/metrics"
    }

    suspend fun handle(request: HttpRequestContext): HttpResponseContext {
        // requestId сквозной: клиентский, либо свой (пункт 20 ТЗ).
        val requestId = extractRequestId(request)

        return when {
            request.path == PATH_HEALTH && request.method == "GET" ->
                HttpResponseContext(200, healthProvider())

            request.path == PATH_ADMIN_METRICS && request.method == "GET" ->
                handleAdminMetrics(request, requestId)

            request.path == PATH_EXECUTE && request.method == "POST" ->
                handleExecute(request, requestId)

            request.path == PATH_EXECUTE || request.path == PATH_ADMIN_METRICS ->
                error(ApiErrorCode.INVALID_REQUEST, requestId, 405)

            else -> error(ApiErrorCode.INVALID_REQUEST, requestId, 404)
        }
    }

    private suspend fun handleExecute(
        request: HttpRequestContext,
        requestId: String
    ): HttpResponseContext {
        // ------------------------------------------------- 0. Размер тела
        if (request.contentLength > validation.maxBodyBytes ||
            request.body.length > validation.maxBodyBytes
        ) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, requestId)
        }

        // ------------------------------------------------- 1. Authentication
        val client = when (val auth = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> auth.client
            AuthResult.MissingCredentials, AuthResult.InvalidCredentials -> {
                metrics.recordUnauthorized()
                logger.warn("unauthorized request", "requestId" to requestId, "path" to request.path)
                return error(ApiErrorCode.UNAUTHORIZED, requestId)
            }
        }

        // ------------------------------------------------- 2. Authorization
        if (!authorizer.isAllowed(client, Permission.EXECUTE_AI)) {
            logger.warn(
                "forbidden request",
                "requestId" to requestId,
                "clientId" to client.clientId,
                "tier" to client.tier.name
            )
            return error(ApiErrorCode.FORBIDDEN, requestId)
        }

        // ------------------------------------------------- 3. Rate limit
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

        // ------------------------------------------------- 4. Парсинг тела
        val parsed = try {
            json.decodeFromString(AiExecutionRequest.serializer(), request.body)
        } catch (e: Exception) {
            // Детали парсинга — только в лог, наружу общий код.
            logger.warn(
                "malformed request body",
                "requestId" to requestId,
                "clientId" to client.clientId,
                "error" to e.javaClass.simpleName
            )
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }

        // ------------------------------------------------- 5. AI Router
        return when (val result = router.execute(parsed, client, requestId)) {
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

    private fun extractRequestId(request: HttpRequestContext): String {
        // Пытаемся достать requestId из тела, не падая на некорректном JSON.
        return try {
            json.decodeFromString(AiExecutionRequest.serializer(), request.body)
                .requestId
                ?.takeIf { it.isNotBlank() && it.length <= 64 }
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

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
        headers = headers
    )
}
