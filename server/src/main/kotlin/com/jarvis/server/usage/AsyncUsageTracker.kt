package com.jarvis.server.usage

import com.jarvis.server.config.TokenCostConfig
import com.jarvis.server.config.UsageLimitConfig
import com.jarvis.server.observability.Metrics
import com.jarvis.server.observability.StructuredLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AR-05: асинхронный pipeline записи usage с bounded retry и контролем лимитов.
 *
 * Мотивация: запись usage раньше делалась синхронно в том же корутине, что
 * обрабатывала запрос. Хотя к моменту записи ответ уже готов, синхронный JDBC
 * call под нагрузкой способен задержать освобождение connection/slot и больно
 * ударяет по tail latency. Ошибка записи также не должна сломать ответ, но
 * должна логироваться и подсвечиваться метриками.
 *
 * Структура:
 * ```
 *  request ──▶ Channel (bounded, capacity=QUEUE_CAPACITY)
 *                   │
 *                   ▼
 *             single worker coroutine (SupervisorJob)
 *                   │
 *                   ├──▶ repo.record (retry with bounded exponential backoff)
 *                   └──▶ metrics / logs
 * ```
 *
 * Гарантии:
 *  - очередь ограничена (QUEUE_CAPACITY=4096); при переполнении событие
 *    дропается с инкрементом `usage.dropped` метрики и warn-логом;
 *  - retry экспоненциальный с максимумом MAX_RETRIES=3;
 *  - shutdown() корректно ожидает drain с таймаутом SHUTDOWN_DRAIN_MS;
 *  - С++ CancellationException не маскируется.
 *
 * Помимо записи, этот класс отвечает за проверку пер-request лимитов
 * (token/cost/request) в памяти (точное решение — за PostgresRateLimiter;
 * здесь — быстрый in-process precheck, защищающий от всплесков в рамках
 * одной JVM).
 */
