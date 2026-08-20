package com.jarvis.server.usage

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

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
 * In-memory реализация.
 *
 * В проекте нет серверной БД, и ТЗ (пункт 31) запрещает разворачивать большую
 * persistence-архитектуру ради этого этапа. Контракт [UsageRepository]
 * специально сделан минимальным, чтобы замена на Postgres/SQLite свелась
 * к одному новому классу.
 *
 * Ограничение: при перезапуске данные теряются, размер ограничен
 * [maxRecords] (защита от роста памяти).
 */
class InMemoryUsageRepository(
    private val maxRecords: Int = 10_000
) : UsageRepository {

    private val records = ConcurrentLinkedQueue<AiUsageRecord>()

    override suspend fun record(usage: AiUsageRecord) {
        records.add(usage)
        while (records.size > maxRecords) {
            records.poll()
        }
    }

    override suspend fun recentFor(clientId: String, limit: Int): List<AiUsageRecord> =
        records.filter { it.clientId == clientId }.takeLast(limit)

    override suspend fun all(): List<AiUsageRecord> = records.toList()

    fun size(): Int = records.size
}
