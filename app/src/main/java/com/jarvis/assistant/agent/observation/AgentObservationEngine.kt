package com.jarvis.assistant.agent.observation

import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.planner.PlanCondition
import com.jarvis.assistant.agent.planner.PlanStep
import com.jarvis.assistant.agent.planner.StepObservation
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentObservationEngine @Inject constructor(
    private val workingMemory: WorkingMemory
) {
    /**
     * Анализирует предусловие шага плана перед его выполнением (Pre-Condition Check)
     */
    fun shouldExecuteStep(step: PlanStep): Pair<Boolean, String?> {
        return when (val cond = step.condition) {
            is PlanCondition.Always -> true to null
            is PlanCondition.IfBatteryBelow -> {
                val lastBattery = (workingMemory.get("battery_percent") as? Int) ?: 100
                if (lastBattery < cond.percentThreshold) {
                    true to null
                } else {
                    false to "Батарея $lastBattery% (выше порога ${cond.percentThreshold}%)"
                }
            }
            is PlanCondition.IfVolumeAbove -> {
                val lastVol = (workingMemory.get("volume_percent") as? Int) ?: 50
                if (lastVol > cond.percentThreshold) {
                    true to null
                } else {
                    false to "Громкость $lastVol% (ниже порога)"
                }
            }
            is PlanCondition.IfResultContains -> true to null
        }
    }

    /**
     * Наблюдение за результатом шага (Post-Execution Observation & State Update)
     */
    fun observeStepResult(step: PlanStep, result: ToolExecutionResult): StepObservation {
        if (result.isSuccess) {
            // Обновляем контекст рабочей памяти на основе фактических данных инструмента
            result.data?.let { data ->
                data["percent"]?.jsonPrimitive?.content?.toIntOrNull()?.let {
                    workingMemory.put("battery_percent", it)
                }
                data["time"]?.jsonPrimitive?.content?.let {
                    workingMemory.put("current_time", it)
                }
                data["package"]?.jsonPrimitive?.content?.let {
                    workingMemory.setLastApp(it)
                }
            }
            workingMemory.setLastAction(step.toolCall.toolId)
            return StepObservation.StepCompleted(step, result)
        } else {
            return StepObservation.StepFailed(step, result.error ?: result.summary)
        }
    }
}
