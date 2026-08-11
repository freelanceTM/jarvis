package com.jarvis.assistant.agent.tools.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "media.control"
    override val description: String = "Управляет воспроизведением музыки и видео (плей, пауза, следующий трек, предыдущий трек)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "play_pause, pause, play, next, previous, stop")
            }
        }
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "play_pause"
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionResult.failure("Аудио-служба недоступна", "NO_AUDIO_SERVICE")

        val keyCode = when (action) {
            "pause", "пауза", "стоп" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "плей", "играй", "продолжи" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next", "следующий", "вперед" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous", "назад", "предыдущий" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            val actionLabel = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Музыка поставлена на паузу"
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Воспроизведение запущено"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Переключено на следующий трек"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Переключено на предыдущий трек"
                else -> "Медиа-команда выполнена"
            }

            ToolExecutionResult.success(summary = actionLabel)
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка управления медиа: ${e.localizedMessage}", "MEDIA_ERROR")
        }
    }
}
