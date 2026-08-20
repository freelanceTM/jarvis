package com.jarvis.assistant.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Запуск JARVIS Benchmark v1 и генерация отчётов (Этап 4).
 *
 * Артефакты пишутся в `app/build/benchmark/`:
 *  - benchmark-results.json
 *  - benchmark-results.csv
 *  - benchmark-report.txt
 *
 * Тест НЕ падает из-за низкой accuracy: цель этапа — честный baseline,
 * а не зелёная галочка. Пороговые проверки живут отдельно, в
 * [BenchmarkRegressionTest].
 */
class BenchmarkRunnerTest {

    private companion object {
        const val RUNS = 3
        val OUTPUT_DIR = File("build/benchmark")
    }

    @Test
    fun `dataset is valid and covers required categories`() {
        val problems = BenchmarkDataset.validate()
        assertTrue("Проблемы датасета: $problems", problems.isEmpty())

        val distribution = BenchmarkDataset.distribution()
        println("Распределение: $distribution")

        assertTrue("Нужно минимум 50 команд", BenchmarkDataset.cases.size >= 50)
        // Все обязательные категории представлены.
        BenchmarkCategory.values().forEach { category ->
            assertTrue(
                "Категория $category отсутствует",
                distribution.containsKey(category)
            )
        }
    }

    @Test
    fun `run benchmark and produce baseline report`() = runBlocking {
        val rig = BenchmarkHarness.build()
        val runner = BenchmarkRunner(rig)
        val cases = BenchmarkDataset.cases

        val results = runner.run(cases, runs = RUNS)
        val report = BenchmarkMetrics.buildReport(results)

        val env = BenchmarkEnvironment(
            benchmarkVersion = "1.0.0",
            datasetVersion = BenchmarkDataset.VERSION,
            datasetSize = cases.size,
            appVersion = "0.2.0",
            serverVersion = "0.2.0 (JARVIS API module)",
            localModelId = "gemma3-1b-it-int4",
            localModelQuantization = "int4 QAT (dynamic, block 128)",
            localRuntime = "MediaPipe LLM Inference 0.10.35 (SIMULATED on JVM)",
            providerModels = mapOf(
                "GROQ" to "llama-3.3-70b-versatile",
                "GEMINI" to "gemini-1.5-flash",
                "OPENROUTER" to "meta-llama/llama-3.3-70b-instruct"
            ),
            runtimeDescription = "JVM ${System.getProperty("java.version")} / " +
                "${System.getProperty("os.name")} / ${Runtime.getRuntime().availableProcessors()} CPU",
            executionMode = "JVM harness — реальные FastCommandRouter, " +
                "ExecutionDecisionEngine, CognitivePlanner, AgentCognitiveLoop, ToolExecutor; " +
                "симулированы: локальный инференс, сеть/провайдеры, Android-тулы",
            runs = RUNS,
            timestampMs = System.currentTimeMillis(),
            limitations = listOf(
                "Прогон на JVM, не на Android-устройстве",
                "Локальный инференс симулирован фиксированными задержками (нет реального GPU/NPU)",
                "Сетевые вызовы к провайдерам симулированы — реальная latency провайдеров не измерена",
                "Battery/CPU/RAM не измерены: требуется физическое устройство",
                "Стоимость облака не рассчитана: тарифы провайдеров в конфигурации отсутствуют",
                "Датасет синтетический: ${BenchmarkDataset.NATURE}",
                "Качество ответов (human evaluation) не оценивалось — измерена только маршрутизация"
            )
        )

        OUTPUT_DIR.mkdirs()
        File(OUTPUT_DIR, "benchmark-results.json")
            .writeText(BenchmarkMetrics.toJson(env, report, results))
        File(OUTPUT_DIR, "benchmark-results.csv")
            .writeText(BenchmarkMetrics.toCsv(results))

        val textReport = renderReport(env, report, rig)
        File(OUTPUT_DIR, "benchmark-report.txt").writeText(textReport)
        println(textReport)

        // Санити-проверки самого benchmark, а не качества системы.
        assertEquals(cases.size * RUNS, results.size)
        assertEquals(cases.size, report.totalCases)
        assertTrue("Отчёт должен содержать результаты", report.routeDistribution.isNotEmpty())
    }

