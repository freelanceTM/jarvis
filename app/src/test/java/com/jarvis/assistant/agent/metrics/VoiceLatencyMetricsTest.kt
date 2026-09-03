package com.jarvis.assistant.agent.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice Latency: P50/P95/P99 по сегментам пайплайна с разрезом LOCAL/CLOUD.
 */
class VoiceLatencyMetricsTest {

    private val m = VoiceLatencyMetrics()

    @Test
    fun `percentiles are computed over recorded samples`() {
        // 100 значений 1..100: p50=50, p95=95, p99=99 (ceil-семантика).
        repeat(100) { m.record(VoiceLatencyMetrics.VoiceStage.AI, (it + 1).toLong(), VoiceLatencyMetrics.VoiceLane.LOCAL) }

        val p = m.snapshot()[VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.AI, VoiceLatencyMetrics.VoiceLane.LOCAL)]!!

        assertEquals(100, p.count)
        assertEquals(50L, p.p50Ms)
        assertEquals(95L, p.p95Ms)
        assertEquals(99L, p.p99Ms)
    }

    @Test
    fun `local and cloud lanes are kept apart - the real difference is visible`() {
        // Локальная AI-фаза: 10-20 мс; облачная: 900-1100 мс.
        repeat(10) { m.record(VoiceLatencyMetrics.VoiceStage.AI, 10L + it, VoiceLatencyMetrics.VoiceLane.LOCAL) }
        repeat(10) { m.record(VoiceLatencyMetrics.VoiceStage.AI, 900L + it * 20, VoiceLatencyMetrics.VoiceLane.CLOUD) }

        val snap = m.snapshot()
        val local = snap[VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.AI, VoiceLatencyMetrics.VoiceLane.LOCAL)]!!
        val cloud = snap[VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.AI, VoiceLatencyMetrics.VoiceLane.CLOUD)]!!

        assertEquals(19L, local.p50Ms + 0) // медиана локального ряда
        assertTrue("local p95 (${local.p95Ms}) должен быть << cloud p50 (${cloud.p50Ms})", local.p95Ms < cloud.p50Ms)
        assertEquals(1000L, cloud.p50Ms)
    }

    @Test
    fun `ring buffer keeps only recent samples`() {
        repeat(VoiceLatencyMetrics.CAPACITY + 50) { m.record(VoiceLatencyMetrics.VoiceStage.TOOL, 100L) }
        val p = m.snapshot()[VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.TOOL, VoiceLatencyMetrics.VoiceLane.UNSPECIFIED)]!!
        assertEquals(VoiceLatencyMetrics.CAPACITY + 50, p.count) // total подсчитан
        // В окне — только последние 256 значений (100).
        assertEquals(100L, p.p50Ms)
    }

    @Test
    fun `implausible durations are discarded`() {
        m.record(VoiceLatencyMetrics.VoiceStage.WAKE_TO_STT, -5L)
        m.record(VoiceLatencyMetrics.VoiceStage.WAKE_TO_STT, VoiceLatencyMetrics.MAX_PLAUSIBLE_MS + 1)
        m.record(VoiceLatencyMetrics.VoiceStage.WAKE_TO_STT, 250L)

        val p = m.snapshot()[VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.WAKE_TO_STT, VoiceLatencyMetrics.VoiceLane.UNSPECIFIED)]!!
        assertEquals(1, p.count)
        assertEquals(250L, p.p50Ms)
    }

    @Test
    fun `empty series is never read`() {
        assertTrue(m.snapshot().isEmpty())
        assertFalse(m.snapshot().containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.TOOL_TO_TTS, VoiceLatencyMetrics.VoiceLane.UNSPECIFIED)))
    }
}
