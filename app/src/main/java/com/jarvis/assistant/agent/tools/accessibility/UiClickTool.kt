package com.jarvis.assistant.agent.tools.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiClickTool @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityAwareTool {

    override val toolId: String = "accessibility.ui_click"
    override val description: String = "Нажимает на элемент интерфейса по его тексту или описанию (требует включения Accessibility Service)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true
    override val executionTimeoutMs: Long = 5000L

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.USE_ACCESSIBILITY_SERVICE),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: JarvisCapability = JarvisCapability.Accessibility

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "click, scroll_down или scroll_up")
            }
            putJsonObject("target_text") {
                put("type", "string")
                put("description", "Текст кнопки или элемента для нажатия (например: 'Отправить', 'OK', 'Далее')")
            }
        }
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "click"
        val targetText = arguments["target_text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (action == "click" && targetText.isEmpty()) {
            return ToolExecutionResult.failure(
                "Не указан текст элемента для нажатия",
                "MISSING_TARGET_TEXT"
            )
        }
        if (action !in setOf("click", "scroll_down", "scroll_up")) {
            return ToolExecutionResult.failure("Неизвестное UI-действие: $action", "INVALID_ACTION")
        }

        if (!JarvisAccessibilityService.isServiceRunning()) {
            val opened = try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
            return ToolExecutionResult.userActionRequired(
                summary = if (opened) {
                    "Для управления интерфейсом необходимо включить JARVIS Accessibility Service. Открыл настройки специальных возможностей."
                } else {
                    "Для управления интерфейсом необходимо включить JARVIS Accessibility Service в настройках."
                },
                reason = "ACCESSIBILITY_SERVICE_DISABLED",
                data = buildJsonObject { put("opened_settings", opened) }
            )
        }

        return try {
            val performed = when (action) {
                "click" -> JarvisAccessibilityService.clickByText(targetText)
                "scroll_down" -> JarvisAccessibilityService.scrollDown()
                "scroll_up" -> JarvisAccessibilityService.scrollUp()
                else -> false
            }
            if (performed) {
                ToolExecutionResult.success(
                    summary = when (action) {
                        "click" -> "Нажал на '$targetText'"
                        "scroll_down" -> "Прокрутил экран вниз"
                        else -> "Прокрутил экран вверх"
                    },
                    data = buildJsonObject {
                        put("action", action)
                        if (targetText.isNotEmpty()) put("target", targetText)
                        put("performed", true)
                    }
                )
            } else {
                ToolExecutionResult.failure(
                    summary = if (action == "click") {
                        "Элемент '$targetText' не найден на экране."
                    } else {
                        "На экране нет доступной области для прокрутки."
                    },
                    error = if (action == "click") "ELEMENT_NOT_FOUND" else "SCROLL_TARGET_NOT_FOUND"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Ошибка UI-действия: ${e.localizedMessage}",
                error = "UI_ACTION_ERROR"
            )
        }
    }
}
