package com.jarvis.assistant.agent.planner

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.parser.ToolCallParser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CognitivePlanner @Inject constructor(
    private val toolCallParser: ToolCallParser
) {
    /**
     * Создает динамический многошаговый план действий (Execution Plan) на основе намерения пользователя
     */
    fun planForGoal(userPrompt: String, llmRawOutput: String? = null): ExecutionPlan? {
        val q = userPrompt.lowercase().trim()
            .replace(Regex("^(джарвис|jarvis|жарвис)[,\\s]*"), "")
            .trim()

        // 1. Динамический сценарий: «Я ухожу» / «Выхожу из дома»
        if (q.contains("я ухожу") || q.contains("выхожу") || q.contains("вышел из дома") || q.contains("на выход")) {
            return ExecutionPlan(
                goal = "Подготовка телефона к выходу из дома",
                explanation = "Выключение фонарика, снижение громкости, проверка батареи",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.flashlight",
                            arguments = buildJsonObject { put("enabled", false) }
                        ),
                        description = "Выключить фонарик, если горит",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject {
                                put("action", "set")
                                put("percent", 20)
                            }
                        ),
                        description = "Установить громкость на 20% для улицы",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.battery",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить остаток батареи перед выходом",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // 2. Динамический сценарий: «Я пришел домой» / «Я дома»
        if (q.contains("я пришел") || q.contains("я дома") || q.contains("вернулся домой")) {
            return ExecutionPlan(
                goal = "Подготовка телефона к домашнему режиму",
                explanation = "Увеличение громкости и открытие панели Wi-Fi",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject {
                                put("action", "set")
                                put("percent", 70)
                            }
                        ),
                        description = "Увеличить громкость до 70%",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.wifi",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить Wi-Fi",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // 3. Динамический сценарий: «Подготовь ко сну» / «Режим сна»
        if (q.contains("сон") || q.contains("спать") || q.contains("спокойной ночи") || q.contains("ночной режим")) {
            return ExecutionPlan(
                goal = "Активация ночного режима",
                explanation = "Отключение света, приглушение звука и сверка времени",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.flashlight",
                            arguments = buildJsonObject { put("enabled", false) }
                        ),
                        description = "Погасить вспышку",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject {
                                put("action", "set")
                                put("percent", 10)
                            }
                        ),
                        description = "Снизить громкость до 10%",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.time",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить время",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // 4. Построение плана на основе структурированного ответа LLM (для нестандартных инструкций)
        if (!llmRawOutput.isNullOrBlank()) {
            val toolCalls = toolCallParser.parse(llmRawOutput, userPrompt)
            if (toolCalls.isNotEmpty()) {
                val steps = toolCalls.map { call ->
                    PlanStep(
                        toolCall = call,
                        description = "Выполнение ${call.toolId}",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                }
                return ExecutionPlan(
                    goal = userPrompt,
                    explanation = "План выполнения действий для задачи",
                    steps = steps
                )
            }
        }

        return null
    }

    /**
     * Динамический Re-plan: перестраивает план при сбое шага.
     * 1. Ищет альтернативный инструмент для упавшего шага.
     * 2. Либо безопасно пропускает упавший шаг и формирует план из оставшихся шагов.
     */
    fun replan(
        currentPlan: ExecutionPlan,
        failedStep: PlanStep,
        observation: StepObservation.StepFailed,
        attemptNumber: Int
    ): ExecutionPlan? {
        if (attemptNumber > 2) return null

        val remainingSteps = mutableListOf<PlanStep>()
        var replaced = false

        // 1. Проверяем возможность подбора альтернативного инструмента
        val alternativeStep = findAlternativeStep(failedStep)
        if (alternativeStep != null) {
            remainingSteps.add(alternativeStep)
            replaced = true
        }

        // 2. Добавляем оставшиеся шаги плана после упавшего
        val failedIdx = currentPlan.steps.indexOfFirst { it.stepId == failedStep.stepId }
        if (failedIdx != -1 && failedIdx + 1 < currentPlan.steps.size) {
            for (i in (failedIdx + 1) until currentPlan.steps.size) {
                remainingSteps.add(currentPlan.steps[i])
            }
        }

        if (remainingSteps.isEmpty()) return null

        return ExecutionPlan(
            planId = UUID.randomUUID().toString(),
            goal = currentPlan.goal,
            explanation = if (replaced) {
                "Адаптированный план с альтернативой вместо '${failedStep.description}'"
            } else {
                "Продолжение плана после пропуска шага '${failedStep.description}'"
            },
            steps = remainingSteps
        )
    }

    private fun findAlternativeStep(failedStep: PlanStep): PlanStep? {
        return when (failedStep.toolCall.toolId) {
            "communication.call" -> {
                val recipient = failedStep.toolCall.arguments["recipient"]?.jsonPrimitive?.contentOrNull ?: return null
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "communication.contacts",
                        arguments = buildJsonObject { put("query", recipient) }
                    ),
                    description = "Поиск контакта $recipient в телефонной книге",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            "device.wifi" -> {
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "system.network_status",
                        arguments = buildJsonObject { }
                    ),
                    description = "Проверка текущего статуса сети",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            else -> null
        }
    }
}
