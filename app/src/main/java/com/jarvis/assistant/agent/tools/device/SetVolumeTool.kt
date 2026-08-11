package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.media.AudioManager
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetVolumeTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.volume"
    override val description: String = "Управляет громкостью звука: сделать громче, тише, выключить звук, максимум или задать точный процент (0-100)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "up (громче), down (тише), mute (без звука), max (максимум), set (установить процент)")
            }
            putJsonObject("percent") {
                put("type", "number")
                put("description", "Уровень громкости от 0 до 100")
            }
        }
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionResult.failure("Аудио-служба недоступна", "NO_AUDIO_SERVICE")

        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "up"
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val prevVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        val rollbackData = buildJsonObject {
            put("prev_volume", prevVol)
        }

        return try {
            when (action) {
                "up", "громче" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ToolExecutionResult.success("Громкость увеличена", rollbackData = rollbackData)
                }
                "down", "тише" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ToolExecutionResult.success("Громкость уменьшена", rollbackData = rollbackData)
                }
                "mute", "без звука" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    ToolExecutionResult.success("Звук выключен", rollbackData = rollbackData)
                }
                "max", "максимум" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                    ToolExecutionResult.success("Громкость установлена на максимум", rollbackData = rollbackData)
                }
                "set", "установить" -> {
                    val p = arguments["percent"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 50
                    val target = (maxVol * (p / 100.0)).toInt()
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    ToolExecutionResult.success("Громкость установлена на $p%", rollbackData = rollbackData)
                }
                else -> ToolExecutionResult.failure("Неизвестное действие: $action", "UNKNOWN_ACTION")
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка изменения громкости: ${e.localizedMessage}", "AUDIO_ERROR")
        }
    }

    override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val prev = rollbackData?.get("prev_volume")?.jsonPrimitive?.intOrNull ?: return false
        am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, AudioManager.FLAG_SHOW_UI)
        return true
    }
}