class AsyncUsageTracker(
    private val repository: UsageRepository,
    private val limits: UsageLimitConfig,
    private val costs: TokenCostConfig,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    parentJob: Job? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val started = AtomicBoolean(false)
    private val job = SupervisorJob(parentJob)
    private val ceh = CoroutineExceptionHandler { _, t ->
        if (t is CancellationException) throw t
        logger.error("usage tracker uncaught exception", "type" to t.javaClass.simpleName)
    }
    private val scope = CoroutineScope(dispatcher + job + ceh)

    private val channel = Channel<AiUsageRecord>(capacity = QUEUE_CAPACITY)

    // Пер-request in-memory counters (per clientId, day-bucket) — быстрый precheck.
    // Сервер authoritative — PostgresRateLimiter, но эти счётчики защищают от
    // всплесков внутри одной JVM до того как DB успеет применить лимит.
    private val dailyRequests = ConcurrentHashMap<String, DailyCounter>()
    private val dailyTokens = ConcurrentHashMap<String, DailyCounter>()
    private val dailyCostUsd = ConcurrentHashMap<String, DailyCostCounter>()

    private var workerJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        workerJob = scope.launch {
            for (record in channel) {
                runCatching { writeWithRetry(record) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        logger.error(
                            "usage record failed after retries",
                            "clientId" to record.clientId,
                            "requestId" to record.requestId,
                            "type" to it.javaClass.simpleName
                        )
                        metrics.increment("usage_failed")
                    }
            }
        }
        // M-02: periodic cleanup устаревших daily-bucket'ов.
        // В ConcurrentHashMap накапливаются мёртвые клиенты (разовый
        // тестовый ключ, ротация клиентов, миграция лицензий). Без
        // очистки карта монотонно растёт за всё время жизни процесса.
        // Раз в час проходим по трём картам и удаляем те записи, у
        // которых счётчик = 0 И bucket отстаёт от сегодняшнего.
        // Активные клиенты (даже с count=0 в текущем дне) не трогаем.
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                runCatching { pruneStaleEntries() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        logger.warn(
                            "usage tracker prune failed",
                            "type" to it.javaClass.simpleName
                        )
                    }
            }
        }
    }

    /**
     * M-02: удаляет из in-memory карт записи с протухшим bucket'ом и
     * нулевым счётчиком. Разовые клиенты/ротированные лицензии не должны
     * накапливаться в ConcurrentHashMap за всё время жизни процесса.
     */
    private fun pruneStaleEntries() {
        val today = todayBucket(clock.instant())
        val reqIter = dailyRequests.entries.iterator()
        while (reqIter.hasNext()) {
            val v = reqIter.next().value
            if (v.bucket != today && v.count.get() == 0L) reqIter.remove()
        }
        val tokIter = dailyTokens.entries.iterator()
        while (tokIter.hasNext()) {
            val v = tokIter.next().value
            if (v.bucket != today && v.count.get() == 0L) tokIter.remove()
        }
        val costIter = dailyCostUsd.entries.iterator()
        while (costIter.hasNext()) {
            val v = costIter.next().value
            if (v.bucket != today && v.cents.get() == 0L) costIter.remove()
        }
    }

    /**
     * Проверка лимитов ПЕРЕД выполнением запроса.
     *
     * @return null если разрешено; иначе — человекочитаемая причина +
     *         оценочный retry-after в секундах.
     */
    fun preflight(clientId: String): UsageLimitResult {
        val now = clock.instant()
        val day = todayBucket(now)

        val req = dailyRequests.computeIfAbsent(clientId) { DailyCounter(day) }
        req.rolloverIfNeeded(day)
        if (limits.perDayRequests > 0 && req.count.get() >= limits.perDayRequests) {
            return UsageLimitResult.Limited(
                scope = "per_day_requests",
                retryAfterSeconds = Duration.between(now, nextDayStart(now)).seconds.coerceAtLeast(1)
            )
        }

        if (limits.perDayTokens > 0) {
            val tok = dailyTokens.computeIfAbsent(clientId) { DailyCounter(day) }
            tok.rolloverIfNeeded(day)
            if (tok.count.get() >= limits.perDayTokens) {
                return UsageLimitResult.Limited(
                    scope = "per_day_tokens",
                    retryAfterSeconds = Duration.between(now, nextDayStart(now)).seconds.coerceAtLeast(1)
                )
            }
        }

        if (limits.perDayCostUsd > 0.0) {
            val cost = dailyCostUsd.computeIfAbsent(clientId) { DailyCostCounter(day) }
            cost.rolloverIfNeeded(day)
            if (cost.cents.get() >= (limits.perDayCostUsd * 100).toLong()) {
                return UsageLimitResult.Limited(
                    scope = "per_day_cost",
                    retryAfterSeconds = Duration.between(now, nextDayStart(now)).seconds.coerceAtLeast(1)
                )
            }
        }

        return UsageLimitResult.Allowed
    }

    /** Атомарно учитывает запрос в in-memory счётчиках. */
    fun accountFor(clientId: String, inputTokens: Long, outputTokens: Long, providerCostPer1k: CostPer1kTokens?) {
        val day = todayBucket(clock.instant())
        dailyRequests.computeIfAbsent(clientId) { DailyCounter(day) }.apply {
            rolloverIfNeeded(day); count.incrementAndGet()
        }
        val total = inputTokens + outputTokens
        if (total > 0 && limits.perDayTokens > 0) {
            dailyTokens.computeIfAbsent(clientId) { DailyCounter(day) }.apply {
                rolloverIfNeeded(day); count.addAndGet(total)
            }
        }
        val usd = estimateCostUsd(inputTokens, outputTokens, providerCostPer1k)
        if (usd > 0.0 && limits.perDayCostUsd > 0.0) {
            // Локаль нельзя называть `cents`: внутри apply{} она затеняла бы
            // одноимённое поле AtomicLong у DailyCostCounter.
            val centsDelta = (usd * 100).toLong()
            dailyCostUsd.computeIfAbsent(clientId) { DailyCostCounter(day) }.apply {
                rolloverIfNeeded(day); cents.addAndGet(centsDelta)
            }
        }
    }

    /** Неблокирующая отправка в очередь. */
    fun record(record: AiUsageRecord) {
        if (!started.get()) {
            // Ещё не стартовали — пишем синхронно (защита на период startup race).
            // repository.record — suspend, поэтому оборачиваем в runBlocking.
            runCatching { runBlocking { repository.record(record) } }
            return
        }
        val result = channel.trySend(record)
        if (result.isFailure) {
            metrics.increment("usage_dropped")
            logger.warn(
                "usage queue full; event dropped",
                "clientId" to record.clientId,
                "requestId" to record.requestId
            )
        }
    }

    private suspend fun writeWithRetry(record: AiUsageRecord) {
        var attempt = 0
        var backoffMs = RETRY_INITIAL_BACKOFF_MS
        while (true) {
            try {
                repository.record(record)
                metrics.increment("usage_recorded")
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                attempt++
                if (attempt > MAX_RETRIES) {
                    throw t
                }
                logger.warn(
                    "usage record failed; will retry",
                    "clientId" to record.clientId,
                    "requestId" to record.requestId,
                    "attempt" to attempt.toString(),
                    "backoffMs" to backoffMs.toString(),
                    "type" to t.javaClass.simpleName
                )
                metrics.increment("usage_retry")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(RETRY_MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * Graceful shutdown: дожидаемся, пока очередь опустеет с бюджетом
     * [SHUTDOWN_DRAIN_MS], либо закрываем канал и дропаем оставшееся с логом.
     */
    fun shutdown() {
        if (!started.compareAndSet(true, false)) return
        channel.close()
        runCatching {
            @Suppress("OPT_IN_USAGE")
            runBlocking {
                withTimeout(SHUTDOWN_DRAIN_MS) {
                    workerJob?.join()
                }
            }
        }.onFailure {
            logger.warn("usage tracker shutdown drain timed out; some records may be lost")
        }
        job.cancel()
    }

    private fun estimateCostUsd(
        inputTokens: Long,
        outputTokens: Long,
        cost: CostPer1kTokens?
    ): Double {
        val c = cost ?: return costs.fallbackUsdPer1k?.let {
            (inputTokens + outputTokens) * it / 1000.0
        } ?: 0.0
        return inputTokens * c.inputUsdPer1k / 1000.0 +
            outputTokens * c.outputUsdPer1k / 1000.0
    }

    private fun todayBucket(now: Instant): Long =
        now.epochSecond / (24 * 60 * 60)

    private fun nextDayStart(now: Instant): Instant =
        now.plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.DAYS)

    private class DailyCounter(initialBucket: Long) {
        @Volatile var bucket: Long = initialBucket
        val count: AtomicLong = AtomicLong(0)
        fun rolloverIfNeeded(day: Long) {
            if (bucket != day) {
                synchronized(this) {
                    if (bucket != day) {
                        count.set(0)
                        bucket = day
                    }
                }
            }
        }
    }

    private class DailyCostCounter(initialBucket: Long) {
        @Volatile var bucket: Long = initialBucket
        val cents: AtomicLong = AtomicLong(0)
        fun rolloverIfNeeded(day: Long) {
            if (bucket != day) {
                synchronized(this) {
                    if (bucket != day) {
                        cents.set(0)
                        bucket = day
                    }
                }
            }
        }
    }

    companion object {
        const val QUEUE_CAPACITY = 4096
        const val MAX_RETRIES = 3
        const val RETRY_INITIAL_BACKOFF_MS = 100L
        const val RETRY_MAX_BACKOFF_MS = 2_000L
        const val SHUTDOWN_DRAIN_MS = 4_000L

        /**
         * M-02: интервал очистки in-memory счётчиков.
         * Раз в час достаточно агрессивно для leak-prevention и достаточно
         * редко, чтобы не создавать contention с hot-path accountFor().
         */
        const val CLEANUP_INTERVAL_MS = 60L * 60L * 1000L
    }
}

sealed class UsageLimitResult {
    data object Allowed : UsageLimitResult()
    data class Limited(val scope: String, val retryAfterSeconds: Long) : UsageLimitResult()
}

/** Стоимость конкретного провайдера (USD за 1K токенов). */
data class CostPer1kTokens(
    val inputUsdPer1k: Double,
    val outputUsdPer1k: Double
)
