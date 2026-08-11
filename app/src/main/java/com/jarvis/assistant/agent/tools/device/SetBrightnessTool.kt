package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetBrightnessTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "set_brightness"
    override val description: String = "Открывает настройки яркости и экрана телефона"
    override val risk: ToolRisk = ToolRisk.LOW

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("level", buildJsonObject {
                put("type", "number")
                put("description", "Уровень яркости (0-100)")
            })
        })
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Открываю настройки яркости экрана")
        } catch (e: Exception) {
            ToolResult.Error("Не удалось открыть настройки экрана", "SETTINGS_ERROR")
        }
    }
}
