package com.jarvis.server

import com.jarvis.server.persistence.PostgresSingleInstanceGuard
import org.junit.Assert.assertTrue
import org.junit.Test

class PostgresSingleInstanceGuardTest : PostgresTestSupport() {
    @Test
    fun `second application instance fails closed and restart can acquire lock`() {
        val first = PostgresSingleInstanceGuard.acquire(dataSource)
        try {
            val failure = runCatching { PostgresSingleInstanceGuard.acquire(dataSource) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("Single-instance topology violation"))
        } finally {
            first.close()
        }

        PostgresSingleInstanceGuard.acquire(dataSource).use { restarted ->
            // Idempotent close and held session lock lifecycle are both exercised.
            restarted.close()
        }
    }
}
