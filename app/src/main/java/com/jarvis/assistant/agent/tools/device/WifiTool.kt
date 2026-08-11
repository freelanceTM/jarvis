package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "wifi_control"
    override val description: String = "Управляет Wi-Fi сетью и открывает системную панель интернета"
    override val risk: ToolRisk = ToolRisk.LOW

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "true - включить Wi-Fi, false - выключить")
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            ToolResult.Success("Открываю панель управления Wi-Fi")
        } catch (e: Exception) {
            ToolResult.Error("Не удалось открыть настройки Wi-Fi", "WIFI_ERROR")
        }
    }
}
