package com.jarvis.assistant.agent.tools.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
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
) : JarvisTool {

    override val toolId: String = "accessibility.ui_click"
    override val description: String = "Нажимает на элемент интерфейса по его тексту или описанию (требует включения Accessibility Service)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true
    override val executionTimeoutMs: Long = 5000L

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target_text") {
                put("type", "string")
                put("description", "Текст кнопки или элемента для нажатия (например: 'Отправить', 'OK', 'Далее')")
            }
        }
        put("required", buildJsonArray { add("target_text") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val targetText = arguments["target_text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        
        if (targetText.isEmpty()) {
            return ToolExecutionResult.failure(
                "Не указан текст элемента для нажатия",
                "MISSING_TARGET_TEXT"
            )
        }

        // КРИТИЧНО: Проверяем, включён ли Accessibility Service
        if (!JarvisAccessibilityService.isServiceRunning()) {
            // Открываем настройки Accessibility для пользователя
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { }

            return ToolExecutionResult.failure(
                summary = "Для управления интерфейсом необходимо включить JARVIS Accessibility Service в настройках. Открываю настройки специальных возможностей.",
                error = "ACCESSIBILITY_SERVICE_DISABLED"
            )
        }

        return try {
            val clicked = JarvisAccessibilityService.clickByText(targetText)
            
            if (clicked) {
                ToolExecutionResult.success(
                    summary = "Нажал на '$targetText'",
                    data = buildJsonObject {
                        put("target", targetText)
                        put("clicked", true)
                    }
                )
            } else {
                ToolExecutionResult.failure(
                    summary = "Элемент '$targetText' не найден на экране. Возможно, он скрыт или ещё не загружен.",
                    error = "ELEMENT_NOT_FOUND"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Ошибка при попытке нажать на '$targetText': ${e.localizedMessage}",
                error = "CLICK_ERROR"
            )
        }
    }
}
