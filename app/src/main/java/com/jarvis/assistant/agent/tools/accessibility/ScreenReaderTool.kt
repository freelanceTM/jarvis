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
class ScreenReaderTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "accessibility.screen_reader"
    override val description: String = "Считывает текстовое содержимое текущего экрана (требует включения Accessibility Service)"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true
    override val executionTimeoutMs: Long = 3000L

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
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
                summary = "Для чтения экрана необходимо включить JARVIS Accessibility Service в настройках. Открываю настройки специальных возможностей.",
                error = "ACCESSIBILITY_SERVICE_DISABLED"
            )
        }

        return try {
            val screenContent = JarvisAccessibilityService.getScreenContent()
            
            if (screenContent.isNotBlank() && screenContent != "На экране нет текстового содержимого.") {
                // Ограничиваем длину для голосового ответа
                val truncated = if (screenContent.length > 800) {
                    screenContent.take(800) + "..."
                } else {
                    screenContent
                }
                
                ToolExecutionResult.success(
                    summary = truncated,
                    data = buildJsonObject {
                        put("full_content", screenContent)
                        put("truncated", screenContent.length > 800)
                    }
                )
            } else {
                ToolExecutionResult.success(
                    summary = "На текущем экране нет текстового содержимого или экран заблокирован.",
                    data = buildJsonObject {
                        put("full_content", "")
                        put("is_empty", true)
                    }
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Ошибка при чтении экрана: ${e.localizedMessage}",
                error = "SCREEN_READ_ERROR"
            )
        }
    }
}
