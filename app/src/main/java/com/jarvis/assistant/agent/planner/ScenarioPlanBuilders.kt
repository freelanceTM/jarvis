package com.jarvis.assistant.agent.planner

import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Билдер плана сценария (пункт аудита #16 — LOW).
 *
 * Разбивает god-функцию planForGoal: каждый сценарий строит свой ExecutionPlan
 * в отдельном классе. Матчинг сценария — в [ScenarioMatcher] (пункт #6),
 * здесь — ТОЛЬКО построение плана.
 */
interface ScenarioPlanBuilder {
    val scenarioId: ScenarioId

    /** @param query нормализованный запрос пользователя (может не использоваться). */
    fun build(query: String): ExecutionPlan
}

/** СЦЕНАРИЙ 1: «Я ухожу» / «Выхожу из дома» / «На выход». */
object LeavingHomePlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.LEAVING_HOME
    override fun build(query: String) = ExecutionPlan(
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
                toolCall = ToolCall(toolId = "system.battery", arguments = buildJsonObject { }),
                description = "Проверить остаток батареи перед выходом",
                condition = PlanCondition.Always,
                isCritical = false
            )
        )
    )
}

/** СЦЕНАРИЙ 2: «Я пришёл домой» / «Я дома» / «Вернулся». */
object ComingHomePlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.COMING_HOME
    override fun build(query: String) = ExecutionPlan(
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
                toolCall = ToolCall(toolId = "device.wifi", arguments = buildJsonObject { }),
                description = "Проверить Wi-Fi",
                condition = PlanCondition.Always,
                isCritical = false
            )
        )
    )
}

/** СЦЕНАРИЙ 3: «Подготовь ко сну» / «Режим сна» / «Спокойной ночи». */
object SleepPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.SLEEP
    override fun build(query: String) = ExecutionPlan(
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

/** СЦЕНАРИЙ 4: «Доброе утро» / «Просыпаюсь» / «Утренний режим». */
object MorningPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.MORNING
    override fun build(query: String) = ExecutionPlan(
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
                toolCall = ToolCall(toolId = "system.time", arguments = buildJsonObject { }),
                description = "Сообщить текущее время",
                condition = PlanCondition.Always,
                isCritical = false
            ),
            PlanStep(
                toolCall = ToolCall(toolId = "system.battery", arguments = buildJsonObject { }),
                description = "Проверить заряд батареи",
                condition = PlanCondition.Always,
                isCritical = false
            )
        )
    )
}

/** СЦЕНАРИЙ 5: «Режим совещания» / «Я на встрече» / «Митинг». */
object MeetingPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.MEETING
    override fun build(query: String) = ExecutionPlan(
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

/** СЦЕНАРИЙ 6: «Подготовь к поездке» / «Еду на машине» / «Навигация». */
object DrivingPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.DRIVING
    override fun build(query: String) = ExecutionPlan(
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
                toolCall = ToolCall(toolId = "device.bluetooth", arguments = buildJsonObject { }),
                description = "Проверить Bluetooth для подключения к машине",
                condition = PlanCondition.Always,
                isCritical = false
            )
        )
    )
}

/** СЦЕНАРИЙ 7: «Режим экономии» / «Батарея садится» / «Экономь заряд». */
object PowerSavingPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.POWER_SAVING
    override fun build(query: String) = ExecutionPlan(
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
                toolCall = ToolCall(toolId = "system.battery", arguments = buildJsonObject { }),
                description = "Показать текущий заряд",
                condition = PlanCondition.Always,
                isCritical = false
            )
        )
    )
}

/** СЦЕНАРИЙ 8: «Статус системы» / «Что с телефоном» / «Диагностика». */
object DiagnosticsPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.DIAGNOSTICS
    override fun build(query: String) = ExecutionPlan(
        goal = "Диагностика системы",
        explanation = "Проверка батареи, времени, сети и устройства",
        steps = listOf(
            PlanStep(
                toolCall = ToolCall(toolId = "system.battery", arguments = buildJsonObject { }),
                description = "Проверить батарею",
                condition = PlanCondition.Always,
                isCritical = false
            ),
            PlanStep(
                toolCall = ToolCall(toolId = "system.time", arguments = buildJsonObject { }),
                description = "Текущее время",
                condition = PlanCondition.Always,
                isCritical = false
            ),
            PlanStep(
                toolCall = ToolCall(toolId = "system.network_status", arguments = buildJsonObject { }),
                description = "Статус сети",
                condition = PlanCondition.Always,
                isCritical = false
            ),
            PlanStep(
                toolCall = ToolCall(toolId = "system.device_info", arguments = buildJsonObject { }),
                description = "Информация об устройстве",
                condition = PlanCondition.Always,
                isCritical = false
            )
        )
    )
}

/** СЦЕНАРИЙ 9: «Подготовь презентацию» / «Демо режим». */
object PresentationPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.PRESENTATION
    override fun build(query: String) = ExecutionPlan(
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

/** СЦЕНАРИЙ 10: «Отмени всё» / «Верни как было» / «Обычный режим». */
object ResetPlanBuilder : ScenarioPlanBuilder {
    override val scenarioId = ScenarioId.RESET
    override fun build(query: String) = ExecutionPlan(
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

/** Реестр билдеров: сценарий → билдер. */
object ScenarioPlanBuilders {
    val all: List<ScenarioPlanBuilder> = listOf(
        LeavingHomePlanBuilder,
        ComingHomePlanBuilder,
        SleepPlanBuilder,
        MorningPlanBuilder,
        MeetingPlanBuilder,
        DrivingPlanBuilder,
        PowerSavingPlanBuilder,
        DiagnosticsPlanBuilder,
        PresentationPlanBuilder,
        ResetPlanBuilder
    )

    fun builderFor(scenarioId: ScenarioId): ScenarioPlanBuilder? =
        all.firstOrNull { it.scenarioId == scenarioId }
}
