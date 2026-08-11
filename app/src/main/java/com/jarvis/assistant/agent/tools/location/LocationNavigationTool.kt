package com.jarvis.assistant.agent.tools.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationNavigationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "location.navigation"
    override val description: String = "Запускает навигатор по адресу или координатам в Google Maps или Яндекс Картах"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = false
    override val requiresForeground: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("destination") {
                put("type", "string")
                put("description", "Адрес или место назначения (например: Красная Площадь, Аэропорт, Улица Пушкина 10)")
            }
        }
        put("required", buildJsonArray { add("destination") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val dest = arguments["destination"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (dest.isEmpty()) {
            return ToolExecutionResult.failure("Укажите место назначения", "MISSING_DESTINATION")
        }

        return try {
            val encoded = URLEncoder.encode(dest, "UTF-8")
            val navUri = Uri.parse("google.navigation:q=$encoded")
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Проверяем наличие карт или открываем браузер
            val finalIntent = if (mapIntent.resolveActivity(context.packageManager) != null) {
                mapIntent
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(finalIntent)
            ToolExecutionResult.success("Маршрут построен: $dest", actionRequiresUser = true)
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось открыть навигатор: ${e.localizedMessage}", "NAVIGATION_ERROR")
        }
    }
}
