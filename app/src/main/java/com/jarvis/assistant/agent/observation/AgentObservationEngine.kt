package com.jarvis.assistant.agent.observation

import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.planner.PlanCondition
import com.jarvis.assistant.agent.planner.PlanStep
import com.jarvis.assistant.agent.planner.StepObservation
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Движок наблюдений агента.
 *
 * Отвечает за две вещи:
 *  1. Pre-condition check — стоит ли вообще выполнять шаг;
 *  2. Post-execution observation — что произошло по факту и что делать дальше.
 *
 * Результат каждого шага сворачивается в структурированное [Observation]
 * (success / stateChanged / data / error / nextActionHint), на основе которого
 * agent loop решает: продолжать, перепланировать или остановиться.
 */
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
                val lastBattery = workingMemory.get("battery_percent") as? Int
                when {
                    lastBattery == null -> true to null // Данных нет — не блокируем шаг
                    lastBattery < cond.percentThreshold -> true to null
                    else -> false to "Батарея $lastBattery% (выше порога ${cond.percentThreshold}%)"
                }
            }

            is PlanCondition.IfVolumeAbove -> {
                val lastVol = workingMemory.get("volume_percent") as? Int
                when {
                    lastVol == null -> true to null
                    lastVol > cond.percentThreshold -> true to null
                    else -> false to "Громкость $lastVol% (ниже порога ${cond.percentThreshold}%)"
                }
            }

            is PlanCondition.IfResultContains -> {
                val stored = workingMemory.get(cond.key)?.toString()
                if (stored == null || stored.contains(cond.expectedValue, ignoreCase = true)) {
                    true to null
                } else {
                    false to "Условие не выполнено: ${cond.key} = $stored, ожидалось ${cond.expectedValue}"
                }
            }
        }
    }

    /**
     * Наблюдение за результатом шага (Post-Execution Observation & State Update).
     *
     * Возвращает структурированное [Observation] и одновременно обновляет
     * рабочую память фактическими данными инструмента.
     */
    fun observe(step: PlanStep, result: ToolExecutionResult): Observation {
        val observation = Observation.from(step.toolCall.toolId, result)
        recordState(step, result, observation)
        return observation
    }

    /**
     * Совместимый со схемой плана вид наблюдения.
     */
    fun observeStepResult(step: PlanStep, result: ToolExecutionResult): StepObservation {
        val observation = observe(step, result)
        return if (observation.success) {
            StepObservation.StepCompleted(step, result)
        } else {
            StepObservation.StepFailed(step, observation.error ?: observation.summary)
        }
    }

    /**
     * Фиксирует наблюдаемое состояние устройства в рабочей памяти, чтобы
     * последующие шаги и re-plan опирались на факты, а не на предположения.
     */
    private fun recordState(step: PlanStep, result: ToolExecutionResult, observation: Observation) {
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
            data["label"]?.jsonPrimitive?.content?.let {
                workingMemory.setLastApp(it)
            }
            data["recipient"]?.jsonPrimitive?.content?.let {
                workingMemory.setLastContact(it)
            }
            data["location"]?.jsonPrimitive?.content?.let {
                workingMemory.setLastLocation(it)
            }
            // Наблюдаемое состояние радиомодулей — важно для re-plan сценария
            // «выхожу из дома»: агент должен знать, что Bluetooth реально выключен.
            data["enabled"]?.jsonPrimitive?.booleanOrNull?.let {
                workingMemory.put("${step.toolCall.toolId}.enabled", it)
            }
            data["wifi_enabled"]?.jsonPrimitive?.booleanOrNull?.let {
                workingMemory.put("wifi_enabled", it)
            }
        }

        // Последним действием считаем только то, что реально изменило состояние.
        if (observation.stateChanged) {
            workingMemory.setLastAction(step.toolCall.toolId)
        }
    }
}
