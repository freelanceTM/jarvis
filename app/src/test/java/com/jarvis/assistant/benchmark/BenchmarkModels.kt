package com.jarvis.assistant.benchmark

import com.jarvis.assistant.agent.decision.ExecutionType
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource

/**
 * JARVIS Benchmark v1 — модели данных (Этап 4).
 *
 * Цель этапа — ЧЕСТНЫЙ baseline, а не красивые цифры. Ground truth
 * фиксируется ДО запуска и не подгоняется под фактический результат.
 */

/** Категория сценария — для отчёта по категориям. */
enum class BenchmarkCategory {
    DEVICE,
    LOCAL_AI,
    CLOUD_AI,
    CLOUD_WEB,
    AGENT,
    AMBIGUOUS,
    EDGE_CASE,
    PRIVACY
}

/**
 * Ожидаемый путь исполнения.
 *
 * Отдельно от [ExecutionType], потому что benchmark должен уметь выражать
 * исходы, которых в enum движка нет:
 *  - [CLARIFICATION] — система обязана попросить уточнение (п. 11 ТЗ);
 *  - [REFUSAL]       — обязана отказать (privacy / невозможность);
 *  - [ANY_NON_DEVICE] — допустим и LOCAL_AI, и CLOUD_AI (обоснованная
 *    неоднозначность), но НЕ device-действие.
 */
enum class ExpectedExecutionType {
    DEVICE_TOOL,
    LOCAL_AI,
    CLOUD_AI,
    AGENT,
    CLARIFICATION,
    REFUSAL,
    ANY_NON_DEVICE;

    /** Совпадает ли фактический маршрут с ожидаемым. */
    fun matches(actual: ExecutionType?, wasRefusal: Boolean, wasClarification: Boolean): Boolean =
        when (this) {
            DEVICE_TOOL -> actual == ExecutionType.DEVICE_TOOL && !wasRefusal
            LOCAL_AI -> actual == ExecutionType.LOCAL_AI
            CLOUD_AI -> actual == ExecutionType.CLOUD_AI
            AGENT -> actual == ExecutionType.AGENT
            CLARIFICATION -> wasClarification
            REFUSAL -> wasRefusal
            ANY_NON_DEVICE -> actual == ExecutionType.LOCAL_AI || actual == ExecutionType.CLOUD_AI
        }
}

/**
 * Один случай benchmark с заранее определённым ground truth.
 *
 * @param rationale ПОЧЕМУ ожидается именно такой маршрут. Обязательное поле:
 *        не даёт задним числом «переобъяснить» ожидание под факт.
 */
data class BenchmarkCase(
    val id: String,
    val category: BenchmarkCategory,
    val command: String,
    val expectedExecutionType: ExpectedExecutionType,
    val rationale: String,
    val requiresWeb: Boolean = false,
    val requiresDeviceControl: Boolean = false,
    val privacyLevel: PrivacyLevel = PrivacyLevel.NORMAL,
    val source: RequestSource = RequestSource.VOICE,
    val expectedSuccess: Boolean = true,
    /**
     * Известное ограничение системы: маршрут заведомо будет неверным.
     * Такие случаи НЕ исключаются из метрик — они и есть находки benchmark.
     */
    val knownGap: String? = null
)

/** Результат одного прогона (п. 16 ТЗ). */
data class BenchmarkResult(
    val caseId: String,
    val category: BenchmarkCategory,
    val command: String,
    val expectedRoute: ExpectedExecutionType,
    val actualRoute: ExecutionType?,
    val routeCorrect: Boolean,
    val success: Boolean,
    val expectedSuccess: Boolean,
    val wasRefusal: Boolean,
    val wasClarification: Boolean,
    val latencyMs: Long,
    val cloudRequest: Boolean,
    val provider: String?,
    val model: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val errorCode: String?,
    val responseChars: Int,
    val runIndex: Int,
    val timestampMs: Long
) {
    /** Ожидали облако/веб, а ушло в локальную модель — риск галлюцинации. */
    val isFalseLocal: Boolean
        get() = (expectedRoute == ExpectedExecutionType.CLOUD_AI ||
            expectedRoute == ExpectedExecutionType.AGENT) &&
            actualRoute == ExecutionType.LOCAL_AI

    /** Ожидали локально, ушло в облако — лишний сетевой запрос и трата денег. */
    val isUnnecessaryCloud: Boolean
        get() = expectedRoute == ExpectedExecutionType.LOCAL_AI &&
            actualRoute == ExecutionType.CLOUD_AI

    /** Неожиданное device-действие — самая опасная ошибка маршрутизации. */
    val isDeviceFalsePositive: Boolean
        get() = expectedRoute != ExpectedExecutionType.DEVICE_TOOL &&
            actualRoute == ExecutionType.DEVICE_TOOL &&
            !wasRefusal
}

/** Окружение прогона — для воспроизводимости (п. 36 ТЗ). */
data class BenchmarkEnvironment(
    val benchmarkVersion: String,
    val datasetVersion: String,
    val datasetSize: Int,
    val appVersion: String,
    val serverVersion: String,
    val localModelId: String,
    val localModelQuantization: String,
    val localRuntime: String,
    val providerModels: Map<String, String>,
    val runtimeDescription: String,
    val executionMode: String,
    val runs: Int,
    val timestampMs: Long,
    val limitations: List<String>
)
