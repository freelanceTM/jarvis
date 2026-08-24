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
        // СЦЕНАРИЙ 0: «Открой <приложение> и найди/поищи <запрос>»
        //
        // Полноценная UI-цепочка вместо «открыл приложение = задача решена»:
        //   OpenApp(app) → click(поле поиска) → type(query) → verify(query на экране)
        // Последний шаг несёт verifyScreenContains — цикл OBSERVE→VERIFY подтвердит,
        // что результат реально появился, иначе REPLAN.
        // =========================================================================
        val openSearchMatch = Regex(
            """(?:открой|запусти|включи|откр)\s+(.+?)\s+(?:и\s+)?(?:найди|поищи|ищи|найти|поиск)\s+(.+)""",
            RegexOption.IGNORE_CASE
        ).find(q)
        if (openSearchMatch != null) {
            val appRaw = openSearchMatch.groupValues[1].trim()
            val query = openSearchMatch.groupValues[2].trim()

            val app = when {
                appRaw.contains("телеграм") || appRaw.contains("telegram") || appRaw.contains("тг") || appRaw.contains("tg") -> "telegram"
                appRaw.contains("ютуб") || appRaw.contains("youtube") || appRaw.contains("ют") -> "youtube"
                appRaw.contains("ватсап") || appRaw.contains("whatsapp") || appRaw.contains("вацап") -> "whatsapp"
                appRaw.contains("хром") || appRaw.contains("chrome") || appRaw.contains("браузер") -> "chrome"
                appRaw.contains("спотифай") || appRaw.contains("spotify") -> "spotify"
                else -> null
            }

            if (app != null && query.isNotEmpty()) {
                return ExecutionPlan(
                    goal = "Открыть $app и найти «$query»",
                    explanation = "UI-цепочка: открыть приложение, найти поле поиска, ввести запрос, проверить результат на экране",
                    steps = listOf(
                        PlanStep(
                            toolCall = ToolCall(
                                toolId = "device.open_app",
                                arguments = buildJsonObject { put("app_name", app) }
                            ),
                            description = "Открыть $app",
                            condition = PlanCondition.Always,
                            isCritical = true
                        ),
                        PlanStep(
                            toolCall = ToolCall(
                                toolId = "accessibility.ui_click",
                                arguments = buildJsonObject { put("target_text", "поиск") }
                            ),
                            description = "Найти и нажать поле поиска в $app",
                            condition = PlanCondition.Always,
                            isCritical = false
                        ),
                        PlanStep(
                            toolCall = ToolCall(
                                toolId = "accessibility.type_text",
                                arguments = buildJsonObject { put("text", query) }
                            ),
                            description = "Ввести запрос «$query» в поле поиска",
                            condition = PlanCondition.Always,
                            isCritical = false
                        ),
                        PlanStep(
                            toolCall = ToolCall(
                                toolId = "accessibility.screen_reader",
                                arguments = buildJsonObject { }
                            ),
                            description = "Проверить, что результаты поиска по «$query» появились на экране",
                            condition = PlanCondition.Always,
                            isCritical = true,
                            verifyScreenContains = query
                        )
                    )
                )
            }
        }

        // =========================================================================
        // Сценарии 1-10 через хеш-таблицу ScenarioMatcher (пункт аудита #6):
        // ОДИН проход по словарю ключевых слов вместо 15+ последовательных
        // contains(). Приоритет (порядок исходных if) сохранён внутри match().
        // =========================================================================
        val scenario = ScenarioMatcher.match(q)

        // =========================================================================
        // Сценарии 1-10: построение плана делегируется отдельным билдерам
        // (пункт аудита #16). Матчинг — ScenarioMatcher (пункт #6), здесь —
        // только диспетчеризация. Поведение идентично прежним if-блокам.
        // =========================================================================
        if (scenario != null) {
            ScenarioPlanBuilders.builderFor(scenario)?.let { builder ->
                return builder.build(q)
            }
        }

        // =========================================================================
        // LLM-BASED PLANNING: Парсинг tool_calls из ответа AI
        // =========================================================================
        if (!llmRawOutput.isNullOrBlank()) {
            val toolCalls = toolCallParser.parse(llmRawOutput)
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
            "accessibility.type_text" -> {
                // Ввод не удался (нет редактируемого поля) — читаем экран,
                // чтобы понять, что на нём реально есть.
                PlanStep(
                    toolCall = ToolCall(
                        toolId = "accessibility.screen_reader",
                        arguments = buildJsonObject { }
                    ),
                    description = "Чтение экрана: проверка доступных полей ввода",
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
