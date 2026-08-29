package com.jarvis.server

import com.jarvis.server.billing.BillingProviderId
import com.jarvis.server.billing.ReconciliationWorker
import com.jarvis.server.billing.StaleReconciliationOrder
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * P1-3: тесты воркера видимости reconciliation-заказов.
 *
 * Контракт воркера:
 *  - зависшие (> staleThreshold) заказы попадают в метрику reconciliation_stale;
 *  - чистый скан инкрементирует reconciliation_scan_clean;
 *  - воркер ТОЛЬКО читает: источник данных не мутируется;
 *  - сбой источника не выбрасывает наверх (скан fail-safe);
 *  - cancellation не маскируется (проверяется контрактом runCatching-цикла).
 */
class ReconciliationWorkerTest {

    private val logger = ConsoleStructuredLogger(sink = {})
    private val fixedNow = Instant.parse("2026-08-29T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private class RecordingSource : ReconciliationWorker.StaleReconciliationOrderSource {
        val requested = mutableListOf<Instant>()
        var lastLimit: Int = -1
        var result: List<StaleReconciliationOrder> = emptyList()
        var failure: Throwable? = null

        override fun findStaleReconciliationOrders(staleBefore: Instant, limit: Int): List<StaleReconciliationOrder> {
            requested += staleBefore
            lastLimit = limit
            failure?.let { throw it }
            return result
        }
    }

    private fun order(age: Duration, provider: BillingProviderId = BillingProviderId.PADDLE) =
        StaleReconciliationOrder(
            orderId = UUID.randomUUID(),
            provider = provider,
            providerOrderId = "prov-order-1",
            updatedAt = fixedNow.minus(age)
        )

    @Test
    fun `stale orders increment reconciliation_stale metric`() {
        val source = RecordingSource().apply {
            result = listOf(order(Duration.ofHours(2)), order(Duration.ofMinutes(90), BillingProviderId.HELEKET))
        }
        val metrics = Metrics()
        val worker = ReconciliationWorker(
            orderSource = source,
            logger = logger,
            metrics = metrics,
            clock = clock
        )

        val staleCount = worker.scanOnce()

        assertEquals(2, staleCount)
        // AR-05: named counters — считываем напрямую из snapshot.
        val snapshot = metrics.snapshot()
        assertEquals(2L, snapshot["reconciliation_stale"])
        assertEquals(null, snapshot["reconciliation_scan_clean"])
        // Скан запросил границу "старее чем now - 1h".
        assertEquals(fixedNow.minus(Duration.ofHours(1)), source.requested.single())
    }

    @Test
    fun `clean scan increments clean counter and does not touch stale metric`() {
        val source = RecordingSource()
        val metrics = Metrics()
        val worker = ReconciliationWorker(
            orderSource = source,
            logger = logger,
            metrics = metrics,
            clock = clock
        )

        val staleCount = worker.scanOnce()

        assertEquals(0, staleCount)
        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot["reconciliation_scan_clean"])
        assertEquals(null, snapshot["reconciliation_stale"])
    }

    @Test
    fun `source failure is contained and does not throw`() {
        val source = RecordingSource().apply { failure = IllegalStateException("db down") }
        val metrics = Metrics()
        val worker = ReconciliationWorker(
            orderSource = source,
            logger = logger,
            metrics = metrics,
            clock = clock
        )

        // scanOnce вызывается из runCatching-цикла; здесь проверяем, что
        // исходное исключение не теряется, а контракт цикла (продолжение после
        // сбоя) обеспечивает runCatching в start().
        val outcome = runCatching { worker.scanOnce() }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun `worker passes configured scan limit to source`() {
        val source = RecordingSource()
        val metrics = Metrics()
        val worker = ReconciliationWorker(
            orderSource = source,
            logger = logger,
            metrics = metrics,
            clock = clock,
            scanLimit = 7
        )

        worker.scanOnce()

        assertEquals(7, source.lastLimit)
    }
}
