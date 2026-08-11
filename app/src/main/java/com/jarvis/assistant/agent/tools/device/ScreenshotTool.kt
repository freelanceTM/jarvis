package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.screenshot"
    override val description: String = "Делает снимок экрана (скриншот) телефона"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        if (JarvisAccessibilityService.isServiceRunning()) {
            val taken = JarvisAccessibilityService.takeSystemScreenshot()
            return if (taken) {
                ToolExecutionResult.success("Скриншот сделан")
            } else {
                ToolExecutionResult.failure("Не удалось сделать скриншот", "ACCESSIBILITY_FAILED")
            }
        }

        // Если служба специальных возможностей не включена, открываем настройки
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolExecutionResult.success(
            summary = "Для создания скриншотов включите службу JARVIS в спец. возможностях",
            actionRequiresUser = true
        )
    }
}