    private fun renderReport(
        env: BenchmarkEnvironment,
        report: BenchmarkMetrics.Report,
        rig: BenchmarkHarness.Rig
    ): String = buildString {
        appendLine("=".repeat(78))
        appendLine("JARVIS BENCHMARK v${env.benchmarkVersion} — BASELINE")
        appendLine("=".repeat(78))
        appendLine()
        appendLine("ОКРУЖЕНИЕ")
        appendLine("  dataset:        v${env.datasetVersion}, ${env.datasetSize} команд, ${BenchmarkDataset.NATURE}")
        appendLine("  app / server:   ${env.appVersion} / ${env.serverVersion}")
        appendLine("  local model:    ${env.localModelId} (${env.localModelQuantization})")
        appendLine("  local runtime:  ${env.localRuntime}")
        appendLine("  providers:      ${env.providerModels}")
        appendLine("  runtime:        ${env.runtimeDescription}")
        appendLine("  runs:           ${env.runs}")
        appendLine("  mode:           ${env.executionMode}")
        appendLine()
        appendLine("ОГРАНИЧЕНИЯ ИЗМЕРЕНИЯ")
        env.limitations.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("-".repeat(78))
        appendLine("МАРШРУТИЗАЦИЯ")
        appendLine("-".repeat(78))
        appendLine("  Routing accuracy:        ${pct(report.routingAccuracyPercent)}  (${
            report.categoryAccuracy.sumOf { it.correct }
        }/${report.totalCases})")
        appendLine("  Success rate:            ${pct(report.successRatePercent)}")
        appendLine("  Error rate:              ${pct(report.errorRatePercent)}")
        appendLine("  Cloud request ratio:     ${pct(report.cloudRequestRatioPercent)}")
        appendLine()
        appendLine("  ТОЧНОСТЬ ПО КАТЕГОРИЯМ")
        report.categoryAccuracy.forEach {
            appendLine(String.format(
                "    %-12s %6s   (%d/%d)",
                it.category.name, pct(it.accuracyPercent), it.correct, it.total
            ))
        }
        appendLine()
        appendLine("  ОПАСНЫЕ ОШИБКИ")
        appendLine("    False Local:           ${report.falseLocalCount} (${pct(report.falseLocalRatePercent)} от cloud/agent-кейсов)")
        appendLine("    Unnecessary Cloud:     ${report.unnecessaryCloudCount} (${pct(report.unnecessaryCloudRatePercent)} от local-кейсов)")
        appendLine("    Device false positive: ${report.deviceFalsePositiveCount} (${pct(report.deviceFalsePositiveRatePercent)})")
        appendLine()
        appendLine("  РАСПРЕДЕЛЕНИЕ ФАКТИЧЕСКИХ МАРШРУТОВ")
        report.routeDistribution.entries.sortedByDescending { it.value }.forEach {
            appendLine(String.format("    %-14s %3d", it.key, it.value))
        }
        appendLine()
        appendLine("  CONFUSION MATRIX")
        report.confusionMatrix.render().lines().forEach { if (it.isNotBlank()) appendLine("    $it") }
        appendLine()
        appendLine("-".repeat(78))
        appendLine("LATENCY (${env.runs} прогона, все кейсы)")
        appendLine("-".repeat(78))
        appendLine("  Общая:")
        appendLine(latencyLine(report.overallLatency))
        appendLine()
        appendLine("  По маршрутам:")
        report.latencyByRoute.entries.sortedBy { it.key }.forEach { (route, stats) ->
            appendLine("    $route:")
            appendLine(latencyLine(stats, indent = 6))
        }
        appendLine()
        appendLine("-".repeat(78))
        appendLine("LOCAL AI")
        appendLine("-".repeat(78))
        val rt = rig.localRuntime
        appendLine("  Инференсов:              ${rt.inferences.get()}")
        appendLine("  Cold start (load+gen):   ${rt.coldStartMs} ms")
        if (rt.warmLatencies.isNotEmpty()) {
            appendLine("  Warm p50:                ${BenchmarkMetrics.percentile(rt.warmLatencies, 50)} ms")
            appendLine("  Warm p95:                ${BenchmarkMetrics.percentile(rt.warmLatencies, 95)} ms")
            appendLine("  Warm avg:                ${rt.warmLatencies.average().toLong()} ms")
        }
        if (rt.timeToFirstToken.isNotEmpty()) {
            appendLine("  TTFT p50:                ${BenchmarkMetrics.percentile(rt.timeToFirstToken, 50)} ms")
        }
        if (rt.tokensPerSecond.isNotEmpty()) {
            appendLine(String.format("  Tokens/sec (avg):        %.1f", rt.tokensPerSecond.average()))
        }
        appendLine("  Отказов локальной модели: ${rig.localExecutor.declines.get()}")
        appendLine("  Обработано локально:      ${rig.localExecutor.handled.get()}")
        appendLine("  RAM / CPU / battery:      НЕ ИЗМЕРЕНО (нужно физическое устройство)")
        appendLine()
        appendLine("-".repeat(78))
        appendLine("CLOUD")
        appendLine("-".repeat(78))
        appendLine("  Вызовов облака:          ${rig.cloud.calls.get()}")
        if (rig.cloud.networkLatencies.isNotEmpty()) {
            appendLine("  Network latency (сим.):  ${rig.cloud.networkLatencies.average().toLong()} ms")
        }
        if (rig.cloud.providerLatencies.isNotEmpty()) {
            appendLine("  Provider latency (сим.): ${rig.cloud.providerLatencies.average().toLong()} ms")
            appendLine("  Provider p95 (сим.):     ${BenchmarkMetrics.percentile(rig.cloud.providerLatencies, 95)} ms")
        }
        appendLine("  Input tokens:            ${rig.cloud.inputTokens}")
        appendLine("  Output tokens:           ${rig.cloud.outputTokens}")
        appendLine("  Стоимость:               UNKNOWN (тарифы не заданы в конфигурации сервера)")
        appendLine("  Fallback между провайд.: 0 (в стенде один симулированный провайдер)")
        appendLine()
        appendLine("-".repeat(78))
        appendLine("AGENT")
        appendLine("-".repeat(78))
        appendLine("  Планов построено:        ${rig.agent.plansBuilt.get()}")
        appendLine("  Запусков цикла:          ${rig.agent.runs.get()}")
        if (rig.agent.stepCounts.isNotEmpty()) {
            appendLine(String.format("  Шагов в плане (avg):     %.1f", rig.agent.stepCounts.average()))
            appendLine("  Шагов в плане (max):     ${rig.agent.stepCounts.max()}")
        }
        appendLine()
        appendLine("-".repeat(78))
        appendLine("ОШИБКИ")
        appendLine("-".repeat(78))
        if (report.errorsByCode.isEmpty()) appendLine("  нет")
        report.errorsByCode.entries.sortedByDescending { it.value }.forEach {
            appendLine(String.format("  %-28s %d", it.key, it.value))
        }
        appendLine()
        appendLine("-".repeat(78))
        appendLine("НЕВЕРНО СМАРШРУТИЗИРОВАННЫЕ КЕЙСЫ (${report.failedCases.size})")
        appendLine("-".repeat(78))
        report.failedCases.forEach { r ->
            val case = BenchmarkDataset.cases.first { it.id == r.caseId }
            appendLine("  ${r.caseId} [${r.category}]")
            appendLine("    команда:  \"${r.command.take(70)}${if (r.command.length > 70) "…" else ""}\"")
            appendLine("    ожидали:  ${r.expectedRoute}")
            appendLine("    получили: ${r.actualRoute ?: if (r.wasRefusal) "REFUSAL" else "NONE"}")
            case.knownGap?.let { appendLine("    известный пробел: $it") }
        }
        appendLine()
        appendLine("=".repeat(78))
    }

    private fun latencyLine(s: BenchmarkMetrics.LatencyStats, indent: Int = 4): String {
        val pad = " ".repeat(indent)
        return "${pad}n=${s.count}  min=${s.min}  p50=${s.p50}  p90=${s.p90}  " +
            "p95=${s.p95}  p99=${s.p99}  max=${s.max}  avg=${s.average} (ms)"
    }

    private fun pct(value: Double) = String.format("%.1f%%", value)
}
