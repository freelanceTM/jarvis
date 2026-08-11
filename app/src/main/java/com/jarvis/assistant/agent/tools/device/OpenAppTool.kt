package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.open_app"
    override val description: String = "Открывает любое установленное приложение на телефоне (Telegram, YouTube, WhatsApp, Камера, Chrome, Музыка, Калькулятор, Настройки)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Название: telegram, youtube, whatsapp, camera, chrome, spotify, settings, calculator, gallery, maps")
            }
        }
        put("required", buildJsonArray { add("app_name") })
    }

    private val packageMap = mapOf(
        "telegram" to "org.telegram.messenger",
        "телеграм" to "org.telegram.messenger",
        "телегу" to "org.telegram.messenger",
        "тг" to "org.telegram.messenger",
        "tg" to "org.telegram.messenger",
        "youtube" to "com.google.android.youtube",
        "ютуб" to "com.google.android.youtube",
        "ют" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "ватсап" to "com.whatsapp",
        "вацап" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "хром" to "com.android.chrome",
        "браузер" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "спотифай" to "com.spotify.music",
        "музыка" to "com.spotify.music",
        "camera" to "android.media.action.IMAGE_CAPTURE",
        "камера" to "android.media.action.IMAGE_CAPTURE",
        "settings" to "android.settings.SETTINGS",
        "настройки" to "android.settings.SETTINGS",
        "calculator" to "com.google.android.calculator",
        "калькулятор" to "com.google.android.calculator",
        "maps" to "com.google.android.apps.maps",
        "карты" to "com.google.android.apps.maps"
    )

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val rawName = arguments["app_name"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim().orEmpty()
        if (rawName.isEmpty()) {
            return ToolExecutionResult.failure("Не указано название приложения", "MISSING_PARAM")
        }

        val target = packageMap[rawName] ?: rawName

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(target)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ToolExecutionResult.success(
                    summary = "Приложение $rawName успешно открыто",
                    data = buildJsonObject { put("package", target) },
                    actionRequiresUser = false
                )
            }

            if (target.startsWith("android.")) {
                val actionIntent = Intent(target).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(actionIntent)
                return ToolExecutionResult.success(summary = "Открываю $rawName")
            }

            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$rawName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            ToolExecutionResult.success(summary = "Приложение $rawName не установлено, открыт магазин")
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось запустить $rawName: ${e.localizedMessage}", "LAUNCH_ERROR")
        }
    }
}
