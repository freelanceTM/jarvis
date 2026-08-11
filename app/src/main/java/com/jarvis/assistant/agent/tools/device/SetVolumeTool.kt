package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.media.AudioManager
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetVolumeTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "set_volume"
    override val description: String = "Регулирует громкость телефона: сделать громче, тише, выключить звук или задать точный процент (0-100)"
    override val risk: ToolRisk = ToolRisk.LOW

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "up (громче), down (тише), mute (без звука), max (максимум), set (установить процент)")
            })
            put("percent", buildJsonObject {
                put("type", "number")
                put("description", "Уровень громкости от 0 до 100")
            })
        })
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Error("Аудио-служба недоступна", "NO_AUDIO_SERVICE")

        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "up"
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        return try {
            when (action) {
                "up", "громче" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость увеличена")
                }
                "down", "тише" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость уменьшена")
                }
                "mute", "без звука" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Звук выключен")
                }
                "max", "максимум" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость установлена на максимум")
                }
                "set", "установить" -> {
                    val p = arguments["percent"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 50
                    val target = (maxVol * (p / 100.0)).toInt()
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Громкость установлена на $p%")
                }
                else -> ToolResult.Error("Неизвестное действие: $action", "UNKNOWN_ACTION")
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка изменения громкости: ${e.localizedMessage}", "AUDIO_ERROR")
        }
    }
}
