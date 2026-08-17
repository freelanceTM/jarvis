package com.jarvis.assistant.ai

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Политика retry для AI-запросов (пункт аудита #9 — MEDIUM).
 *
 * Exponential backoff: 300мс → 600мс → 1200мс (3 попытки, cap 2с).
 * Retry только для transient ошибок:
 *  - SocketTimeoutException, IOException (сеть);
 *  - HTTP 408/429/5xx (в сообщении исключения вида "HTTP 429: ...").
 *
 * Чистая логика — unit-тестируема без сети.
 */
object AiRetryPolicy {

    /** Максимум попыток (исходная + 2 ретрая). */
    const val MAX_ATTEMPTS = 3

    private const val BASE_DELAY_MS = 300L
    private const val MAX_DELAY_MS = 2_000L

    /** Retry для сетевых исключений (transient по определению). */
    fun shouldRetry(e: Throwable): Boolean =
        e is SocketTimeoutException || e is IOException

    /** Retry для transient HTTP-кодов: 408 (timeout), 429 (rate limit), 5xx. */
    fun shouldRetryHttp(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /** Парсит HTTP-код из сообщения исключения вида "HTTP 429: ...". */
    fun extractHttpCode(message: String?): Int? {
        if (message == null) return null
        val match = Regex("""HTTP\s+(\d{3})""").find(message) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** true, если в сообщении исключения зашит transient HTTP-код. */
    fun isTransientHttpError(message: String?): Boolean {
        val code = extractHttpCode(message) ?: return false
        return shouldRetryHttp(code)
    }

    /** Задержка перед попыткой [attempt] (1-based): 300 * 2^(attempt-1), cap 2000. */
    fun backoffDelayMs(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val shift = (attempt - 1).coerceAtMost(4)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    /** Понятное сообщение об ошибке после исчерпания retry. */
    fun friendlyMessage(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Таймаут подключения к AI. Проверьте интернет."
        is IOException -> "Ошибка сети при связи с AI."
        else -> "Ошибка AI: ${e.localizedMessage}"
    }
}
