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

/**
 * Cognitive Planner v2.0
 * 
 * Создаёт многошаговые планы действий на основе:
 * 1. Предопределённых сценариев (10+ паттернов)
 * 2. LLM-generated tool_calls (динамическое планирование)
 * 3. Re-planning с альтернативными инструментами
 */
@Singleton
open class CognitivePlanner @Inject constructor(
    private val toolCallParser: ToolCallParser
) {
    /**
     * Создаёт динамический многошаговый план действий на основе намерения пользователя
     */
    fun planForGoal(userPrompt: String, llmRawOutput: String? = null): ExecutionPlan? {
        val q = userPrompt.lowercase().trim()
            .replace(Regex("^(джарвис|jarvis|жарвис)[,\\s]*"), "")
            .trim()

        // =========================================================================
        // СЦЕНАРИЙ 1: «Я ухожу» / «Выхожу из дома» / «На выход»
        // =========================================================================
        if (q.contains("я ухожу") || q.contains("выхожу") || q.contains("вышел из дома") || 
            q.contains("на выход") || q.contains("ухожу на работу") || q.contains("выхожу из офиса")) {
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

        // =========================================================================
        // СЦЕНАРИЙ 2: «Я пришёл домой» / «Я дома» / «Вернулся»
        // =========================================================================
        if (q.contains("я пришел") || q.contains("я дома") || q.contains("вернулся домой") ||
            q.contains("пришёл домой") || q.contains("дома уже")) {
            return ExecutionPlan(
                goal = "Подготовка телефона к домашнему режиму",
                explanation = "Увеличение громкости и проверка Wi-Fi",
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

        // =========================================================================
        // СЦЕНАРИЙ 3: «Подготовь ко сну» / «Режим сна» / «Спокойной ночи»
        // =========================================================================
        if (q.contains("сон") || q.contains("спать") || q.contains("спокойной ночи") || 
            q.contains("ночной режим") || q.contains("ложусь") || q.contains("засыпа")) {
            return ExecutionPlan(
                goal = "Активация ночного режима",
                explanation = "Отключение света, минимальная громкость, режим 'Не беспокоить'",
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
                            toolId = "device.dnd",
                            arguments = buildJsonObject { put("enabled", true) }
                        ),
                        description = "Включить режим 'Не беспокоить'",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 4: «Доброе утро» / «Просыпаюсь» / «Утренний режим»
        // =========================================================================
        if (q.contains("доброе утро") || q.contains("просыпа") || q.contains("утренний режим") ||
            q.contains("утро") && q.contains("режим") || q.contains("проснулся")) {
            return ExecutionPlan(
                goal = "Активация утреннего режима",
                explanation = "Средняя громкость, выключение DND, проверка времени и батареи",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.dnd",
                            arguments = buildJsonObject { put("enabled", false) }
                        ),
                        description = "Выключить режим 'Не беспокоить'",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject {
                                put("action", "set")
                                put("percent", 50)
                            }
                        ),
                        description = "Установить громкость на 50%",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.time",
                            arguments = buildJsonObject { }
                        ),
                        description = "Сообщить текущее время",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.battery",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить заряд батареи",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 5: «Режим совещания» / «Я на встрече» / «Митинг»
        // =========================================================================
        if (q.contains("совещани") || q.contains("встреч") || q.contains("митинг") ||
            q.contains("на собрании") || q.contains("переговор")) {
            return ExecutionPlan(
                goal = "Активация режима совещания",
                explanation = "Тихий режим, DND, выключение всех уведомлений",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject { put("action", "mute") }
                        ),
                        description = "Полностью отключить звук",
                        condition = PlanCondition.Always,
                        isCritical = true
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.dnd",
                            arguments = buildJsonObject { put("enabled", true) }
                        ),
                        description = "Включить режим 'Не беспокоить'",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 6: «Подготовь к поездке» / «Еду на машине» / «Навигация»
        // =========================================================================
        if (q.contains("поездк") || q.contains("еду") || q.contains("за рулём") ||
            q.contains("за рулем") || q.contains("в машин") || q.contains("автомобил")) {
            return ExecutionPlan(
                goal = "Режим вождения",
                explanation = "Максимальная громкость, Bluetooth, отключение уведомлений",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject {
                                put("action", "set")
                                put("percent", 100)
                            }
                        ),
                        description = "Максимальная громкость для навигации",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.bluetooth",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить Bluetooth для подключения к машине",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 7: «Режим экономии» / «Батарея садится» / «Экономь заряд»
        // =========================================================================
        if (q.contains("эконом") || q.contains("батарея садится") || q.contains("мало заряда") ||
            q.contains("экономь") || q.contains("сохрани заряд")) {
            return ExecutionPlan(
                goal = "Режим экономии батареи",
                explanation = "Минимальная яркость, выключение Wi-Fi/Bluetooth, тихий режим",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.brightness",
                            arguments = buildJsonObject { put("percent", 10) }
                        ),
                        description = "Минимальная яркость экрана",
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
                        description = "Снизить громкость",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.battery",
                            arguments = buildJsonObject { }
                        ),
                        description = "Показать текущий заряд",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 8: «Статус системы» / «Что с телефоном» / «Диагностика»
        // =========================================================================
        if (q.contains("статус") || q.contains("диагностик") || q.contains("что с телефоном") ||
            q.contains("состояние") || q.contains("проверь всё") || q.contains("отчёт")) {
            return ExecutionPlan(
                goal = "Диагностика системы",
                explanation = "Проверка батареи, времени, сети и устройства",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.battery",
                            arguments = buildJsonObject { }
                        ),
                        description = "Проверить батарею",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.time",
                            arguments = buildJsonObject { }
                        ),
                        description = "Текущее время",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.network_status",
                            arguments = buildJsonObject { }
                        ),
                        description = "Статус сети",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "system.device_info",
                            arguments = buildJsonObject { }
                        ),
                        description = "Информация об устройстве",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 9: «Подготовь презентацию» / «Демо режим»
        // =========================================================================
        if (q.contains("презентац") || q.contains("демо") || q.contains("показ") && q.contains("режим")) {
            return ExecutionPlan(
                goal = "Режим презентации",
                explanation = "DND, тихий режим, максимальная яркость",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.dnd",
                            arguments = buildJsonObject { put("enabled", true) }
                        ),
                        description = "Включить DND (без отвлечений)",
                        condition = PlanCondition.Always,
                        isCritical = true
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject { put("action", "mute") }
                        ),
                        description = "Выключить звук",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.brightness",
                            arguments = buildJsonObject { put("percent", 100) }
                        ),
                        description = "Максимальная яркость для видимости",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // СЦЕНАРИЙ 10: «Отмени всё» / «Верни как было» / «Обычный режим»
        // =========================================================================
        if (q.contains("отмени") || q.contains("верни как было") || q.contains("обычный режим") ||
            q.contains("стандартный режим") || q.contains("сброс")) {
            return ExecutionPlan(
                goal = "Возврат к обычным настройкам",
                explanation = "Средняя громкость, выключение DND, средняя яркость",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.dnd",
                            arguments = buildJsonObject { put("enabled", false) }
                        ),
                        description = "Выключить DND",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.volume",
                            arguments = buildJsonObject {
                                put("action", "set")
                                put("percent", 50)
                            }
                        ),
                        description = "Средняя громкость",
                        condition = PlanCondition.Always,
                        isCritical = false
                    ),
                    PlanStep(
                        toolCall = ToolCall(
                            toolId = "device.brightness",
                            arguments = buildJsonObject { put("percent", 50) }
                        ),
                        description = "Средняя яркость",
                        condition = PlanCondition.Always,
                        isCritical = false
                    )
                )
            )
        }

        // =========================================================================
        // LLM-BASED PLANNING: Парсинг tool_calls из ответа AI
        // =========================================================================
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
     * 
     * Стратегии:
     * 1. Поиск альтернативного инструмента для упавшего шага
     * 2. Пропуск некритичного шага и продолжение
     */
    open fun replan(
        currentPlan: ExecutionPlan,
        failedStep: PlanStep,
        observation: StepObservation.StepFailed,
        attemptNumber: Int
    ): ExecutionPlan? {
        if (attemptNumber > 2) return null

        val remainingSteps = mutableListOf<PlanStep>()
        var replaced = false

        // 1. Поиск альтернативы для упавшего шага
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

    /**
     * Расширенный поиск альтернативных инструментов
     */
    private fun findAlternativeStep(failedStep: PlanStep): PlanStep? {
        val toolId = failedStep.toolCall.toolId
        val args = failedStep.toolCall.arguments

        return when (toolId) {
            // Коммуникации
            "communication.call" -> {
                val recipient = args["recipient"]?.jsonPrimitive?.contentOrNull ?: return null
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "communication.contacts",
                        arguments = buildJsonObject { put("query", recipient) }
                    ),
                    description = "Поиск контакта '$recipient' в телефонной книге",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            "communication.sms" -> {
                val recipient = args["recipient"]?.jsonPrimitive?.contentOrNull ?: return null
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "communication.share",
                        arguments = buildJsonObject {
                            put("text", args["message"]?.jsonPrimitive?.contentOrNull ?: "")
                            put("target", "sms")
                        }
                    ),
                    description = "Отправка через Share для '$recipient'",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            
            // Сеть
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
            "device.bluetooth" -> {
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "system.device_info",
                        arguments = buildJsonObject { }
                    ),
                    description = "Информация об устройстве",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            
            // Accessibility
            "accessibility.ui_click" -> {
                val target = args["target_text"]?.jsonPrimitive?.contentOrNull ?: return null
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "accessibility.screen_reader",
                        arguments = buildJsonObject { }
                    ),
                    description = "Чтение экрана для поиска '$target'",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            
            // Поиск
            "intelligence.web_search" -> {
                // Если веб-поиск не сработал — пробуем вспомнить из памяти
                val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return null
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "memory.recall",
                        arguments = buildJsonObject { put("query", query) }
                    ),
                    description = "Поиск в памяти: '$query'",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            
            // DND
            "device.dnd" -> {
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "device.volume",
                        arguments = buildJsonObject { put("action", "mute") }
                    ),
                    description = "Альтернатива DND: полное отключение звука",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }
            
            // Яркость
            "device.brightness" -> {
                // Яркость может не работать без системных разрешений
                null
            }
            
            // Скриншот
            "device.screenshot" -> {
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "accessibility.screen_reader",
                        arguments = buildJsonObject { }
                    ),
                    description = "Альтернатива скриншоту: чтение содержимого экрана",
                    condition = PlanCondition.Always,
                    isCritical = false
                )
            }

            else -> null
        }
    }
}
