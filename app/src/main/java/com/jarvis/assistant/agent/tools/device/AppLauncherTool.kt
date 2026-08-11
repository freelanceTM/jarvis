package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.registry.JarvisTool
import com.jarvis.assistant.agent.registry.ToolCategory
import com.jarvis.assistant.agent.registry.ToolParamSpec
import com.jarvis.assistant.agent.registry.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLauncherTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "open_app"
    override val description: String = "Открывает установленное приложение на телефоне (Telegram, YouTube, WhatsApp, Chrome, Камера, Музыка, Настройки)"
    override val category: ToolCategory = ToolCategory.DEVICE

    override val parameters: List<ToolParamSpec> = listOf(
        ToolParamSpec(
            name = "app_name",
            type = "string",
            description = "Название приложения: telegram, youtube, whatsapp, chrome, camera, spotify, settings, gallery, calculator"
        )
    )

    private val packageMap = mapOf(
        "telegram" to "org.telegram.messenger",
        "телеграм" to "org.telegram.messenger",
        "телегу" to "org.telegram.messenger",
        "youtube" to "com.google.android.youtube",
        "ютуб" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "ватсап" to "com.whatsapp",
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
        "калькулятор" to "com.google.android.calculator"
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val rawName = args["app_name"]?.lowercase()?.trim().orEmpty()
        if (rawName.isEmpty()) {
            return ToolResult.Failure("Не указано имя приложения", "Missing app_name")
        }

        val target = packageMap[rawName] ?: rawName

        try {
            // 1. Попытка запуска по Package Name
            val launchIntent = context.packageManager.getLaunchIntentForPackage(target)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ToolResult.Success("Приложение $rawName успешно открыто")
            }

            // 2. Попытка запуска по Intent Action (Камера, Настройки)
            if (target.startsWith("android.")) {
                val actionIntent = Intent(target).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(actionIntent)
                return ToolResult.Success("Открываю $rawName")
            }

            // 3. Fallback: Поиск в маркете, если не установлено
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$rawName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            return ToolResult.Success("Приложение $rawName не найдено, открыт поиск")
        } catch (e: Exception) {
            return ToolResult.Failure("Не удалось открыть $rawName: ${e.message}", e.localizedMessage ?: "")
        }
    }
}
