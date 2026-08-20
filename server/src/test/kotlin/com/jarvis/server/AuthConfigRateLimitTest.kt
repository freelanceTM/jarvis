package com.jarvis.server

import com.jarvis.server.auth.AuthResult
import com.jarvis.server.auth.AuthenticatedClient
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.Permission
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.config.AiGenerationConfig
import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.config.ServerConfig
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.ratelimit.RateLimitDecision
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AuthConfigRateLimitTest {

    @Test
    fun `bearer authentication handles casing whitespace missing and invalid values`() {
        val auth = TokenAuthenticator(mapOf("secret-token" to "client-a"))

        assertTrue(auth.authenticate(null) is AuthResult.MissingCredentials)
        assertTrue(auth.authenticate("") is AuthResult.MissingCredentials)
        assertTrue(auth.authenticate("Basic secret-token") is AuthResult.MissingCredentials)
        assertTrue(auth.authenticate("Bearer") is AuthResult.MissingCredentials)
        assertTrue(auth.authenticate("Bearer   ") is AuthResult.MissingCredentials)
        assertTrue(auth.authenticate("Bearer wrong") is AuthResult.InvalidCredentials)

        val result = auth.authenticate("bEaReR   secret-token  ") as AuthResult.Success
        assertEquals("client-a", result.client.clientId)
        assertEquals(ClientTier.FREE, result.client.tier)
    }

    @Test
    fun `authorizer keeps admin permission separate from execution permission`() {
        val authorizer = TierAuthorizer()
        for (tier in ClientTier.entries) {
            val client = AuthenticatedClient("c", tier)
            assertTrue(authorizer.isAllowed(client, Permission.EXECUTE_AI))
            assertEquals(tier == ClientTier.ADMIN, authorizer.isAllowed(client, Permission.VIEW_ADMIN))
        }
    }

    @Test
    fun `server config parses tokens with colon in client id and ignores malformed entries`() {
        val tokenA = "a".repeat(32)
        val tokenB = "b".repeat(32)
        val env = mapOf(
            "PORT" to "9090",
            "JARVIS_CLIENT_TOKENS" to " $tokenA:client:a , malformed, :empty, $tokenB:client-b ",
            "RATE_LIMIT_PER_MINUTE" to "7",
            "ALLOW_PRIVATE_CLOUD" to "YES",
            "AI_TEMPERATURE" to "1.25"
        )

        val config = ServerConfig.fromEnv(env::get)

        assertEquals(9090, config.port)
        assertEquals(mapOf(tokenA to "client:a", tokenB to "client-b"), config.staticClientTokens)
        assertEquals(7, config.rateLimit.perMinute)
        assertTrue(config.privacy.allowPrivate)
        assertEquals(1.25, config.generation.temperature, 0.0)
    }

    @Test
    fun `invalid security and resource boundaries fail fast`() {
        assertThrowsIllegalArgument { RateLimitConfig(perMinute = -1) }
        assertThrowsIllegalArgument { CircuitBreakerConfig(failureThreshold = 0) }
        assertThrowsIllegalArgument { ExecutionPolicyConfig(maxRetriesPerProvider = -1) }
        assertThrowsIllegalArgument { ValidationConfig(maxTextLength = 0) }
        assertThrowsIllegalArgument { AiGenerationConfig(temperature = Double.NaN) }
        assertThrowsIllegalArgument {
            ProviderConfig(
                ProviderId.GROQ, true, 1, "k", "m", "https://example.invalid",
                connectTimeoutMs = 0, requestTimeoutMs = 1
            )
        }
        assertThrowsIllegalArgument {
            ServerConfig.fromEnv(mapOf("PORT" to "70000")::get)
        }
        assertThrowsIllegalArgument {
            ServerConfig.fromEnv(mapOf("JARVIS_CLIENT_TOKENS" to "weak:client")::get)
        }
        assertThrowsIllegalArgument {
            ServerConfig.fromEnv(
                mapOf("JARVIS_CLIENT_TOKENS" to "${"x".repeat(257)}:client")::get
            )
        }
        assertThrowsIllegalArgument {
            val whitespaceToken = "a".repeat(16) + " " + "b".repeat(16)
            ServerConfig.fromEnv(mapOf("JARVIS_CLIENT_TOKENS" to "$whitespaceToken:client")::get)
        }
        val duplicate = "d".repeat(32)
        assertThrowsIllegalArgument {
            ServerConfig.fromEnv(
                mapOf("JARVIS_CLIENT_TOKENS" to "$duplicate:first,$duplicate:second")::get
            )
        }
        assertThrowsIllegalArgument {
            ValidationConfig(maxBodyBytes = 10L * 1024 * 1024 + 1)
        }
    }

    @Test
    fun `minute window uses exact boundary and retry-after rounds up`() {
        var now = 1_000L
        val limiter = SlidingWindowRateLimiter(RateLimitConfig(perMinute = 1, perDay = 10)) { now }

        assertTrue(limiter.check("a") is RateLimitDecision.Allowed)
        now += 1
        val limited = limiter.check("a") as RateLimitDecision.Limited
        assertEquals("per_minute", limited.scope)
        assertEquals(60L, limited.retryAfterSeconds)

        now = 61_000L
        assertTrue(limiter.check("a") is RateLimitDecision.Allowed)
    }

    @Test
    fun `daily limit and client identities are independent`() {
        var now = 0L
        val limiter = SlidingWindowRateLimiter(RateLimitConfig(perMinute = 10, perDay = 2)) { now }

        assertTrue(limiter.check("a") is RateLimitDecision.Allowed)
        now += 60_000
        assertTrue(limiter.check("a") is RateLimitDecision.Allowed)
        now += 60_000
        val blocked = limiter.check("a") as RateLimitDecision.Limited
        assertEquals("per_day", blocked.scope)
        assertTrue(limiter.check("b") is RateLimitDecision.Allowed)

        limiter.reset("a")
        assertTrue(limiter.check("a") is RateLimitDecision.Allowed)
    }

    @Test
    fun `concurrent calls cannot exceed configured minute allowance`() {
        val limiter = SlidingWindowRateLimiter(RateLimitConfig(perMinute = 10, perDay = 100)) { 123L }
        val pool = Executors.newFixedThreadPool(16)
        try {
            val decisions = pool.invokeAll(
                List(100) { Callable { limiter.check("same-client") } }
            ).map { it.get() }

            assertEquals(10, decisions.count { it is RateLimitDecision.Allowed })
            assertEquals(90, decisions.count { it is RateLimitDecision.Limited })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Expected IllegalArgumentException", thrown)
    }
}
