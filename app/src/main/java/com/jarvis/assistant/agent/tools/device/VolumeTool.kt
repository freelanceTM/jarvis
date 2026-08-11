package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.media.AudioManager
import com.jarvis.assistant.agent.registry.JarvisTool
import com.jarvis.assistant.agent.registry.ToolCategory
import com.jarvis.assistant.agent.registry.ToolParamSpec
import com.jarvis.assistant.agent.registry.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VolumeTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "set_volume"
    override val description: String = "Управляет громкостью звука на телефоне (увеличить, уменьшить, заглушить, установить уровень в процентах)"
    override val category: ToolCategory = ToolCategory.DEVICE

    override val parameters: List<ToolParamSpec> = listOf(
        ToolParamSpec(
            name = "action",
            type = "string",
            description = "Действие: up (громче), down (тише), mute (без звука), max (максимум), set (установить число)"
        ),
        ToolParamSpec(
            name = "level",
            type = "number",
            description = "Процент громкости от 0 до 100 (если action=set)",
            isRequired = false
        )
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Failure("Аудио-служба недоступна", "No AudioManager")

        val action = args["action"]?.lowercase()?.trim() ?: "up"
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        return try {
            when (action) {
                "up", "громче" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость увеличена")
                }
                "down", "тише" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость уменьшена")
                }
                "mute", "без звука", "выключить" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Звук выключен")
                }
                "max", "максимум", "на всю" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость установлена на максимум")
                }
                "set", "установить" -> {
                    val percent = args["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                    val targetVol = (maxVol * (percent / 100.0)).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость установлена на $percent%")
                }
                else -> ToolResult.Failure("Неизвестное действие: $action", "Unknown action")
            }
        } catch (e: Exception) {
            ToolResult.Failure("Не удалось изменить громкость", e.localizedMessage ?: "")
        }
    }
}
