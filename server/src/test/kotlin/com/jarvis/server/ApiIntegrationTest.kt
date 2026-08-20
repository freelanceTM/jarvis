package com.jarvis.server

import com.jarvis.server.api.ApiErrorCode
import com.jarvis.server.api.ApiErrorResponse
import com.jarvis.server.api.AiExecutionResponse
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.AiGenerationConfig
import com.jarvis.server.config.PrivacyPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.JarvisApiHandler
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.AiProvider
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderSelectionPolicy
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.usage.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-тесты полного конвейера (пункт 33 ТЗ):
 *
 * ```
 * HTTP → Authentication → Authorization → Rate Limit → AI Router
 *      → Provider Manager → FakeProvider → Response
 * ```
 *
 * Реальные провайдеры не используются.
 */
class ApiIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val validToken = "test-token-abcdef"
    private val adminToken = "admin-token-abcdef"

    private class Harness(
        val handler: JarvisApiHandler,
        val usage: InMemoryUsageRepository,
        val metrics: Metrics,
        val providers: List<FakeAiProvider>
    )

    private fun cfg(id: ProviderId, priority: Int) = ProviderConfig(
        id = id, enabled = true, priority = priority, apiKey = "k",
        model = "test-model", baseUrl = "https://example.invalid",
        connectTimeoutMs = 1000, requestTimeoutMs = 2000
    )

    private fun harness(
        providers: List<FakeAiProvider> = listOf(FakeAiProvider.ok(ProviderId.GROQ, "Квантовая запутанность — это...")),
        rateLimit: RateLimitConfig = RateLimitConfig(perMinute = 100, perDay = 1000),
        privacy: PrivacyPolicyConfig = PrivacyPolicyConfig(),
        validation: ValidationConfig = ValidationConfig()
    ): Harness {
        val configs = providers.associate { it.id to cfg(it.id, it.id.ordinal + 1) }
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val metrics = Metrics()
        val logger = ConsoleStructuredLogger(sink = {})

        val manager = ProviderManager(
            providers = providers as List<AiProvider>,
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = ProviderSelectionPolicy(configs, health),
            logger = logger,
            metrics = metrics
        )

        val usage = InMemoryUsageRepository()

        val router = AiRouter(
            providerManager = manager,
            usageRepository = usage,
            validation = validation,
            privacyPolicy = privacy,
            generation = AiGenerationConfig(),
            logger = logger,
            metrics = metrics
        )

        val handler = JarvisApiHandler(
            authenticator = TokenAuthenticator(
                mapOf(validToken to "android-1", adminToken to "admin-1")
            ) { clientId -> if (clientId == "admin-1") ClientTier.ADMIN else ClientTier.FREE },
            authorizer = TierAuthorizer(),
            rateLimiter = SlidingWindowRateLimiter(rateLimit),
            router = router,
            validation = validation,
            logger = logger,
            metrics = metrics,
            json = json
        )

        return Harness(handler, usage, metrics, providers)
    }

    private fun post(
        body: String,
        token: String? = validToken,
        path: String = JarvisApiHandler.PATH_EXECUTE
    ) = HttpRequestContext(
        method = "POST",
        path = path,
        authorizationHeader = token?.let { "Bearer $it" },
        body = body,
        contentLength = body.length.toLong()
    )

    private fun requestBody(
        text: String = "Объясни квантовую запутанность",
        privacy: String = "NORMAL",
        requiresWeb: Boolean = false
    ) = """{"text":"$text","source":"VOICE","privacyLevel":"$privacy","requiresWeb":$requiresWeb}"""

    private fun errorOf(body: String): ApiErrorResponse =
        json.decodeFromString(ApiErrorResponse.serializer(), body)

    // ------------------------------------------------------------------ tests

    /** Полный happy-path: Android → API → Router → Provider → ответ. */
    @Test
    fun `valid request returns cloud answer`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post(requestBody()))

        assertEquals(200, response.status)
        val parsed = json.decodeFromString(AiExecutionResponse.serializer(), response.body)
        assertTrue(parsed.success)
        assertEquals("Квантовая запутанность — это...", parsed.text)
        assertTrue("requestId обязателен", parsed.requestId.isNotBlank())
        // Провайдер клиенту не раскрывается.
        assertFalse("provider не должен утекать", response.body.contains("GROQ", ignoreCase = true))
    }

    /** Нет токена → 401. */
    @Test
    fun `missing token returns 401`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post(requestBody(), token = null))

        assertEquals(401, response.status)
        assertEquals(ApiErrorCode.UNAUTHORIZED.name, errorOf(response.body).error.code)
        assertEquals("провайдер не должен вызываться", 0, h.providers[0].calls.get())
    }

    /** Неизвестный токен → 401. */
    @Test
    fun `invalid token returns 401`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post(requestBody(), token = "wrong-token"))

        assertEquals(401, response.status)
    }

    /** Нет прав на админ-эндпоинт → 403. */
    @Test
    fun `forbidden client cannot access admin metrics`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(
            HttpRequestContext("GET", JarvisApiHandler.PATH_ADMIN_METRICS, "Bearer $validToken", "", 0)
        )

        assertEquals(403, response.status)
        assertEquals(ApiErrorCode.FORBIDDEN.name, errorOf(response.body).error.code)
    }

    /** ADMIN-клиент получает доступ к метрикам. */
    @Test
    fun `admin client can access metrics`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(
            HttpRequestContext("GET", JarvisApiHandler.PATH_ADMIN_METRICS, "Bearer $adminToken", "", 0)
        )

        assertEquals(200, response.status)
    }

    /** Превышение лимита → 429 + Retry-After. */
    @Test
    fun `rate limit exceeded returns 429 with retry after`() = runBlocking {
        val h = harness(rateLimit = RateLimitConfig(perMinute = 2, perDay = 100))

        h.handler.handle(post(requestBody()))
        h.handler.handle(post(requestBody()))
        val third = h.handler.handle(post(requestBody()))

        assertEquals(429, third.status)
        assertEquals(ApiErrorCode.RATE_LIMITED.name, errorOf(third.body).error.code)
        assertNotNull("Retry-After обязателен", third.headers["Retry-After"])
        assertEquals(2, h.providers[0].calls.get())
    }

    /** SENSITIVE не уходит провайдеру. */
    @Test
    fun `sensitive request is blocked by privacy policy`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post(requestBody(privacy = "SENSITIVE")))

        assertEquals(403, response.status)
        assertEquals(
            ApiErrorCode.PRIVACY_POLICY_VIOLATION.name,
            errorOf(response.body).error.code
        )
        assertEquals("провайдер не должен вызываться", 0, h.providers[0].calls.get())
    }

    /** PRIVATE тоже блокируется политикой по умолчанию. */
    @Test
    fun `private request is blocked by default policy`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post(requestBody(privacy = "PRIVATE")))

        assertEquals(403, response.status)
        assertEquals(0, h.providers[0].calls.get())
    }

    /** Явное разрешение в конфиге допускает PRIVATE. */
    @Test
    fun `private request allowed when policy permits`() = runBlocking {
        val h = harness(privacy = PrivacyPolicyConfig(allowPrivate = true))

        val response = h.handler.handle(post(requestBody(privacy = "PRIVATE")))

        assertEquals(200, response.status)
        assertEquals(1, h.providers[0].calls.get())
    }

    /** Fallback виден на уровне API: primary упал, ответ всё равно 200. */
    @Test
    fun `primary provider failure falls back and still returns 200`() = runBlocking {
        val h = harness(
            providers = listOf(
                FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, 500),
                FakeAiProvider.ok(ProviderId.GEMINI, "ответ резервного провайдера")
            )
        )

        val response = h.handler.handle(post(requestBody()))

        assertEquals(200, response.status)
        val parsed = json.decodeFromString(AiExecutionResponse.serializer(), response.body)
        assertEquals("ответ резервного провайдера", parsed.text)
    }

    /** Все провайдеры недоступны → ALL_PROVIDERS_UNAVAILABLE, без деталей. */
    @Test
    fun `all providers down returns normalized error`() = runBlocking {
        val h = harness(
            providers = listOf(
                FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, 500),
                FakeAiProvider.failing(ProviderId.GEMINI, ProviderFailureKind.TIMEOUT),
                FakeAiProvider.failing(ProviderId.OPENROUTER, ProviderFailureKind.CONNECTION)
            )
        )

        val response = h.handler.handle(post(requestBody()))

        assertEquals(503, response.status)
        val error = errorOf(response.body).error
        assertEquals(ApiErrorCode.ALL_PROVIDERS_UNAVAILABLE.name, error.code)
        // Никаких внутренних деталей наружу.
        assertFalse(response.body.contains("500"))
        assertFalse(response.body.contains("TIMEOUT"))
        assertFalse(response.body.lowercase().contains("exception"))
    }

    /** Единственный упавший провайдер маппится в конкретный код. */
    @Test
    fun `single provider timeout maps to provider timeout`() = runBlocking {
        val h = harness(
            providers = listOf(FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.TIMEOUT))
        )

        val response = h.handler.handle(post(requestBody()))

        assertEquals(504, response.status)
        assertEquals(ApiErrorCode.PROVIDER_TIMEOUT.name, errorOf(response.body).error.code)
    }

    /** Пустой текст → INVALID_REQUEST. */
    @Test
    fun `empty text is rejected`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post(requestBody(text = "")))

        assertEquals(400, response.status)
        assertEquals(ApiErrorCode.INVALID_REQUEST.name, errorOf(response.body).error.code)
        assertEquals(0, h.providers[0].calls.get())
    }

    /** Слишком длинный текст → INVALID_REQUEST. */
    @Test
    fun `oversized text is rejected`() = runBlocking {
        val h = harness(validation = ValidationConfig(maxTextLength = 50, maxBodyBytes = 100_000))

        val response = h.handler.handle(post(requestBody(text = "а".repeat(200))))

        assertEquals(400, response.status)
        assertEquals(0, h.providers[0].calls.get())
    }

    /** Слишком большое тело → 413. */
    @Test
    fun `oversized body is rejected`() = runBlocking {
        val h = harness(validation = ValidationConfig(maxTextLength = 10_000, maxBodyBytes = 100))

        val response = h.handler.handle(post(requestBody(text = "а".repeat(500))))

        assertEquals(413, response.status)
        assertEquals(ApiErrorCode.PAYLOAD_TOO_LARGE.name, errorOf(response.body).error.code)
    }

    /** Некорректный JSON → INVALID_REQUEST без утечки деталей парсера. */
    @Test
    fun `malformed json returns invalid request`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(post("{not json"))

        assertEquals(400, response.status)
        assertFalse(response.body.lowercase().contains("serializ"))
    }

    /** Клиент НЕ может выбрать провайдера — поле игнорируется. */
    @Test
    fun `client cannot choose provider`() = runBlocking {
        val h = harness(
            providers = listOf(
                FakeAiProvider.ok(ProviderId.GROQ, "от groq"),
                FakeAiProvider.ok(ProviderId.GEMINI, "от gemini")
            )
        )

        val body = """{"text":"тест","source":"CHAT","privacyLevel":"NORMAL",""" +
            """"requiresWeb":false,"provider":"gemini"}"""
        val response = h.handler.handle(post(body))

        assertEquals(200, response.status)
        val parsed = json.decodeFromString(AiExecutionResponse.serializer(), response.body)
        // Выбор остался за сервером: сработал приоритетный GROQ.
        assertEquals("от groq", parsed.text)
    }

    /** requestId клиента прокидывается сквозь весь конвейер. */
    @Test
    fun `client request id is propagated`() = runBlocking {
        val h = harness()
        val body = """{"text":"тест","source":"CHAT","privacyLevel":"NORMAL",""" +
            """"requiresWeb":false,"requestId":"client-req-42"}"""

        val response = h.handler.handle(post(body))

        val parsed = json.decodeFromString(AiExecutionResponse.serializer(), response.body)
        assertEquals("client-req-42", parsed.requestId)
        assertEquals("client-req-42", h.usage.all().first().requestId)
    }

    /** Usage пишется и на успех, и на ошибку. */
    @Test
    fun `usage is recorded for success and failure`() = runBlocking {
        val h = harness(
            providers = listOf(FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.TIMEOUT))
        )
        h.handler.handle(post(requestBody()))

        val records = h.usage.all()
        assertEquals(1, records.size)
        val record = records.first()
        assertFalse(record.success)
        assertEquals(ApiErrorCode.PROVIDER_TIMEOUT.name, record.errorCode)
        assertEquals("android-1", record.clientId)

        val ok = harness()
        ok.handler.handle(post(requestBody()))
        val okRecord = ok.usage.all().first()
        assertTrue(okRecord.success)
        assertEquals(30L, okRecord.totalTokens)
        assertEquals("GROQ", okRecord.provider)
    }

    /** Usage НЕ хранит текст промпта — только его длину (privacy). */
    @Test
    fun `usage does not store prompt text`() = runBlocking {
        val h = harness()
        val secret = "мой секретный вопрос про здоровье"
        h.handler.handle(post(requestBody(text = secret)))

        val record = h.usage.all().first()
        assertEquals(secret.length, record.promptChars)
        // В записи нет ни одного поля с самим текстом.
        assertFalse(record.toString().contains("секретный"))
    }

    /** Метрики считают запросы и ошибки. */
    @Test
    fun `metrics track requests`() = runBlocking {
        val h = harness()
        h.handler.handle(post(requestBody()))
        h.handler.handle(post(requestBody(privacy = "SENSITIVE")))

        val snapshot = h.metrics.snapshot()
        assertEquals(2L, snapshot["requests_total"])
        assertEquals(1L, snapshot["requests_success"])
        assertEquals(1L, snapshot["privacy_blocked_total"])
    }

    /** Health-эндпоинт доступен без авторизации. */
    @Test
    fun `health endpoint is public`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(
            HttpRequestContext("GET", JarvisApiHandler.PATH_HEALTH, null, "", 0)
        )

        assertEquals(200, response.status)
    }

    /** Неизвестный путь → 404, без раскрытия структуры. */
    @Test
    fun `unknown path returns 404`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(
            HttpRequestContext("GET", "/v1/secret", "Bearer $validToken", "", 0)
        )

        assertEquals(404, response.status)
    }
}
