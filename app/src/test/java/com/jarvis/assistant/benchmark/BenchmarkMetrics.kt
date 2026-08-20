package com.jarvis.assistant.benchmark

import com.jarvis.assistant.agent.decision.ExecutionType

/**
 * Агрегация метрик benchmark (пункты 17-33 ТЗ).
 *
 * Чистые вычисления без побочных эффектов — тестируются отдельно.
 */
object BenchmarkMetrics {

    /** Перцентили по методу nearest-rank. */
    fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    data class LatencyStats(
        val count: Int,
        val min: Long,
        val max: Long,
        val average: Long,
        val p50: Long,
        val p90: Long,
        val p95: Long,
        val p99: Long
    ) {
        companion object {
            val EMPTY = LatencyStats(0, 0, 0, 0, 0, 0, 0, 0)

            fun from(values: List<Long>): LatencyStats {
                if (values.isEmpty()) return EMPTY
                return LatencyStats(
                    count = values.size,
                    min = values.min(),
                    max = values.max(),
                    average = values.average().toLong(),
                    p50 = percentile(values, 50),
                    p90 = percentile(values, 90),
                    p95 = percentile(values, 95),
                    p99 = percentile(values, 99)
                )
            }
        }
    }

    data class CategoryAccuracy(
        val category: BenchmarkCategory,
        val total: Int,
        val correct: Int
    ) {
        val accuracyPercent: Double get() = if (total == 0) 0.0 else correct * 100.0 / total
    }

