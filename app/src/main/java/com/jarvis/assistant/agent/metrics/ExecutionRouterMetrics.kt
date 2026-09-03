package com.jarvis.assistant.agent.metrics

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Метрики Local-first ExecutionRouter (подсчёт, а не политика).
 *
 * Считается КАЖДЫЙ запрос, прошедший через
 * [com.jarvis.assistant.agent.decision.ExecutionDecisionEngine.execute]:
 *
 *  - total_requests     — все вызовы (включая отказы/уточнения — они честно
 *                         понижают оба процента, остаток виден арифметикой);
 *  - tool_requests      — полоса LOCAL TOOL (FastCommandRouter → ToolExecutor);
 *  - local_requests     — полоса LOCAL AI (on-device Gemma / процедурная память);
 *  - agent_requests     — on-device многошаговые планы (AGENT); это тоже
 *                         локальное исполнение и входит в Local %;
 *  - direct_requests    — мгновенные локальные реплики роутера («привет»);
 *                         тоже локальное исполнение, входит в Local %;
 *  - cloud_requests     — запросы, реально отправленные в облако (attempt);
 *  - failed_local       — полоса LOCAL AI честно отказала (Error), без
 *                         эскалации (движок не эскалирует Failed);
 *  - cloud_escalations  — облачные запросы, которых НЕ БЫЛО БЫ, справься
 *                         локальная полоса (local был опрошен и не взял).
 *
 * Целевой показатель первой версии — Tool/local execution 60–70%+.
 * Это МЕТРИКА, а не жёсткое требование: ничего в роутинге не меняется ради
 * процента; 80%+ без ухудшения качества — отлично, деградация качества ради
 * процента — запрещена.
 */
@Singleton
class ExecutionRouterMetrics @Inject constructor() {

    private val totalRequests = AtomicLong()
    private val toolRequests = AtomicLong()
    private val localRequests = AtomicLong()
    private val agentRequests = AtomicLong()
    private val directRequests = AtomicLong()
    private val cloudRequests = AtomicLong()
    private val failedLocal = AtomicLong()
    private val cloudEscalations = AtomicLong()

    fun noteTotalRequest() {
        maybeLogSummary(totalRequests.incrementAndGet())
    }

    fun noteToolExecution() {
        toolRequests.incrementAndGet()
    }

    fun noteDirectResponse() {
        directRequests.incrementAndGet()
    }

    fun noteAgentExecution() {
        agentRequests.incrementAndGet()
    }

    fun noteLocalHandled() {
        localRequests.incrementAndGet()
    }

    fun noteLocalFailed() {
        failedLocal.incrementAndGet()
    }

    /** @param escalated true — облаку помогла только невозможность локальной полосы. */
    fun noteCloudExecution(escalated: Boolean) {
        cloudRequests.incrementAndGet()
        if (escalated) cloudEscalations.incrementAndGet()
    }

    /** Иммутабельный снимок для UI/диагностики/логов. */
    fun snapshot(): Snapshot = Snapshot(
        totalRequests = totalRequests.get(),
        toolRequests = toolRequests.get(),
        localRequests = localRequests.get(),
        agentRequests = agentRequests.get(),
        directRequests = directRequests.get(),
        cloudRequests = cloudRequests.get(),
        failedLocal = failedLocal.get(),
        cloudEscalations = cloudEscalations.get()
    )

    data class Snapshot(
        val totalRequests: Long,
        val toolRequests: Long,
        val localRequests: Long,
        val agentRequests: Long,
        val directRequests: Long,
        val cloudRequests: Long,
        val failedLocal: Long,
        val cloudEscalations: Long
    ) {
        /** Все локально исполненные полосы (tool + agent + local + direct). */
        val localExecuted: Long get() = toolRequests + agentRequests + localRequests + directRequests

        /** Local Execution % от всех запросов. */
        val localExecutionPercent: Double
            get() = percent(localExecuted, totalRequests)

        /** Cloud Execution % от всех запросов. */
        val cloudExecutionPercent: Double
            get() = percent(cloudRequests, totalRequests)

        /**
         * Остаток (уточнения, отказы, privacy-блоки, офлайн) — честно виден,
         * а не спрятан в знаменателе.
         */
        val notExecuted: Long
            get() = totalRequests - localExecuted - cloudRequests

        /**
         * Достигнут ли целевой ориентир первой версии (60%). Доля локальных
         * полос считается вместе с tool/agent/direct — это и есть
         * «Tool/local execution» из спецификации.
         */
        val meetsFirstVersionTarget: Boolean
            get() = totalRequests >= MIN_SAMPLES_FOR_TARGET && localExecutionPercent >= FIRST_VERSION_TARGET_PERCENT

        private fun percent(part: Long, whole: Long): Double =
            if (whole <= 0L) 0.0 else 100.0 * part / whole
    }

    /**
     * Периодическая сводка в лог (каждые [LOG_EVERY] запросов): проценты и
     * эскалации — единственный «UI» метрик до появления экрана диагностики.
     */
    private fun maybeLogSummary(total: Long) {
        if (total % LOG_EVERY != 0L) return
        val s = snapshot()
        Log.i(
            TAG,
            "ExecutionRouter | total=${s.totalRequests} | local%=${"%.1f".format(s.localExecutionPercent)} " +
                "(tool=${s.toolRequests} agent=${s.agentRequests} local=${s.localRequests} direct=${s.directRequests}) | " +
                "cloud%=${"%.1f".format(s.cloudExecutionPercent)} (escalations=${s.cloudEscalations}) | " +
                "failedLocal=${s.failedLocal} | target≥$FIRST_VERSION_TARGET_PERCENT%"
        )
    }

    companion object {
        private const val TAG = "ExecRouterMetrics"

        /** Периодичность сводки в лог. */
        const val LOG_EVERY = 25L

        /**
         * Целевой ориентир первой версии (spec): Tool/local execution 60–70%+.
         * Метрика, НЕ жёсткое требование: не влияет на роутинг.
         */
        const val FIRST_VERSION_TARGET_PERCENT = 60.0

        /** До этой выборки процент неинформативен — target не «достигается». */
        const val MIN_SAMPLES_FOR_TARGET = 20L
    }
}
