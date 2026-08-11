package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "bluetooth_control"
    override val description: String = "Управляет состоянием Bluetooth и открывает экран подключения гарнитур"
    override val risk: ToolRisk = ToolRisk.LOW

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "true - включить Bluetooth, false - выключить")
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success(
                message = "Открываю панель управления Bluetooth",
                actionRequiresUser = true
            )
        } catch (e: Exception) {
            ToolResult.Error("Не удалось открыть настройки Bluetooth", "BLUETOOTH_ERROR")
        }
    }
}
