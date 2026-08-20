package com.jarvis.assistant.benchmark

import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.ExecutionResult
import com.jarvis.assistant.agent.decision.ExecutionType

/**
 * Runner benchmark: прогоняет dataset через НАСТОЯЩИЙ ExecutionDecisionEngine
 * и собирает сырые результаты.
 */
class BenchmarkRunner(private val rig: BenchmarkHarness.Rig) {

    /**
     * @param runs сколько раз прогнать набор. Маршрут детерминирован, поэтому
     *        accuracy считается по первому прогону; повторы нужны для latency
     *        (п. 38 ТЗ).
     */
    suspend fun run(cases: List<BenchmarkCase>, runs: Int = 1): List<BenchmarkResult> {
        val results = mutableListOf<BenchmarkResult>()

        for (runIndex in 0 until runs) {
            for (case in cases) {
                results += execute(case, runIndex)
            }
        }
        return results
    }

    private suspend fun execute(case: BenchmarkCase, runIndex: Int): BenchmarkResult {
        val request = ExecutionRequest(
            text = case.command,
            source = case.source,
            requiresWeb = case.requiresWeb,
            requiresDeviceControl = case.requiresDeviceControl,
            privacyLevel = case.privacyLevel
        )

        val cloudBefore = rig.cloud.calls.get()
        val startedAt = System.currentTimeMillis()

        val result = try {
            rig.engine.execute(request)
        } catch (e: Throwable) {
            ExecutionResult.Error("harness failure: ${e.javaClass.simpleName}")
        }

        val latency = System.currentTimeMillis() - startedAt
        val cloudUsed = rig.cloud.calls.get() > cloudBefore

        val actualRoute: ExecutionType? = when (result) {
            is ExecutionResult.Success -> result.executionType
            else -> null
        }

        val wasRefusal = result is ExecutionResult.Error
        // Уточнение = система явно просит подтверждение/дополнение.
        val wasClarification = result is ExecutionResult.ConfirmationRequired

        val success = when (result) {
            is ExecutionResult.Success -> true
            is ExecutionResult.ConfirmationRequired -> true
            is ExecutionResult.Error -> false
        }

        val errorCode = (result as? ExecutionResult.Error)?.reason?.name

        val responseChars = when (result) {
            is ExecutionResult.Success -> result.text.length
            is ExecutionResult.ConfirmationRequired -> result.promptMessage.length
            is ExecutionResult.Error -> result.message.length
        }

        return BenchmarkResult(
            caseId = case.id,
            category = case.category,
            command = case.command,
            expectedRoute = case.expectedExecutionType,
            actualRoute = actualRoute,
            routeCorrect = case.expectedExecutionType.matches(
                actual = actualRoute,
                wasRefusal = wasRefusal,
                wasClarification = wasClarification
            ),
            success = success,
            expectedSuccess = case.expectedSuccess,
            wasRefusal = wasRefusal,
            wasClarification = wasClarification,
            latencyMs = latency,
            cloudRequest = cloudUsed,
            provider = if (cloudUsed) rig.cloud.lastProvider else null,
            model = if (cloudUsed) rig.cloud.lastModel else null,
            inputTokens = null,
            outputTokens = null,
            totalTokens = if (cloudUsed) 128L else null,
            errorCode = errorCode,
            responseChars = responseChars,
            runIndex = runIndex,
            timestampMs = startedAt
        )
    }
}
