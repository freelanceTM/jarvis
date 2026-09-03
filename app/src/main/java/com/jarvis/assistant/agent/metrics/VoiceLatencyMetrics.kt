package com.jarvis.assistant.agent.metrics

import android.util.Log
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Латентность голосового пайплайна (Voice Latency):
 *
 * ```
 * Wake → STT → Router → AI → Tool → TTS
 * ```
 *
 * Сегменты и где они измеряются:
 *  - [VoiceStage.WAKE_TO_STT] — оркестратор: детект wake-word → финальный STT;
 *  - [VoiceStage.STT_TO_ROUTER] — execution engine: финал STT
 *    ([com.jarvis.assistant.agent.decision.ExecutionRequest.originTimestampMs])
 *    → вход в роутинг (вставка сообщений, классификация приватности, память);
 *  - [VoiceStage.ROUTER_DISPATCH] — execution engine: вход → выбрана полоса
 *    (FastCommandRouter, планировщик, preflight);
 *  - [VoiceStage.AI] — длительность AI-фазы: локальная модель / агент /
 *    облачный вызов — ВСЕГДА с [VoiceLane] (LOCAL/CLOUD) — главный разрез
 *    для «увидеть реальную разницу»;
 *  - [VoiceStage.TOOL] — исполнение инструмента (DEVICE_TOOL полоса);
 *  - [VoiceStage.TOOL_TO_TTS] — оркестратор: результат получен → вызов speak.
 *
 * Хранение: кольцевой буфер (256 значений на серию) → P50/P95/P99 на снимке.
 * Записи из горутин оркестратора и движка — [ConcurrentHashMap] + synchronized
 * буфер; латентности измеряются по [SystemClock.elapsedRealtime] (monotonic).
 *
 * Это МЕТРИКА: на маршрутизацию не влияет — цель («Simple command → local →
 * response максимально быстро») достигается полосами LOCAL, а проценты
 * показывают, где узкое место.
 */
@Singleton
class VoiceLatencyMetrics @Inject constructor() {

    enum class VoiceStage { WAKE_TO_STT, STT_TO_ROUTER, ROUTER_DISPATCH, AI, TOOL, TOOL_TO_TTS }

    enum class VoiceLane { LOCAL, CLOUD, UNSPECIFIED }

    private val series = ConcurrentHashMap<SeriesKey, Ring>()

    /** Точка отсчёта латентностей: monotonic clock устройства. */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    fun record(stage: VoiceStage, durationMs: Long, lane: VoiceLane = VoiceLane.UNSPECIFIED) {
        if (durationMs < 0 || durationMs > MAX_PLAUSIBLE_MS) return
        val ring = series.computeIfAbsent(SeriesKey(stage, lane)) { Ring() }
        ring.add(durationMs)
        maybeLogSummary(stage, ring)
    }

    fun snapshot(): Map<SeriesKey, Percentiles> =
        series.mapValues { (_, ring) -> ring.percentiles() }

    data class SeriesKey(val stage: VoiceStage, val lane: VoiceLane)

    data class Percentiles(
        val count: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long
    )

    private class Ring {
        private val buffer = LongArray(CAPACITY)
        private var size = 0
        private var next = 0
        private var logged = 0

        @Synchronized
        fun add(value: Long) {
            buffer[next] = value
            next = (next + 1) % CAPACITY
            if (size < CAPACITY) size++
            logged++
        }

        @Synchronized
        fun count(): Int = logged

        @Synchronized
        fun percentiles(): Percentiles {
            val sorted = buffer.copyOf(size).sorted()
            return Percentiles(
                count = logged,
                p50Ms = percentile(sorted, 50.0),
                p95Ms = percentile(sorted, 95.0),
                p99Ms = percentile(sorted, 99.0)
            )
        }

        @Synchronized
        fun loggedCount(): Int = logged
    }

    companion object {
        /** Ёмкость кольца на серию: последние 256 наблюдений. */
        const val CAPACITY = 256

        /** Мусорные значения (зависший таймер) не портят перцентили. */
        const val MAX_PLAUSIBLE_MS = 120_000L

        /** Периодичность сводки в лог на серию. */
        const val LOG_EVERY = 25

        private fun percentile(sorted: List<Long>, p: Double): Long {
            if (sorted.isEmpty()) return 0L
            val index = (Math.ceil(p / 100.0 * sorted.size) - 1)
                .toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }

    /**
     * Периодическая сводка: для AI-серии показывает LOCAL против CLOUD —
     * «реальную разницу» из спецификации.
     */
    private fun maybeLogSummary(stage: VoiceStage, ring: Ring) {
        val logged = ring.loggedCount()
        if (logged == 0 || logged % LOG_EVERY != 0) return
        val snap = snapshot()
        val local = snap[SeriesKey(VoiceStage.AI, VoiceLane.LOCAL)]
        val cloud = snap[SeriesKey(VoiceStage.AI, VoiceLane.CLOUD)]
        Log.i(
            TAG,
            "VoiceLatency $stage | n=$logged p50=${ring.percentiles().p50Ms}ms | " +
                "AI local=${local?.let { "${it.p50Ms}/${it.p95Ms}/${it.p99Ms}ms" } ?: "-"} " +
                "cloud=${cloud?.let { "${it.p50Ms}/${it.p95Ms}/${it.p99Ms}ms" } ?: "-"}"
        )
    }

    private const val TAG = "VoiceLatency"
}
