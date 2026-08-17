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

/**
 * Ввод текста в сфокусированное поле (ACTION_SET_TEXT через Accessibility).
 *
 * Звено цепочки «открой приложение → найди поле поиска → введи запрос →
 * проверь результат». Как и другие accessibility-инструменты, честно
 * сообщает USER_ACTION_REQUIRED, если служба специальных возможностей
 * не включена, и FAILURE, если редактируемого поля на экране нет.
 */
@Singleton
class UiTypeTextTool @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityAwareTool {

    override val toolId: String = "accessibility.type_text"
    override val description: String = "Вводит текст в поле поиска или ввода на текущем экране (требует включения Accessibility Service)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
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
            putJsonObject("text") {
                put("type", "string")
                put("description", "Текст для ввода в поле поиска (например: 'UFC')")
            }
        }
        put("required", buildJsonArray { add("text") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolExecutionResult.failure(
                summary = "Не указан текст для ввода",
                error = "MISSING_TEXT"
            )
        }

        // КРИТИЧНО: проверяем, включён ли Accessibility Service.
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

            // Честный статус: действие НЕ выполнено, нужен пользователь в системном UI.
            return ToolExecutionResult.userActionRequired(
                summary = if (opened) {
                    "Для ввода текста необходимо включить JARVIS Accessibility Service. Открыл настройки специальных возможностей."
                } else {
                    "Для ввода текста необходимо включить JARVIS Accessibility Service в настройках."
                },
                reason = "ACCESSIBILITY_SERVICE_DISABLED",
                data = buildJsonObject { put("opened_settings", opened) }
            )
        }

        return try {
            val typed = JarvisAccessibilityService.typeText(text)
            if (typed) {
                ToolExecutionResult.success(
                    summary = "Ввёл \"$text\" в поле ввода",
                    data = buildJsonObject {
                        put("text", text)
                        put("typed", true)
                    }
                )
            } else {
                ToolExecutionResult.failure(
                    summary = "Не нашёл редактируемого поля на экране — текст \"$text\" не введён",
                    error = "NO_EDITABLE_FIELD"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Ошибка при вводе текста: ${e.localizedMessage}",
                error = "TYPE_TEXT_ERROR"
            )
        }
    }
}
