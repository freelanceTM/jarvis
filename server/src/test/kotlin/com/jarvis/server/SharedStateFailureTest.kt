package com.jarvis.server

import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.persistence.PostgresSingleInstanceGuard
import com.jarvis.server.ratelimit.PostgresRateLimiter
import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.JdbcUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

class SharedStateFailureTest {
    private val unavailableDataSource = Proxy.newProxyInstance(
        DataSource::class.java.classLoader,
        arrayOf(DataSource::class.java)
    ) { _, method, _ ->
        if (method.name == "toString") "UnavailableDataSource"
        else throw SQLException("shared store unavailable")
    } as DataSource

    @Test
    fun `shared state outage never turns rate limiting into unlimited access`() {
        val failure = runCatching {
            PostgresRateLimiter(
                unavailableDataSource,
                "failure_test",
                RateLimitConfig(1, 1)
            ).check("client")
        }.exceptionOrNull()

        assertTrue(failure is SQLException)
    }

    @Test
    fun `usage outage is visible rather than silently dropping accounting`() = runBlocking {
        val failure = runCatching {
            JdbcUsageRepository(unavailableDataSource).record(
                AiUsageRecord(
                    requestId = "request",
                    clientId = "client",
                    provider = null,
                    model = null,
                    latencyMs = 0,
                    inputTokens = null,
                    outputTokens = null,
                    totalTokens = null,
                    success = false,
                    errorCode = "TEST",
                    promptChars = 0,
                    responseChars = 0,
                    timestamp = Instant.EPOCH
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is SQLException)
    }

    @Test
    fun `single instance guard cannot fail open when shared store is unavailable`() {
        val failure = runCatching {
            PostgresSingleInstanceGuard.acquire(unavailableDataSource)
        }.exceptionOrNull()

        assertTrue(failure is SQLException)
    }
}
