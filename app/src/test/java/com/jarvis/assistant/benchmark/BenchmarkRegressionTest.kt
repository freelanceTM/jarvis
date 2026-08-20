package com.jarvis.assistant.benchmark

import com.jarvis.assistant.agent.decision.ExecutionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression-suite на основе benchmark (п. 41 ТЗ).
 *
 * Пороги усилены по результатам QA-аудита 2026-08-20: исправлены ложные
 * media/Wi-Fi/device routes, сложные запросы к 1B-модели и ambiguity flow.
 * Изменения в роутинге не должны вернуть прежний baseline 73%.
 */
class BenchmarkRegressionTest {

    private companion object {
        /** Усиленный baseline после QA-аудита: 99/100. */
        const val MIN_ROUTING_ACCURACY = 99.0

        /** Категории с детерминированным ожидаемым поведением. */
        const val MIN_DEVICE_ACCURACY = 100.0
        const val MIN_LOCAL_ACCURACY = 100.0
        const val MIN_WEB_ACCURACY = 100.0
        const val MIN_CLOUD_ACCURACY = 100.0
        const val MIN_AMBIGUOUS_ACCURACY = 100.0

        /** Неожиданные device-действия и false-local больше не допускаются. */
        const val MAX_DEVICE_FALSE_POSITIVES = 0
        const val MAX_FALSE_LOCAL = 0
    }

    private fun runBenchmark(): Pair<BenchmarkMetrics.Report, BenchmarkHarness.Rig> = runBlocking {
        val rig = BenchmarkHarness.build()
        val results = BenchmarkRunner(rig).run(BenchmarkDataset.cases, runs = 1)
        BenchmarkMetrics.buildReport(results) to rig
    }

    @Test
    fun `routing accuracy does not regress below baseline`() {
        val (report, _) = runBenchmark()
        assertTrue(
            "Routing accuracy ${report.routingAccuracyPercent}% упала ниже baseline $MIN_ROUTING_ACCURACY%",
            report.routingAccuracyPercent >= MIN_ROUTING_ACCURACY
        )
    }

    @Test
    fun `device routing stays perfect`() {
        val (report, _) = runBenchmark()
        val device = report.categoryAccuracy.first { it.category == BenchmarkCategory.DEVICE }
        assertTrue(
            "DEVICE accuracy ${device.accuracyPercent}% ниже $MIN_DEVICE_ACCURACY%",
            device.accuracyPercent >= MIN_DEVICE_ACCURACY
        )
    }

    @Test
    fun `local ai routing stays perfect`() {
        val (report, _) = runBenchmark()
        val local = report.categoryAccuracy.first { it.category == BenchmarkCategory.LOCAL_AI }
        assertTrue(
            "LOCAL_AI accuracy ${local.accuracyPercent}% ниже $MIN_LOCAL_ACCURACY%",
            local.accuracyPercent >= MIN_LOCAL_ACCURACY
        )
    }

    @Test
    fun `web requests do not silently go local`() {
        val (report, _) = runBenchmark()
        val web = report.categoryAccuracy.first { it.category == BenchmarkCategory.CLOUD_WEB }
        val cloud = report.categoryAccuracy.first { it.category == BenchmarkCategory.CLOUD_AI }
        val ambiguous = report.categoryAccuracy.first { it.category == BenchmarkCategory.AMBIGUOUS }
        assertTrue(
            "CLOUD_WEB accuracy ${web.accuracyPercent}% ниже $MIN_WEB_ACCURACY% — " +
                "риск галлюцинаций на актуальных данных",
            web.accuracyPercent >= MIN_WEB_ACCURACY
        )
        assertTrue(
            "CLOUD_AI accuracy ${cloud.accuracyPercent}% ниже $MIN_CLOUD_ACCURACY%",
            cloud.accuracyPercent >= MIN_CLOUD_ACCURACY
        )
        assertTrue(
            "AMBIGUOUS accuracy ${ambiguous.accuracyPercent}% ниже $MIN_AMBIGUOUS_ACCURACY%",
            ambiguous.accuracyPercent >= MIN_AMBIGUOUS_ACCURACY
        )
    }

    @Test
    fun `device false positives do not grow`() {
        val (report, _) = runBenchmark()
        assertTrue(
            "Device false positives ${report.deviceFalsePositiveCount} > $MAX_DEVICE_FALSE_POSITIVES: " +
                "система стала чаще выполнять неожиданные действия на устройстве",
            report.deviceFalsePositiveCount <= MAX_DEVICE_FALSE_POSITIVES
        )
    }

    @Test
    fun `false local rate does not grow`() {
        val (report, _) = runBenchmark()
        assertTrue(
            "False Local ${report.falseLocalCount} > $MAX_FALSE_LOCAL",
            report.falseLocalCount <= MAX_FALSE_LOCAL
        )
    }

    /**
     * Приватность — жёсткий инвариант, а не порог.
     * PRIVATE/SENSITIVE не должны уходить в облако ни при каких изменениях.
     */
    @Test
    fun `private and sensitive requests never reach cloud`() = runBlocking {
        val rig = BenchmarkHarness.build()
        val privacyCases = BenchmarkDataset.cases.filter {
            it.privacyLevel != com.jarvis.assistant.agent.decision.PrivacyLevel.NORMAL
        }
        assertTrue("Privacy-кейсы должны быть в датасете", privacyCases.isNotEmpty())

        val results = BenchmarkRunner(rig).run(privacyCases, runs = 1)

        results.forEach { r ->
            assertTrue(
                "${r.caseId}: приватный запрос ушёл в CLOUD_AI",
                r.actualRoute != ExecutionType.CLOUD_AI
            )
        }
    }

    /** Датасет — часть контракта: изменение размера должно быть осознанным. */
    @Test
    fun `dataset size and integrity are stable`() {
        assertTrue(BenchmarkDataset.validate().isEmpty())
        assertEquals(100, BenchmarkDataset.cases.size)
    }
}
