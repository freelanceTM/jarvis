package com.jarvis.server

import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.JarvisApiHandler
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.DefaultProviderSelectionPolicy
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.usage.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1: тесты Prometheus-экспорта (GET /v1/admin/metrics/prometheus).
 *
 * Контракт:
 *  - тот же уровень защиты, что и JSON /v1/admin/metrics (Bearer + VIEW_ADMIN);
 *  - 200 + Content-Type text/plain; version=0.0.4 для admin-клиента;
 *  - счётчики общего потока и per-provider строки с labels присутствуют
 *    и соответствуют записанным значениям.
 */
class PrometheusMetricsEndpointTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val validToken = "test-token-abcdef"
    private val adminToken = "admin-token-abcdef"

    private fun handlerWith(metrics: Metrics): JarvisApiHandler {
        val logger = ConsoleStructuredLogger(sink = {})
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val provider = FakeAiProvider.ok(ProviderId.GROQ, "ответ")
        val configs = mapOf(
            ProviderId.GROQ to ProviderConfig(
                id = ProviderId.GROQ, enabled = true, priority = 1, apiKey = "k",
                model = "test-model", baseUrl = "https://example.invalid",
                connectTimeoutMs = 1000, requestTimeoutMs = 2000
            )
        )
        val manager = ProviderManager(
            providers = listOf(provider),
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
            logger = logger,
            metrics = metrics
        )
        val router = AiRouter(
            providerManager = manager,
            usageRepository = InMemoryUsageRepository(),
            validation = ValidationConfig(),
            privacyPolicy = com.jarvis.server.config.PrivacyPolicyConfig(),
            generation = com.jarvis.server.config.AiGenerationConfig(),
            logger = logger,
            metrics = metrics
        )
        return JarvisApiHandler(
            authenticator = TokenAuthenticator(
                mapOf(validToken to "android-1", adminToken to "admin-1")
            ) { clientId -> if (clientId == "admin-1") ClientTier.ADMIN else ClientTier.FREE },
            authorizer = TierAuthorizer(),
            rateLimiter = SlidingWindowRateLimiter(RateLimitConfig(perMinute = 100, perDay = 1000)),
            router = router,
            validation = ValidationConfig(),
            logger = logger,
            metrics = metrics,
            json = json,
            prometheusMetricsProvider = { metrics.prometheus() }
        )
    }

    private fun get(path: String, token: String?) = HttpRequestContext(
        method = "GET",
        path = path,
        authorizationHeader = token?.let { "Bearer $it" },
        body = "",
        contentLength = 0
    )

    @Test
    fun `missing token returns 401`() {
        val response = runBlocking { handlerWith(Metrics()).handle(get(JarvisApiHandler.PATH_ADMIN_METRICS_PROMETHEUS, null)) }
        assertEquals(401, response.status)
    }

    @Test
    fun `non-admin client is forbidden`() {
        val response = runBlocking { handlerWith(Metrics()).handle(get(JarvisApiHandler.PATH_ADMIN_METRICS_PROMETHEUS, validToken)) }
        assertEquals(403, response.status)
    }

    @Test
    fun `admin client receives prometheus text with counters`() {
        val metrics = Metrics()
        metrics.recordRequest()
        metrics.recordSuccess(tokens = 42)
        metrics.recordRateLimited()
        metrics.recordProviderSuccess(ProviderId.GROQ, latencyMs = 321)
        metrics.recordProviderFailure(ProviderId.GROQ, kind = ProviderFailureKind.TIMEOUT)
        metrics.increment("usage_dropped")

        val response = runBlocking { handlerWith(metrics).handle(get(JarvisApiHandler.PATH_ADMIN_METRICS_PROMETHEUS, adminToken)) }

        assertEquals(200, response.status)
        assertEquals("text/plain; version=0.0.4; charset=utf-8", response.headers["Content-Type"])

        val body = response.body
        assertTrue(body.contains("# TYPE jarvis_requests_total counter"))
        assertTrue(body.contains("jarvis_requests_total 1"))
        assertTrue(body.contains("jarvis_requests_success_total 1"))
        assertTrue(body.contains("jarvis_tokens_total 42"))
        assertTrue(body.contains("jarvis_requests_rate_limited_total 1"))
        assertTrue(body.contains("jarvis_provider_success_total{provider=\"GROQ\"} 1"))
        assertTrue(body.contains("jarvis_provider_latency_ms_sum{provider=\"GROQ\"} 321"))
        assertTrue(body.contains("jarvis_provider_failure_kind_total{kind=\"TIMEOUT\"} 1"))
        assertTrue(body.contains("jarvis_named_usage_dropped 1"))
        // HELP/TYPE идут перед значениями; каждая строка значений не пуста.
        assertTrue(body.lines().none { it.startsWith("jarvis_") && it.substringAfter(' ').isBlank() })
    }

    @Test
    fun `empty metrics still render valid prometheus output`() {
        val response = runBlocking { handlerWith(Metrics()).handle(get(JarvisApiHandler.PATH_ADMIN_METRICS_PROMETHEUS, adminToken)) }
        assertEquals(200, response.status)
        assertTrue(response.body.contains("jarvis_requests_total 0"))
    }
}
