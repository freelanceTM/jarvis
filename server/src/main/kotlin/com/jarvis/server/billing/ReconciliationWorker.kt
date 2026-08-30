package com.jarvis.server.billing

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1-3: фоновый воркер видимости зависших reconciliation-заказов.
 *
 * Проблема: заказ в `RECONCILIATION_REQUIRED` означает неоднозначный исход
 * вызова провайдера. До сих пор такой заказ висел молча — его могла
 * обнаружить только ручная проверка БД. Это главный пробел в управлении
 * состоянием биллинга (аудит P1-3).
 *
 * Решение — МИНИМАЛЬНОЕ и честное:
 * ```text
 * каждые SCAN_INTERVAL:
 *   stale = repo.findStaleReconciliationOrders(now - STALE_THRESHOLD)
 *   для каждого: metrics("reconciliation_stale") + structured warn-лог
 * ```
 *
 * Воркер НЕ угадывает статус платежа: без query-API провайдера переводить
 * заказ в PAID/FAILED автоматически нельзя (риск двойного продления или
 * ложного отказа). Процедура разбора — docs/RUNBOOK.md §5.
 *
 * Гарантии:
 *  - только чтение БД (никаких UPDATE);
 *  - failure одного скана не убивает цикл (runCatching + лог);
 *  - CancellationException не маскируется;
 *  - метрика `reconciliation_stale` видна в /v1/admin/metrics{,/prometheus}
 *    и попадает в алерты (deploy/prometheus/alerts.yml).
 */
class ReconciliationWorker(
    private val orderSource: StaleReconciliationOrderSource,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    parentJob: Job? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val scanInterval: Duration = DEFAULT_SCAN_INTERVAL,
    private val staleThreshold: Duration = DEFAULT_STALE_THRESHOLD,
    private val scanLimit: Int = DEFAULT_SCAN_LIMIT
) {
    /** Источник зависших заказов (реализация — JdbcBillingRepository). */
    fun interface StaleReconciliationOrderSource {
        fun findStaleReconciliationOrders(staleBefore: Instant, limit: Int): List<StaleReconciliationOrder>
    }

    private val started = AtomicBoolean(false)
    private val job = SupervisorJob(parentJob)
    private val ceh = CoroutineExceptionHandler { _, t ->
        if (t is CancellationException) throw t
        logger.error("reconciliation worker uncaught exception", "type" to t.javaClass.simpleName)
    }
    private val scope = CoroutineScope(dispatcher + job + ceh)
    private var loopJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        loopJob = scope.launch {
            while (isActive) {
                runCatching { scanOnce() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        logger.warn(
                            "reconciliation scan failed",
                            "type" to it.javaClass.simpleName
                        )
                    }
                delay(scanInterval.toMillis())
            }
        }
    }

    /**
     * Один скан (публичный для тестов). Возвращает число зависших заказов
     * в этом проходе.
     */
    fun scanOnce(): Int {
        val staleBefore = clock.instant().minus(staleThreshold)
        val stale = orderSource.findStaleReconciliationOrders(staleBefore, scanLimit)
        stale.forEach { order ->
            // PII нет: только идентификаторы и возраст.
            logger.warn(
                "billing order stuck in reconciliation",
                "orderId" to order.orderId.toString(),
                "provider" to order.provider.name,
                "providerOrderIdPresent" to (order.providerOrderId != null).toString(),
                "ageMinutes" to Duration.between(order.updatedAt, clock.instant()).toMinutes().toString(),
                "runbook" to "docs/RUNBOOK.md#5"
            )
            metrics.increment("reconciliation_stale")
        }
        if (stale.isEmpty()) {
            metrics.increment("reconciliation_scan_clean")
        }
        return stale.size
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        /** Период скана: зависшие заказы не требуют секундной реакции. */
        val DEFAULT_SCAN_INTERVAL: Duration = Duration.ofMinutes(10)

        /**
         * Порог «зависания». Webhook провайдера обычно приходит за секунды;
         * час тишины — уверенный сигнал для разбора по ранбуку.
         */
        val DEFAULT_STALE_THRESHOLD: Duration = Duration.ofHours(1)

        const val DEFAULT_SCAN_LIMIT: Int = 50
    }
}
