package com.jarvis.assistant.agent.planner

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import java.util.UUID

sealed interface PlanCondition {
    data object Always : PlanCondition
    data class IfBatteryBelow(val percentThreshold: Int) : PlanCondition
    data class IfVolumeAbove(val percentThreshold: Int) : PlanCondition
    data class IfResultContains(val key: String, val expectedValue: String) : PlanCondition
}

data class PlanStep(
    val stepId: String = UUID.randomUUID().toString(),
    val toolCall: ToolCall,
    val description: String = "",
    val condition: PlanCondition = PlanCondition.Always,
    val isCritical: Boolean = true
)

typealias ExecutionStep = PlanStep

data class ExecutionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val goal: String,
    val steps: List<PlanStep>,
    val explanation: String = ""
)

sealed interface StepObservation {
    data class StepCompleted(val step: PlanStep, val result: ToolExecutionResult) : StepObservation
    data class StepSkipped(val step: PlanStep, val reason: String) : StepObservation
    data class StepFailed(val step: PlanStep, val error: String) : StepObservation
}

data class PlanExecutionSummary(
    val plan: ExecutionPlan,
    val observations: List<StepObservation>,
    val finalVoiceSummary: String,
    val isAllSuccessful: Boolean
)
