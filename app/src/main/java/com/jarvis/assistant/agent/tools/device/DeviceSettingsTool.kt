package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.registry.JarvisTool
import com.jarvis.assistant.agent.registry.ToolCategory
import com.jarvis.assistant.agent.registry.ToolParamSpec
import com.jarvis.assistant.agent.registry.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSettingsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "open_settings"
    override val description: String = "Открывает конкретный экран настроек телефона: bluetooth, wifi, battery, display, sound"
    override val category: ToolCategory = ToolCategory.DEVICE

    override val parameters: List<ToolParamSpec> = listOf(
        ToolParamSpec(
            name = "target",
            type = "string",
            description = "Настройки: bluetooth, wifi, battery, display, sound, general"
        )
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val target = args["target"]?.lowercase()?.trim() ?: "general"

        val action = when (target) {
            "bluetooth", "блютуз" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi", "вайфай" -> Settings.ACTION_WIFI_SETTINGS
            "battery", "батарея", "аккумулятор" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display", "экран", "яркость" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "звук", "звуки" -> Settings.ACTION_SOUND_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Открываю настройки: $target")
        } catch (e: Exception) {
            ToolResult.Failure("Не удалось открыть настройки", e.localizedMessage ?: "")
        }
    }
}
