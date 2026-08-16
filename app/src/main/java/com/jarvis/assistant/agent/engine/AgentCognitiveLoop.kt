package com.jarvis.assistant.agent.engine

import android.util.Log
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.observation.AgentObservationEngine
import com.jarvis.assistant.agent.observation.NextActionHint
import com.jarvis.assistant.agent.observation.Observation
import com.jarvis.assistant.agent.planner.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    }

    suspend fun runPlan(initialPlan: ExecutionPlan): PlanExecutionSummary = withContext(Dispatchers.IO) {
        val stepObservations = mutableListOf<StepObservation>()
        val summaries = mutableListOf<String>()
        val blockedNotices = mutableListOf<String>()

        var currentPlan = initialPlan
        var replanAttempts = 0
        var executedSteps = 0
        var isAllSuccessful = true
        var currentStepIdx = 0

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
                return@withContext PlanExecutionSummary(
                    plan = currentPlan,
                    observations = stepObservations,
                    finalVoiceSummary = observation.summary,
                    isAllSuccessful = false,
                    pendingConfirmation = step.toolCall to observation.summary
                )
            }

            if (observation.success) {
                summaries.add(observation.summary)
                currentStepIdx++
                continue
            }

            isAllSuccessful = false
            Log.w(TAG, "Step '${step.description}' not completed: ${observation.summary} (${observation.nextActionHint})")

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
                        Log.d(TAG, "Re-plan $replanAttempts/$MAX_REPLANS: ${replanned.explanation}")
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

        PlanExecutionSummary(
            plan = currentPlan,
            observations = stepObservations,
            finalVoiceSummary = buildVoiceSummary(summaries, blockedNotices, isAllSuccessful),
            isAllSuccessful = isAllSuccessful,
            pendingConfirmation = null
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
