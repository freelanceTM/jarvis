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
import com.jarvis.server.provider.DefaultProviderSelectionPolicy
import com.jarvis.server.provider.ProviderCapabilities
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderResult
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.privacy.PrivacyClassification
import com.jarvis.server.privacy.PrivacyReason
import com.jarvis.server.privacy.ServerPrivacyClassifier
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
        validation: ValidationConfig = ValidationConfig(),
        entitlementAllowed: Boolean = true,
        privacyClassifier: ServerPrivacyClassifier =
            com.jarvis.server.privacy.PromptPrivacyClassifier
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
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
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
            metrics = metrics,
            privacyClassifier = privacyClassifier
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
            json = json,
            entitlementChecker = { entitlementAllowed }
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
        requiresWeb: Boolean = false,
        history: String? = null
    ): String {
        val base = StringBuilder()
        base.append("""{"text":"$text","source":"VOICE","privacyLevel":"$privacy","requiresWeb":$requiresWeb""")
        if (history != null) {
            base.append(""","history":$history""")
        }
        base.append("}")
        return base.toString()
    }

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

    @Test
    fun `authenticated client without server entitlement cannot execute AI`() = runBlocking {
        val h = harness(entitlementAllowed = false)

        val response = h.handler.handle(post(requestBody()))

        assertEquals(402, response.status)
        assertEquals(ApiErrorCode.PAYMENT_REQUIRED.name, errorOf(response.body).error.code)
        assertEquals(0, h.providers[0].calls.get())
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

    @Test
    fun `AI responses are never cacheable`() = runBlocking {
        val h = harness()

        val success = h.handler.handle(post(requestBody()))
        val blocked = h.handler.handle(post(requestBody(privacy = "SENSITIVE")))

        assertEquals("no-store", success.headers["Cache-Control"])
        assertEquals("no-store", blocked.headers["Cache-Control"])
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

    @Test
    fun `normal-labelled credential is detected and blocked server side`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(
            post(requestBody(text = "мой пароль от банка: 4821-secret", privacy = "NORMAL"))
        )

        assertEquals(403, response.status)
        assertEquals(ApiErrorCode.PRIVACY_POLICY_VIOLATION.name, errorOf(response.body).error.code)
        assertEquals(0, h.providers[0].calls.get())
    }

    @Test
    fun `sensitive system context is blocked before provider`() = runBlocking {
        val h = harness()
        val body = """{"text":"ordinary request","source":"CHAT","privacyLevel":"NORMAL","systemContext":"password=context-secret"}"""

        val response = h.handler.handle(post(body))

        assertEquals(403, response.status)
        assertEquals(ApiErrorCode.PRIVACY_POLICY_VIOLATION.name, errorOf(response.body).error.code)
        assertEquals(0, h.providers[0].calls.get())
    }

    @Test
    fun `classifier exception is unknown and fails closed before all fallback providers`() = runBlocking {
        val providers = listOf(
            FakeAiProvider.ok(ProviderId.GROQ, "must not run"),
            FakeAiProvider.ok(ProviderId.GEMINI, "must not run")
        )
        val h = harness(
            providers = providers,
            privacyClassifier = ServerPrivacyClassifier { throw IllegalStateException("classifier down") }
        )

        val response = h.handler.handle(post(requestBody(privacy = "NORMAL")))

        assertEquals(403, response.status)
        assertEquals(ApiErrorCode.PRIVACY_POLICY_VIOLATION.name, errorOf(response.body).error.code)
        assertTrue(h.providers.all { it.calls.get() == 0 })
    }

    @Test
    fun `classifier unknown result is not treated as normal`() = runBlocking {
        val h = harness(
            privacyClassifier = ServerPrivacyClassifier {
                PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED)
            }
        )

        val response = h.handler.handle(post(requestBody(privacy = "NORMAL")))

        assertEquals(403, response.status)
        assertEquals(0, h.providers[0].calls.get())
    }

    @Test
    fun `missing client privacy metadata requires successful server classification`() = runBlocking {
        val normal = harness()
        val normalBody = """{"text":"ordinary astronomy question","source":"CHAT"}"""
        assertEquals(200, normal.handler.handle(post(normalBody)).status)
        assertEquals(1, normal.providers[0].calls.get())

        val failed = harness(
            privacyClassifier = ServerPrivacyClassifier { throw IllegalStateException("down") }
        )
        assertEquals(403, failed.handler.handle(post(normalBody)).status)
        assertEquals(0, failed.providers[0].calls.get())
    }

    @Test
    fun `educational password question is not a privacy false positive`() = runBlocking {
        val h = harness()

        val response = h.handler.handle(
            post(requestBody(text = "как безопасно сменить пароль", privacy = "NORMAL"))
        )

        assertEquals(200, response.status)
        assertEquals(1, h.providers[0].calls.get())
    }

    @Test
    fun `restricted cloud requires explicit per-request consent`() = runBlocking {
        val h = harness()
        val body = """{"text":"password=actual-secret","source":"CHAT","privacyLevel":"NORMAL","cloudExplicitlyAllowed":true}"""

        val response = h.handler.handle(post(body))

        assertEquals(200, response.status)
        assertEquals(1, h.providers[0].calls.get())
    }

    @Test
    fun `explicit consent never overrides unknown classifier failure`() = runBlocking {
        val providers = listOf(
            FakeAiProvider.ok(ProviderId.GROQ, "must not run"),
            FakeAiProvider.ok(ProviderId.GEMINI, "must not run")
        )
        val h = harness(
            providers = providers,
            privacyClassifier = ServerPrivacyClassifier { throw IllegalStateException("down") }
        )
        val body = """{"text":"ordinary request","privacyLevel":"SENSITIVE","cloudExplicitlyAllowed":true}"""

        val response = h.handler.handle(post(body))

        assertEquals(403, response.status)
        assertTrue(h.providers.all { it.calls.get() == 0 })
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
        // Никаких внутренних деталей наружу. Проверяем user-facing message,
        // а не случайный UUID requestId, который законно может содержать "500".
        assertFalse(error.message.contains("500"))
        assertFalse(error.message.contains("TIMEOUT"))
        assertFalse(error.message.lowercase().contains("exception"))
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

    @Test
    fun `known endpoint with wrong method returns 405`() = runBlocking {
        val h = harness()
        for ((method, path) in listOf(
            "POST" to JarvisApiHandler.PATH_HEALTH,
            "GET" to JarvisApiHandler.PATH_EXECUTE,
            "POST" to JarvisApiHandler.PATH_ADMIN_METRICS
        )) {
            val response = h.handler.handle(HttpRequestContext(method, path, null, "", 0))
            assertEquals("$method $path", 405, response.status)
        }
    }

    @Test
    fun `UTF-8 byte size is enforced even when supplied content length is wrong`() = runBlocking {
        val h = harness(validation = ValidationConfig(maxTextLength = 100, maxBodyBytes = 50))
        val body = requestBody(text = "😀".repeat(20))

        // Direct handler callers must not be able to bypass byte limit by
        // reporting a fake Content-Length or relying on UTF-16 String.length.
        val response = h.handler.handle(
            HttpRequestContext("POST", JarvisApiHandler.PATH_EXECUTE, "Bearer $validToken", body, 1)
        )

        assertEquals(413, response.status)
        assertEquals(0, h.providers[0].calls.get())
    }

    @Test
    fun `blank and overlong request ids are replaced rather than reflected`() = runBlocking {
        val h = harness()
        for (badId in listOf("", "x".repeat(65))) {
            val body = """{"text":"test","requestId":"$badId"}"""
            val response = h.handler.handle(post(body))
            val parsed = json.decodeFromString(AiExecutionResponse.serializer(), response.body)
            assertTrue(parsed.requestId.isNotBlank())
            assertTrue(parsed.requestId.length <= 64)
            assertTrue(parsed.requestId != badId)
        }
    }

    @Test
    fun `malformed requests consume rate budget but never reach provider`() = runBlocking {
        val h = harness(rateLimit = RateLimitConfig(perMinute = 1, perDay = 10))

        val malformed = h.handler.handle(post("{bad"))
        val next = h.handler.handle(post(requestBody()))

        assertEquals(400, malformed.status)
        assertEquals(429, next.status)
        assertEquals(0, h.providers[0].calls.get())
    }

    /** CR-03: клиентская история доходит до провайдера как ProviderRequest.history. */
    @Test
    fun `history flows end-to-end from DTO to provider`() = runBlocking {
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "ok")
        val h = harness(providers = listOf(groq))
        val body = requestBody(
            text = "а теперь подробнее?",
            history = """[{"role":"user","content":"привет"},{"role":"assistant","content":"здравствуйте"},{"role":"MODEL","content":"чем могу помочь"}]"""
        )
        val response = h.handler.handle(post(body))
        assertEquals(200, response.status)
        val req = groq.lastRequest!!
        assertEquals("а теперь подробнее?", req.prompt)
        // "MODEL" должен быть нормализован в "assistant".
        val roles = req.history.map { it.role }
        assertEquals(listOf("user", "assistant", "assistant"), roles)
        assertEquals("здравствуйте", req.history[1].content)
    }

    /** CR-03: пустая история работает как раньше — в ProviderRequest.history пусто. */
    @Test
    fun `empty history behaves like pre-CR-03`() = runBlocking {
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "ok")
        val h = harness(providers = listOf(groq))
        val response = h.handler.handle(post(requestBody()))
        assertEquals(200, response.status)
        assertTrue(groq.lastRequest!!.history.isEmpty())
    }

    /** CR-03: невалидная роль в истории → 400. */
    @Test
    fun `invalid history role returns INVALID_REQUEST`() = runBlocking {
        val h = harness()
        val body = requestBody(
            history = """[{"role":"potato","content":"test"}]"""
        )
        val response = h.handler.handle(post(body))
        assertEquals(400, response.status)
    }

    /** CR-16: запрос с requiresWeb=true доходит до Gemini web-capable провайдера. */
    @Test
    fun `requiresWeb request reaches web-capable provider`() = runBlocking {
        val groqNoWeb = FakeAiProvider.ok(ProviderId.GROQ, "groq")
        val geminiWeb = FakeAiProvider(
            id = ProviderId.GEMINI,
            script = listOf(ProviderResult.Success("из сети", "gemini-test", 1, 2, 3)),
            capabilities = ProviderCapabilities(supportsChat = true, supportsWeb = true)
        )
        val h = harness(providers = listOf(groqNoWeb, geminiWeb))
        val response = h.handler.handle(post(requestBody(requiresWeb = true)))
        assertEquals(200, response.status)
        val parsed = json.decodeFromString(AiExecutionResponse.serializer(), response.body)
        assertEquals("из сети", parsed.text)
        assertEquals(0, groqNoWeb.calls.get())
        assertEquals(1, geminiWeb.calls.get())
        // requiresWeb должен дойти до провайдерного запроса.
        assertEquals(true, geminiWeb.lastRequest!!.requiresWeb)
    }
}
