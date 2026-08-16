package com.jarvis.assistant.agent.tools.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.media.MediaIntent
import com.jarvis.assistant.agent.media.MediaIntentParser
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityAwareTool {

    override val toolId: String = "media.control"
    override val description: String = "Управляет воспроизведением музыки и видео (плей, пауза, следующий трек, предыдущий трек)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.CONTROL_MEDIA),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: JarvisCapability = JarvisCapability.Media

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

        // Нормализация: фраза/аргумент -> MediaIntent -> KeyEvent.
        // Неизвестное действие НЕ подменяется на произвольное (раньше падало в PLAY_PAUSE).
        val intent = MediaIntentParser.normalizeAction(action)
            ?: return ToolExecutionResult.failure(
                summary = "Неизвестная медиа-команда: $action",
                error = "UNKNOWN_MEDIA_ACTION"
            )

        val keyCode = when (intent) {
            MediaIntent.PAUSE_MEDIA -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaIntent.PLAY_MEDIA -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaIntent.NEXT_TRACK -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaIntent.PREVIOUS_TRACK -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaIntent.STOP_MEDIA -> KeyEvent.KEYCODE_MEDIA_STOP
            MediaIntent.TOGGLE_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            val actionLabel = when (intent) {
                MediaIntent.PAUSE_MEDIA -> "Музыка поставлена на паузу"
                MediaIntent.PLAY_MEDIA -> "Воспроизведение запущено"
                MediaIntent.NEXT_TRACK -> "Переключено на следующий трек"
                MediaIntent.PREVIOUS_TRACK -> "Переключено на предыдущий трек"
                MediaIntent.STOP_MEDIA -> "Воспроизведение остановлено"
                MediaIntent.TOGGLE_PLAY_PAUSE -> "Воспроизведение переключено"
            }

            ToolExecutionResult.success(
                summary = actionLabel,
                data = buildJsonObject { put("intent", intent.name) }
            )
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка управления медиа: ${e.localizedMessage}", "MEDIA_ERROR")
        }
    }
}
