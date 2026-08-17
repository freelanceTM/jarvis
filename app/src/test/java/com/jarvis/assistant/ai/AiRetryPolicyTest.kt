package com.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Пункт аудита #9 (MEDIUM): exponential backoff retry для transient ошибок.
 */
class AiRetryPolicyTest {

    // ===========================================
    // shouldRetry — только transient
    // ===========================================

    @Test
    fun `socket timeout and io exceptions are retryable`() {
        assertTrue(AiRetryPolicy.shouldRetry(SocketTimeoutException("timeout")))
        assertTrue(AiRetryPolicy.shouldRetry(IOException("network")))
    }

    @Test
    fun `non transient exceptions are not retryable`() {
        assertFalse(AiRetryPolicy.shouldRetry(IllegalArgumentException("bad args")))
        assertFalse(AiRetryPolicy.shouldRetry(IllegalStateException("key missing")))
        assertFalse(AiRetryPolicy.shouldRetry(NullPointerException()))
    }

    // ===========================================
    // HTTP-коды
    // ===========================================

    @Test
    fun `http 408 429 and 5xx are transient`() {
        assertTrue(AiRetryPolicy.shouldRetryHttp(408))
        assertTrue(AiRetryPolicy.shouldRetryHttp(429))
        assertTrue(AiRetryPolicy.shouldRetryHttp(500))
        assertTrue(AiRetryPolicy.shouldRetryHttp(503))
    }

    @Test
    fun `http 4xx are not transient`() {
        assertFalse(AiRetryPolicy.shouldRetryHttp(400))
        assertFalse(AiRetryPolicy.shouldRetryHttp(401))
        assertFalse(AiRetryPolicy.shouldRetryHttp(403))
        assertFalse(AiRetryPolicy.shouldRetryHttp(404))
    }

    @Test
    fun `extractHttpCode parses from exception message`() {
        assertEquals(429, AiRetryPolicy.extractHttpCode("HTTP 429: rate limited"))
        assertEquals(503, AiRetryPolicy.extractHttpCode("HTTP 503: unavailable"))
        assertNull(AiRetryPolicy.extractHttpCode(null))
        assertNull(AiRetryPolicy.extractHttpCode("просто текст"))
        assertNull(AiRetryPolicy.extractHttpCode("HTTP abc"))
    }

    @Test
    fun `isTransientHttpError detects retryable codes in messages`() {
        assertTrue(AiRetryPolicy.isTransientHttpError("HTTP 429: too many requests"))
        assertTrue(AiRetryPolicy.isTransientHttpError("HTTP 503: unavailable"))
        assertFalse(AiRetryPolicy.isTransientHttpError("HTTP 400: bad request"))
        assertFalse(AiRetryPolicy.isTransientHttpError("ключ не указан"))
    }

    // ===========================================
    // Exponential backoff
    // ===========================================

    @Test
    fun `backoff grows exponentially`() {
        // 300 * 2^(attempt-1): попытка 1 — без задержки, 2 — 600, 3 — 1200.
        assertEquals(0L, AiRetryPolicy.backoffDelayMs(1))
        assertEquals(600L, AiRetryPolicy.backoffDelayMs(2))
        assertEquals(1200L, AiRetryPolicy.backoffDelayMs(3))
    }

    @Test
    fun `backoff is capped at 2 seconds`() {
        // 300*8=2400 → cap 2000.
        assertEquals(2000L, AiRetryPolicy.backoffDelayMs(4))
        assertEquals(2000L, AiRetryPolicy.backoffDelayMs(5))
        assertEquals(2000L, AiRetryPolicy.backoffDelayMs(10))
    }

    @Test
    fun `max attempts is three`() {
        assertEquals(3, AiRetryPolicy.MAX_ATTEMPTS)
    }

    // ===========================================
    // Сообщения
    // ===========================================

    @Test
    fun `friendly messages explain the failure`() {
        assertTrue(AiRetryPolicy.friendlyMessage(SocketTimeoutException("t")).contains("Таймаут"))
        assertTrue(AiRetryPolicy.friendlyMessage(IOException("n")).contains("сети"))
        assertTrue(AiRetryPolicy.friendlyMessage(IllegalStateException("boom")).contains("boom"))
    }
}
