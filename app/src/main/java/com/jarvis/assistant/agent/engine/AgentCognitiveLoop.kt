package com.jarvis.assistant.agent.engine

import android.util.Log
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.observation.AgentObservationEngine
import com.jarvis.assistant.agent.planner.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentCognitiveLoop @Inject constructor(
    private val cognitivePlanner: CognitivePlanner,
    private val toolExecutor: ToolExecutor,
    private val observationEngine: AgentObservationEngine
) {
    companion object {
        private const val TAG = "CognitiveLoop"
        private const val MAX_REPLANS = 2
    }

    /**
     * Запускает полный когнитивный цикл агента:
     * Intent ──► Plan ──► Permission Gate ──► Execute ──► Observe ──► Re-plan (до 2 попыток)
     */
    suspend fun runPlan(initialPlan: ExecutionPlan): PlanExecutionSummary = withContext(Dispatchers.IO) {
        val observations = mutableListOf<StepObservation>()
        val summaries = mutableListOf<String>()
        var isAllSuccessful = true
        var currentPlan = initialPlan
        var replanAttempts = 0

        var currentStepIdx = 0
        while (currentStepIdx < currentPlan.steps.size) {
            val step = currentPlan.steps[currentStepIdx]

            // 1. Предварительное наблюдение (Observation Pre-Check)
            val (shouldRun, skipReason) = observationEngine.shouldExecuteStep(step)
            if (!shouldRun) {
                observations.add(StepObservation.StepSkipped(step, skipReason ?: "Условие пропущено"))
                currentStepIdx++
                continue
            }

            // 2. Исполнение инструмента (Execute Step)
            val result = toolExecutor.execute(step.toolCall)

            // 3. Если требуется голосовое подтверждение пользователя
            if (result.status == ToolExecutionStatus.REQUIRES_USER_CONFIRMATION) {
                return@withContext PlanExecutionSummary(
                    plan = currentPlan,
                    observations = observations,
                    finalVoiceSummary = "CONFIRM:${step.toolCall.toolId}:${result.summary}",
                    isAllSuccessful = false
                )
            }

            // 4. Анализ результата (Observation Post-Check & State Recording)
            val observation = observationEngine.observeStepResult(step, result)
            observations.add(observation)

            if (result.isSuccess) {
                summaries.add(result.summary)
                currentStepIdx++
            } else {
                isAllSuccessful = false
                Log.w(TAG, "Step '${step.description}' failed: ${result.summary}")

                // 5. Динамический Re-plan при сбое (если попыток < 2)
                if (replanAttempts < MAX_REPLANS) {
                    replanAttempts++
                    Log.d(TAG, "Triggering Re-planning (attempt $replanAttempts/$MAX_REPLANS) for step: '${step.description}'")

                    val failedObservation = observation as? StepObservation.StepFailed
                        ?: StepObservation.StepFailed(step, result.error ?: result.summary)

                    val newPlan = cognitivePlanner.replan(
                        currentPlan = currentPlan,
                        failedStep = step,
                        observation = failedObservation,
                        attemptNumber = replanAttempts
                    )

                    if (newPlan != null && newPlan.steps.isNotEmpty()) {
                        Log.d(TAG, "Re-plan successful: ${newPlan.steps.size} steps remaining. ${newPlan.explanation}")
                        currentPlan = newPlan
                        currentStepIdx = 0 // Начинаем выполнение нового адаптированного плана
                        continue
                    }
                }

                summaries.add("Не удалось: ${result.summary}")
                if (step.isCritical) {
                    // При падении критического шага без возможности перепланирования прерываем выполнение
                    Log.e(TAG, "Critical step failed without viable re-plan. Halting plan execution.")
                    break
                } else {
                    currentStepIdx++
                }
            }
        }

        val combinedSummary = summaries.joinToString(". ")
        val finalVoiceSummary = if (isAllSuccessful) {
            "$combinedSummary, сэр."
        } else {
            "План выполнен частично: $combinedSummary."
        }

        PlanExecutionSummary(
            plan = currentPlan,
            observations = observations,
            finalVoiceSummary = finalVoiceSummary,
            isAllSuccessful = isAllSuccessful
        )
    }
}
