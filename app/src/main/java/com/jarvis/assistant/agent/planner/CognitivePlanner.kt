package com.jarvis.assistant.agent.planner

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.parser.ToolCallParser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
                        condition = PlanCondition.Always
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
                        condition = PlanCondition.Always
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.battery",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить остаток батареи перед выходом",
                        condition = PlanCondition.Always
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
                        condition = PlanCondition.Always
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.wifi",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить Wi-Fi",
                        condition = PlanCondition.Always
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
                        condition = PlanCondition.Always
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
                        condition = PlanCondition.Always
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.time",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить время",
                        condition = PlanCondition.Always
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
                        condition = PlanCondition.Always
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
}
