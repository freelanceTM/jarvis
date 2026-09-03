package com.jarvis.assistant.agent.engine

import android.util.Log
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.observation.AgentObservationEngine
import com.jarvis.assistant.agent.observation.NextActionHint
import com.jarvis.assistant.agent.observation.Observation
import com.jarvis.assistant.agent.planner.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent state machine:
 *
 *   PLAN → EXECUTE → OBSERVE → goal achieved?
 *                                ├── YES → DONE
 *                                └── NO  → REPLAN → EXECUTE ...
 *
 * Жёсткие guardrails:
 *  - не более [MAX_REPLANS] перепланирований за один запрос;
 *  - не более [MAX_TOTAL_STEPS] выполненных шагов (защита от зацикливания
 *    планов, которые формально «прогрессируют», но не сходятся);
 *  - перепланирование запускается только когда наблюдение говорит, что оно
 *    имеет смысл: если Android требует действия пользователя или возможность
 *    не поддерживается, повторять шаг бессмысленно.
 */
@Singleton
class AgentCognitiveLoop @Inject constructor(
    private val cognitivePlanner: CognitivePlanner,
    private val toolExecutor: ToolExecutor,
    private val observationEngine: AgentObservationEngine
) {
    companion object {
        private const val TAG = "CognitiveLoop"

        /** Максимум перепланирований за один пользовательский запрос. */
        const val MAX_REPLANS = 2

        /** Абсолютный предел выполненных шагов — страховка от бесконечного цикла. */
        const val MAX_TOTAL_STEPS = 12

        /**
         * AR-03 Part A: жёсткий upper-bound на весь cognitive loop
         * (PLAN → EXECUTE → OBSERVE → REPLAN).
         *
         * 8 секунд выбраны с запасом до 10-секундного ANR-порога и с учётом того,
         * что отдельные tool-вызовы уже имеют собственный [JarvisTool.executionTimeoutMs]
         * (по умолчанию 4 с). При истечении бюджет корутины отменяются
         * structured-concurrency'ом; новые tool calls не запускаются.
         */
        const val LOOP_BUDGET_MS = 8_000L
    }

    /**
     * AR-03: выполняет [initialPlan] под жёстким wall-clock budget.
     *
     * Таймаут ограничивает ВЕСЬ цикл, а не отдельный tool-call — это защищает
     * от сценариев "по одному быстрому tool call, но 50 шагов подряд", которые
     * обходят per-tool budget. При истечении бюджета корректно возвращается
     * частичный результат; CancellationException пробрасывается в Structured
     * Concurrency и не маскируется как "успех".
     */
    suspend fun runPlan(initialPlan: ExecutionPlan): PlanExecutionSummary = withContext(Dispatchers.IO) {
        try {
            withTimeout(LOOP_BUDGET_MS) {
                runPlanInternal(initialPlan)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Cognitive loop budget exceeded (${LOOP_BUDGET_MS}ms); returning partial result.")
            PlanExecutionSummary(
                plan = initialPlan,
                observations = emptyList(),
                finalVoiceSummary = "Не удалось закончить план вовремя, сэр.",
                isAllSuccessful = false,
                pendingConfirmation = null,
                timedOut = true
            )
        }
    }

    private suspend fun runPlanInternal(initialPlan: ExecutionPlan): PlanExecutionSummary {
        val stepObservations = mutableListOf<StepObservation>()
        val summaries = mutableListOf<String>()
        val blockedNotices = mutableListOf<String>()

        var currentPlan = initialPlan
        var replanAttempts = 0
        var executedSteps = 0
        var isAllSuccessful = true
        var currentStepIdx = 0

        // Accessibility Lockdown: успешное чтение экрана означает, что
        // summaries содержат экральный контент — персистентность обязана
        // сохранить placeholder вместо текста (ScreenContentPrivacy).
        var screenContentSeen = false

        while (currentStepIdx < currentPlan.steps.size) {
            if (executedSteps >= MAX_TOTAL_STEPS) {
                Log.w(TAG, "Step budget exhausted ($MAX_TOTAL_STEPS). Halting loop.")
                isAllSuccessful = false
                break
            }

            val step = currentPlan.steps[currentStepIdx]

            // ---------------------------------------------------------- OBSERVE (pre)
            val (shouldRun, skipReason) = observationEngine.shouldExecuteStep(step)
            if (!shouldRun) {
                stepObservations.add(StepObservation.StepSkipped(step, skipReason ?: "Условие не выполнено"))
                currentStepIdx++
                continue
            }

            // ---------------------------------------------------------- EXECUTE
            val result = toolExecutor.execute(step.toolCall)
            executedSteps++

            // ---------------------------------------------------------- OBSERVE (post)
            val observation = observationEngine.observe(step, result)
            stepObservations.add(
                if (observation.success) {
                    StepObservation.StepCompleted(step, result)
                } else {
                    StepObservation.StepFailed(step, observation.error ?: observation.summary)
                }
            )

            // Подтверждение прерывает цикл: решение за пользователем.
            if (observation.nextActionHint == NextActionHint.AWAIT_CONFIRMATION) {
                return PlanExecutionSummary(
                    plan = currentPlan,
                    observations = stepObservations,
                    finalVoiceSummary = observation.summary,
                    isAllSuccessful = false,
                    pendingConfirmation = step.toolCall to observation.summary
                )
            }

            if (observation.success) {
                // ------------------------------------------------------ VERIFY
                // Цель шага считается достигнутой только после проверки:
                // читаем экран и убеждаемся, что ожидаемое состояние реально
                // наступило (например, поле поиска открылось). Без этого
                // «Открыл YouTube» считалось бы успехом, даже если экран
                // не загрузился или открылось другое приложение.
                val expected = step.verifyScreenContains
                val verified = if (expected != null) {
                    verifyOnScreen(expected, stepObservations, step)
                } else {
                    true
                }

                if (verified) {
                    if (com.jarvis.assistant.agent.tools.accessibility.ScreenContentPrivacy.isScreenReaderCall(step.toolCall.toolId)) {
                        screenContentSeen = true
                    }
                    summaries.add(observation.summary)
                    currentStepIdx++
                    continue
                }

                // VERIFY провален: инструмент отработал, но цели на экране нет.
                isAllSuccessful = false
                val verifyObservation = Observation(
                    toolId = step.toolCall.toolId,
                    success = false,
                    stateChanged = false,
                    summary = "Проверил экран: \"$expected\" не найден. Цель шага не подтверждена.",
                    error = "VERIFY_FAILED",
                    nextActionHint = NextActionHint.REPLAN
                )
                Log.w(
                    TAG,
                    "VERIFY failed | stepId=${step.stepId} | tool=${step.toolCall.toolId} | " +
                        "expectedChars=${expected.orEmpty().length}"
                )

                val replanned = tryReplan(currentPlan, step, verifyObservation, replanAttempts)
                if (replanned != null) {
                    replanAttempts++
                    Log.d(TAG, "Re-plan $replanAttempts/$MAX_REPLANS after verify failure")
                    currentPlan = replanned
                    currentStepIdx = 0
                    continue
                }

                summaries.add(verifyObservation.summary)
                if (step.isCritical) {
                    Log.e(TAG, "Critical step failed verification without viable re-plan. Halting plan execution.")
                    break
                }
                currentStepIdx++
                continue
            }

            isAllSuccessful = false
            Log.w(
                TAG,
                "Step not completed | stepId=${step.stepId} | tool=${step.toolCall.toolId} | " +
                    "hint=${observation.nextActionHint}"
            )

            when (observation.nextActionHint) {
                // Android требует пользователя / возможность недоступна:
                // перепланирование не изменит исход — сообщаем честно и идём дальше.
                NextActionHint.REQUEST_PERMISSION,
                NextActionHint.AWAIT_USER_ACTION,
                NextActionHint.ABORT_UNSUPPORTED -> {
                    blockedNotices.add(observation.summary)
                    if (step.isCritical) {
                        Log.i(TAG, "Critical step blocked by Android restrictions. Halting plan.")
                        currentStepIdx = currentPlan.steps.size
                    } else {
                        currentStepIdx++
                    }
                }

                // Обычный сбой — есть смысл попробовать альтернативу.
                else -> {
                    val replanned = tryReplan(currentPlan, step, observation, replanAttempts)
                    if (replanned != null) {
                        replanAttempts++
                        Log.d(TAG, "Re-plan $replanAttempts/$MAX_REPLANS")
                        currentPlan = replanned
                        currentStepIdx = 0
                        continue
                    }

                    summaries.add("Не удалось: ${observation.summary}")
                    if (step.isCritical) {
                        Log.e(TAG, "Critical step failed without viable re-plan. Halting plan execution.")
                        break
                    }
                    currentStepIdx++
                }
            }
        }

        // Явный `return` (а не последняя-выражение): у функции с block body
        // компилятор в этом контексте требовал return-выражение.
        return PlanExecutionSummary(
            plan = currentPlan,
            observations = stepObservations,
            finalVoiceSummary = buildVoiceSummary(summaries, blockedNotices, isAllSuccessful),
            isAllSuccessful = isAllSuccessful,
            pendingConfirmation = null,
            containsScreenContent = screenContentSeen
        )
    }

    /**
     * @return новый план или null, если лимит перепланирований исчерпан
     *         либо планировщик не нашёл альтернативы.
     */
    private fun tryReplan(
        currentPlan: ExecutionPlan,
        failedStep: PlanStep,
        observation: Observation,
        attemptsSoFar: Int
    ): ExecutionPlan? {
        if (attemptsSoFar >= MAX_REPLANS) {
            Log.d(TAG, "Re-plan limit reached ($MAX_REPLANS), no further replanning")
            return null
        }
        if (!observation.isReplanWorthwhile) return null

        val newPlan = cognitivePlanner.replan(
            currentPlan = currentPlan,
            failedStep = failedStep,
            observation = StepObservation.StepFailed(failedStep, observation.error ?: observation.summary),
            attemptNumber = attemptsSoFar + 1
        )
        return newPlan?.takeIf { it.steps.isNotEmpty() }
    }

    /**
     * VERIFY: выполняет чтение экрана и проверяет наличие ожидаемого текста.
     *
     * @return true, если ожидаемый текст найден на экране (цель подтверждена).
     */
    private suspend fun verifyOnScreen(
        expectedText: String,
        stepObservations: MutableList<StepObservation>,
        step: PlanStep
    ): Boolean {
        val readResult = toolExecutor.execute(
            ToolCall(toolId = "accessibility.screen_reader", arguments = kotlinx.serialization.json.JsonObject(emptyMap()))
        )
        val screenContent = readResult.summary

        return if (readResult.isSuccess && screenContent.contains(expectedText, ignoreCase = true)) {
            Log.d(TAG, "VERIFY OK | expectedChars=${expectedText.length}")
            true
        } else {
            Log.w(
                TAG,
                "VERIFY failed | expectedChars=${expectedText.length} | screenChars=${screenContent.length}"
            )
            stepObservations.add(StepObservation.StepFailed(step, "VERIFY_FAILED"))
            false
        }
    }

    private fun buildVoiceSummary(
        summaries: List<String>,
        blockedNotices: List<String>,
        isAllSuccessful: Boolean
    ): String {
        val done = summaries.joinToString(". ").trim()
        val blocked = blockedNotices.joinToString(" ").trim()

        return when {
            isAllSuccessful && done.isNotEmpty() -> "$done, сэр."
            isAllSuccessful -> "Готово, сэр."
            done.isNotEmpty() && blocked.isNotEmpty() -> "$done. $blocked"
            blocked.isNotEmpty() -> blocked
            done.isNotEmpty() -> "План выполнен частично: $done."
            else -> "Не удалось выполнить план, сэр."
        }
    }
}
