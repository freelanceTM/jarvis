package com.jarvis.server.usage

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запись об использовании AI (пункт 18 ТЗ).
 *
 * ВАЖНО про приватность (пункт 31 ТЗ): полный текст промпта и ответа
 * НЕ сохраняется. Хранится только метаданные, включая длину текста —
 * этого достаточно и для биллинга, и для диагностики.
 */
data class AiUsageRecord(
    val requestId: String,
    val clientId: String,
    val provider: String?,
    val model: String?,
    val latencyMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val success: Boolean,
    val errorCode: String?,
    val promptChars: Int,
    val responseChars: Int,
    val timestamp: Instant
)

/**
 * Хранилище usage-записей.
 *
 * Запись создаётся для КАЖДОГО запроса — и успешного, и неуспешного
 * (пункт 32 ТЗ), чтобы сбои провайдеров были видны в статистике.
 */
interface UsageRepository {
    suspend fun record(usage: AiUsageRecord)
    suspend fun recentFor(clientId: String, limit: Int = 100): List<AiUsageRecord>
    suspend fun all(): List<AiUsageRecord>
}

/**
 * Bounded in-memory implementation for isolated unit/API harnesses only.
 * Production composition uses JdbcUsageRepository so records are shared and
 * restart-safe. This implementation deliberately remains useful for tests that
 * must not require infrastructure.
 */
class InMemoryUsageRepository(
    private val maxRecords: Int = 10_000
) : UsageRepository {

    init {
        require(maxRecords >= 0) { "maxRecords must be non-negative" }
    }

    private val records = ConcurrentLinkedQueue<AiUsageRecord>()
    private val recordCount = AtomicInteger(0)

    override suspend fun record(usage: AiUsageRecord) {
        synchronized(records) {
            records.add(usage)
            recordCount.incrementAndGet()
            // ConcurrentLinkedQueue.size — O(n). Отдельный атомарный счётчик
            // сохраняет bounded-memory операцию O(1) и не деградирует после
            // достижения лимита на каждом последующем запросе.
            while (recordCount.get() > maxRecords) {
                if (records.poll() != null) {
                    recordCount.decrementAndGet()
                } else {
                    // Защита от рассинхронизации при будущих изменениях.
                    recordCount.set(0)
                    break
                }
            }
        }
    }

    override suspend fun recentFor(clientId: String, limit: Int): List<AiUsageRecord> =
        synchronized(records) {
            records.filter { it.clientId == clientId }.takeLast(limit.coerceAtLeast(0))
        }

    override suspend fun all(): List<AiUsageRecord> = synchronized(records) { records.toList() }

    fun size(): Int = recordCount.get()
}
