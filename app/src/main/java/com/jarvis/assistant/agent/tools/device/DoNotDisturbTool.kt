package com.jarvis.assistant.agent.tools.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
class DoNotDisturbTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.dnd"
    override val description: String = "Управляет режимом 'Не беспокоить' (DND / режим тишины)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "true - включить режим 'Не беспокоить', false - выключить")
            }
        }
        put("required", buildJsonArray { add("enabled") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolExecutionResult.failure("Служба уведомлений недоступна", "NO_NOTIFICATION_SERVICE")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                val targetFilter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(targetFilter)
                ToolExecutionResult.success(if (enabled) "Режим 'Не беспокоить' включён" else "Режим 'Не беспокоить' выключен")
            } else {
                // Если нет прямого доступа к политике, открываем системные настройки
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult.success("Открываю настройки режима 'Не беспокоить'", actionRequiresUser = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось переключить DND: ${e.localizedMessage}", "DND_ERROR")
        }
    }
}
