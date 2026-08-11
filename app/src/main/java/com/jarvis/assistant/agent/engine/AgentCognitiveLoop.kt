package com.jarvis.assistant.agent.engine

import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.observation.AgentObservationEngine
import com.jarvis.assistant.agent.planner.*
import com.jarvis.assistant.agent.safety.ToolPermissionManager
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
    /**
     * Запускает полный когнитивный цикл агента:
     * Intent ──► Plan ──► Permission Gate ──► Execute ──► Observe ──► Re-plan
     */
    suspend fun runPlan(plan: ExecutionPlan): PlanExecutionSummary = withContext(Dispatchers.IO) {
        val observations = mutableListOf<StepObservation>()
        val summaries = mutableListOf<String>()
        var isAllSuccessful = true

        for (step in plan.steps) {
            // 1. Предварительное наблюдение (Observation Pre-Check)
            val (shouldRun, skipReason) = observationEngine.shouldExecuteStep(step)
            if (!shouldRun) {
                observations.add(StepObservation.StepSkipped(step, skipReason ?: "Условие пропущено"))
                continue
            }

            // 2. Исполнение инструмента (Execute Step)
            val result = toolExecutor.execute(step.toolCall)

            // 3. Анализ результата (Observation Post-Check & State Recording)
            val observation = observationEngine.observeStepResult(step, result)
            observations.add(observation)

            if (result.isSuccess) {
                summaries.add(result.summary)
            } else {
                isAllSuccessful = false
                summaries.add("Не удалось: ${result.summary}")
                if (step.isCritical) {
                    // При падении критического шага прерываем план
                    break
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
            plan = plan,
            observations = observations,
            finalVoiceSummary = finalVoiceSummary,
            isAllSuccessful = isAllSuccessful
        )
    }
}
