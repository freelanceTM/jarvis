package com.jarvis.server

import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.ratelimit.PostgresRateLimiter
import com.jarvis.server.ratelimit.RateLimitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PostgresRateLimiterTest : PostgresTestSupport() {
    @Test
    fun `concurrent limits are shared across limiter instances`() {
        val first = PostgresRateLimiter(dataSource, "redeem_test", RateLimitConfig(10, 100)) { 1_000L }
        val second = PostgresRateLimiter(dataSource, "redeem_test", RateLimitConfig(10, 100)) { 1_000L }
        val pool = Executors.newFixedThreadPool(20)
        try {
            val results = pool.invokeAll(
                List(100) { index -> Callable { (if (index % 2 == 0) first else second).check("same-ip") } }
            ).map { it.get() }
            assertEquals(10, results.count { it is RateLimitDecision.Allowed })
            assertEquals(90, results.count { it is RateLimitDecision.Limited })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `persistent limiter resets exact identity without affecting another`() {
        val limiter = PostgresRateLimiter(dataSource, "validate_test", RateLimitConfig(1, 10)) { 5_000L }
        assertTrue(limiter.check("account-a") is RateLimitDecision.Allowed)
        assertTrue(limiter.check("account-a") is RateLimitDecision.Limited)
        assertTrue(limiter.check("account-b") is RateLimitDecision.Allowed)
        limiter.reset("account-a")
        assertTrue(limiter.check("account-a") is RateLimitDecision.Allowed)
    }

    @Test
    fun `rate limit survives limiter recreation and expires by TTL window`() {
        var now = 10_000L
        val first = PostgresRateLimiter(dataSource, "restart_test", RateLimitConfig(1, 10)) { now }
        assertTrue(first.check("client") is RateLimitDecision.Allowed)

        val recreated = PostgresRateLimiter(dataSource, "restart_test", RateLimitConfig(1, 10)) { now }
        assertTrue(recreated.check("client") is RateLimitDecision.Limited)

        now += 60_001L
        assertTrue(recreated.check("client") is RateLimitDecision.Allowed)
    }
}
