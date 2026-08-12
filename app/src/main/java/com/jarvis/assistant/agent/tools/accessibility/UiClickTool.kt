package com.jarvis.assistant.agent.tools.accessibility

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiClickTool @Inject constructor() : JarvisTool {

    override val toolId: String = "accessibility.ui_click"
    override val description: String = "Нажимает на экранную кнопку или прокручивает интерфейс по голосовой команде"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "string")
                put("description", "Текст или описание кнопки для нажатия (например: Отправить, Далее, Войти, Купить)")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "Действие: click, scroll_down, scroll_up")
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        if (!JarvisAccessibilityService.isServiceRunning()) {
            return ToolExecutionResult.failure(
                summary = "Служба специальных возможностей не включена. Включите JARVIS в настройках Спец. возможностей Android.",
                error = "ACCESSIBILITY_SERVICE_DISABLED"
            )
        }

        val action = arguments["action"]?.jsonPrimitive?.contentOrNull ?: "click"
        val target = arguments["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        return when (action) {
            "scroll_down" -> {
                val ok = JarvisAccessibilityService.scrollDown()
                if (ok) ToolExecutionResult.success("Прокрутил страницу вниз, сэр.")
                else ToolExecutionResult.failure("Не удалось прокрутить экран вниз", "SCROLL_FAILED")
            }
            "scroll_up" -> {
                val ok = JarvisAccessibilityService.scrollUp()
                if (ok) ToolExecutionResult.success("Прокрутил страницу вверх, сэр.")
                else ToolExecutionResult.failure("Не удалось прокрутить экран вверх", "SCROLL_FAILED")
            }
            else -> {
                if (target.isEmpty()) {
                    return ToolExecutionResult.failure("Не указан текст кнопки для нажатия", "MISSING_TARGET")
                }
                val clicked = JarvisAccessibilityService.clickByText(target)
                if (clicked) {
                    ToolExecutionResult.success("Нажал на элемент \"$target\", сэр.")
                } else {
                    ToolExecutionResult.failure("Элемент \"$target\" не найден на экране", "ELEMENT_NOT_FOUND")
                }
            }
        }
    }
}