    /**
     * Confusion matrix маршрутизации (п. 33 ТЗ).
     * Ключ — ожидаемый маршрут, значение — распределение фактических.
     */
    data class ConfusionMatrix(
        val rows: Map<ExpectedExecutionType, Map<String, Int>>
    ) {
        fun render(): String {
            val columns = listOf("DEVICE_TOOL", "LOCAL_AI", "CLOUD_AI", "AGENT", "REFUSAL", "NONE")
            val sb = StringBuilder()
            sb.append(String.format("%-18s", "expected\\actual"))
            columns.forEach { sb.append(String.format("%-13s", it)) }
            sb.append('\n')
            for ((expected, actuals) in rows.entries.sortedBy { it.key.name }) {
                sb.append(String.format("%-18s", expected.name))
                columns.forEach { col -> sb.append(String.format("%-13s", actuals[col] ?: 0)) }
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    data class Report(
        val totalCases: Int,
        val totalRuns: Int,
        val routingAccuracyPercent: Double,
        val successRatePercent: Double,
        val errorRatePercent: Double,
        val categoryAccuracy: List<CategoryAccuracy>,
        val overallLatency: LatencyStats,
        val latencyByRoute: Map<String, LatencyStats>,
        val routeDistribution: Map<String, Int>,
        val cloudRequestRatioPercent: Double,
        val falseLocalCount: Int,
        val falseLocalRatePercent: Double,
        val unnecessaryCloudCount: Int,
        val unnecessaryCloudRatePercent: Double,
        val deviceFalsePositiveCount: Int,
        val deviceFalsePositiveRatePercent: Double,
        val confusionMatrix: ConfusionMatrix,
        val errorsByCode: Map<String, Int>,
        val totalTokens: Long,
        val failedCases: List<BenchmarkResult>
    )

    fun buildReport(results: List<BenchmarkResult>): Report {
        if (results.isEmpty()) {
            return Report(
                0, 0, 0.0, 0.0, 0.0, emptyList(), LatencyStats.EMPTY, emptyMap(),
                emptyMap(), 0.0, 0, 0.0, 0, 0.0, 0, 0.0,
                ConfusionMatrix(emptyMap()), emptyMap(), 0, emptyList()
            )
        }

        // Routing accuracy считается по ПЕРВОМУ прогону каждого кейса:
        // маршрут детерминирован, повторы нужны только для latency.
        val firstRun = results.filter { it.runIndex == 0 }

        val correct = firstRun.count { it.routeCorrect }
        val succeeded = firstRun.count { it.success == it.expectedSuccess }

        val categoryAccuracy = firstRun
            .groupBy { it.category }
            .map { (cat, list) ->
                CategoryAccuracy(cat, list.size, list.count { it.routeCorrect })
            }
            .sortedBy { it.category.name }

        val latencyByRoute = results
            .groupBy { it.actualRoute?.name ?: "NONE" }
            .mapValues { (_, list) -> LatencyStats.from(list.map { it.latencyMs }) }

        val routeDistribution = firstRun
            .groupingBy { it.actualRoute?.name ?: "NONE" }
            .eachCount()

        val cloudCount = firstRun.count { it.cloudRequest }

        val falseLocal = firstRun.filter { it.isFalseLocal }
        val unnecessaryCloud = firstRun.filter { it.isUnnecessaryCloud }
        val deviceFp = firstRun.filter { it.isDeviceFalsePositive }

        // Знаменатель False Local — только те кейсы, где облако/агент ожидались.
        val cloudExpectedCount = firstRun.count {
            it.expectedRoute == ExpectedExecutionType.CLOUD_AI ||
                it.expectedRoute == ExpectedExecutionType.AGENT
        }
        val localExpectedCount = firstRun.count {
            it.expectedRoute == ExpectedExecutionType.LOCAL_AI
        }

        val confusion = ConfusionMatrix(
            firstRun.groupBy { it.expectedRoute }.mapValues { (_, list) ->
                list.groupingBy { r ->
                    when {
                        r.wasRefusal -> "REFUSAL"
                        r.actualRoute != null -> r.actualRoute.name
                        else -> "NONE"
                    }
                }.eachCount()
            }
        )

        val errorsByCode = firstRun
            .mapNotNull { it.errorCode }
            .groupingBy { it }
            .eachCount()

        return Report(
            totalCases = firstRun.size,
            totalRuns = results.size,
            routingAccuracyPercent = correct * 100.0 / firstRun.size,
            successRatePercent = succeeded * 100.0 / firstRun.size,
            errorRatePercent = firstRun.count { !it.success } * 100.0 / firstRun.size,
            categoryAccuracy = categoryAccuracy,
            overallLatency = LatencyStats.from(results.map { it.latencyMs }),
            latencyByRoute = latencyByRoute,
            routeDistribution = routeDistribution,
            cloudRequestRatioPercent = cloudCount * 100.0 / firstRun.size,
            falseLocalCount = falseLocal.size,
            falseLocalRatePercent =
                if (cloudExpectedCount == 0) 0.0 else falseLocal.size * 100.0 / cloudExpectedCount,
            unnecessaryCloudCount = unnecessaryCloud.size,
            unnecessaryCloudRatePercent =
                if (localExpectedCount == 0) 0.0 else unnecessaryCloud.size * 100.0 / localExpectedCount,
            deviceFalsePositiveCount = deviceFp.size,
            deviceFalsePositiveRatePercent = deviceFp.size * 100.0 / firstRun.size,
            confusionMatrix = confusion,
            errorsByCode = errorsByCode,
            totalTokens = results.sumOf { it.totalTokens ?: 0L },
            failedCases = firstRun.filter { !it.routeCorrect }
        )
    }

    // ------------------------------------------------------------- экспорт

    fun toCsv(results: List<BenchmarkResult>): String = buildString {
        appendLine(
            "caseId,category,expectedRoute,actualRoute,routeCorrect,success,latencyMs," +
                "cloudRequest,provider,model,inputTokens,outputTokens,totalTokens," +
                "errorCode,responseChars,runIndex,timestampMs,command"
        )
        for (r in results) {
            appendLine(
                listOf(
                    r.caseId,
                    r.category.name,
                    r.expectedRoute.name,
                    r.actualRoute?.name ?: "NONE",
                    r.routeCorrect,
                    r.success,
                    r.latencyMs,
                    r.cloudRequest,
                    r.provider ?: "",
                    r.model ?: "",
                    r.inputTokens?.toString() ?: "",
                    r.outputTokens?.toString() ?: "",
                    r.totalTokens?.toString() ?: "",
                    r.errorCode ?: "",
                    r.responseChars,
                    r.runIndex,
                    r.timestampMs,
                    csvEscape(r.command)
                ).joinToString(",")
            )
        }
    }

    private fun csvEscape(value: String): String {
        val cleaned = value.replace("\n", " ").replace("\r", " ")
        return if (cleaned.contains(',') || cleaned.contains('"')) {
            "\"" + cleaned.replace("\"", "\"\"") + "\""
        } else {
            cleaned
        }
    }

    fun toJson(
        env: BenchmarkEnvironment,
        report: Report,
        results: List<BenchmarkResult>
    ): String = buildString {
        appendLine("{")
        appendLine("""  "environment": {""")
        appendLine("""    "benchmarkVersion": "${env.benchmarkVersion}",""")
        appendLine("""    "datasetVersion": "${env.datasetVersion}",""")
        appendLine("""    "datasetSize": ${env.datasetSize},""")
        appendLine("""    "appVersion": "${env.appVersion}",""")
        appendLine("""    "serverVersion": "${env.serverVersion}",""")
        appendLine("""    "localModelId": "${env.localModelId}",""")
        appendLine("""    "localModelQuantization": "${env.localModelQuantization}",""")
        appendLine("""    "localRuntime": "${env.localRuntime}",""")
        appendLine("""    "providerModels": {${
            env.providerModels.entries.joinToString(",") { """"${it.key}":"${it.value}"""" }
        }},""")
        appendLine("""    "runtime": "${env.runtimeDescription}",""")
        appendLine("""    "executionMode": "${env.executionMode}",""")
        appendLine("""    "runs": ${env.runs},""")
        appendLine("""    "timestampMs": ${env.timestampMs},""")
        appendLine("""    "limitations": [${
            env.limitations.joinToString(",") { """"${jsonEscape(it)}"""" }
        }]""")
        appendLine("  },")
        appendLine("""  "summary": {""")
        appendLine("""    "totalCases": ${report.totalCases},""")
        appendLine("""    "totalRuns": ${report.totalRuns},""")
        appendLine("""    "routingAccuracyPercent": ${fmt(report.routingAccuracyPercent)},""")
        appendLine("""    "successRatePercent": ${fmt(report.successRatePercent)},""")
        appendLine("""    "errorRatePercent": ${fmt(report.errorRatePercent)},""")
        appendLine("""    "cloudRequestRatioPercent": ${fmt(report.cloudRequestRatioPercent)},""")
        appendLine("""    "falseLocalCount": ${report.falseLocalCount},""")
        appendLine("""    "falseLocalRatePercent": ${fmt(report.falseLocalRatePercent)},""")
        appendLine("""    "unnecessaryCloudCount": ${report.unnecessaryCloudCount},""")
        appendLine("""    "unnecessaryCloudRatePercent": ${fmt(report.unnecessaryCloudRatePercent)},""")
        appendLine("""    "deviceFalsePositiveCount": ${report.deviceFalsePositiveCount},""")
        appendLine("""    "totalTokens": ${report.totalTokens}""")
        appendLine("  },")
        appendLine("""  "latency": {""")
        appendLine("""    "overall": ${latencyJson(report.overallLatency)},""")
        appendLine("""    "byRoute": {${
            report.latencyByRoute.entries.joinToString(",") {
                """"${it.key}":${latencyJson(it.value)}"""
            }
        }}""")
        appendLine("  },")
        appendLine("""  "categoryAccuracy": [${
            report.categoryAccuracy.joinToString(",") {
                """{"category":"${it.category}","total":${it.total},""" +
                    """"correct":${it.correct},"accuracyPercent":${fmt(it.accuracyPercent)}}"""
            }
        }],""")
        appendLine("""  "routeDistribution": {${
            report.routeDistribution.entries.joinToString(",") { """"${it.key}":${it.value}""" }
        }},""")
        appendLine("""  "errorsByCode": {${
            report.errorsByCode.entries.joinToString(",") { """"${it.key}":${it.value}""" }
        }},""")
        appendLine("""  "results": [""")
        results.forEachIndexed { index, r ->
            append("    {")
            append(""""caseId":"${r.caseId}",""")
            append(""""category":"${r.category}",""")
            append(""""command":"${jsonEscape(r.command.take(200))}",""")
            append(""""expectedRoute":"${r.expectedRoute}",""")
            append(""""actualRoute":${r.actualRoute?.let { "\"$it\"" } ?: "null"},""")
            append(""""routeCorrect":${r.routeCorrect},""")
            append(""""success":${r.success},""")
            append(""""wasRefusal":${r.wasRefusal},""")
            append(""""wasClarification":${r.wasClarification},""")
            append(""""latencyMs":${r.latencyMs},""")
            append(""""cloudRequest":${r.cloudRequest},""")
            append(""""provider":${r.provider?.let { "\"$it\"" } ?: "null"},""")
            append(""""totalTokens":${r.totalTokens ?: "null"},""")
            append(""""errorCode":${r.errorCode?.let { "\"$it\"" } ?: "null"},""")
            append(""""runIndex":${r.runIndex}""")
            append("}")
            if (index != results.lastIndex) append(",")
            appendLine()
        }
        appendLine("  ]")
        append("}")
    }

    private fun latencyJson(s: LatencyStats) =
        """{"count":${s.count},"min":${s.min},"max":${s.max},"avg":${s.average},""" +
            """"p50":${s.p50},"p90":${s.p90},"p95":${s.p95},"p99":${s.p99}}"""

    private fun fmt(value: Double) = String.format("%.2f", value)

    private fun jsonEscape(value: String) = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("\t", " ")
}
