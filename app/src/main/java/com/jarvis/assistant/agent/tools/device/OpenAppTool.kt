package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "open_app"
    override val description: String = "Открывает любое установленное приложение (Telegram, YouTube, WhatsApp, Камера, Chrome, Spotify, Настройки, Калькулятор)"
    override val risk: ToolRisk = ToolRisk.LOW

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

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val rawName = arguments["app_name"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim().orEmpty()
        if (rawName.isEmpty()) {
            return ToolResult.Error("Не указано название приложения", "MISSING_PARAM")
        }

        val target = packageMap[rawName] ?: rawName

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(target)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ToolResult.Success("Приложение $rawName успешно открыто")
            }

            if (target.startsWith("android.")) {
                val actionIntent = Intent(target).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(actionIntent)
                return ToolResult.Success("Открываю $rawName")
            }

            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$rawName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            ToolResult.Success("Приложение $rawName не установлено, открыт магазин приложений")
        } catch (e: Exception) {
            ToolResult.Error("Не удалось запустить $rawName: ${e.localizedMessage}", "LAUNCH_ERROR")
        }
    }
}
