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
import com.jarvis.assistant.agent.tools.verification.ExecutionVerification
import com.jarvis.assistant.agent.tools.verification.VolumeAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управление медиа. Честность результатов:
 *  - громкость: verify через read-back getStreamVolume (см. SetVolumeTool);
 *  - media-клавиши: [AudioManager.dispatchMediaKeyEvent] не даёт обратной связи,
 *    реально ли играет плеер (для этого нужен MediaSessionManager с доступом к
 *    уведомлениям). Поэтому формулировки фиксируют СДЕЛАННОЕ ДЕЙСТВИЕ
 *    («команда отправлена плееру»), а не неподтверждаемый результат
 *    («поставлена на паузу») — без Fake Success.
 */
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

        // Громкость медиа — отдельная ветка: регулируется через системный
        // AudioManager, а не media-клавишами.
        if (intent == MediaIntent.VOLUME_UP || intent == MediaIntent.VOLUME_DOWN) {
            return try {
                val volumeAction = if (intent == MediaIntent.VOLUME_UP) VolumeAction.UP else VolumeAction.DOWN
                val direction = if (intent == MediaIntent.VOLUME_UP) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val prevVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)

                // ------------------------------------------------------ VERIFY
                val actual = ExecutionVerification.pollFor(
                    read = { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) },
                    satisfied = { it != prevVol }
                )
                val outcome = ExecutionVerification.verifyVolumeChange(
                    action = volumeAction,
                    previousIndex = prevVol,
                    actualIndex = actual,
                    maxIndex = maxVol
                )
                val data = buildJsonObject {
                    put("intent", intent.name)
                    put("volume", actual)
                    put("max", maxVol)
                }
                if (outcome.verified) {
                    ToolExecutionResult.success(outcome.summary, data = data)
                } else {
                    ToolExecutionResult.failure(outcome.summary, outcome.reason ?: "MEDIA_VOLUME_VERIFY_FAILED", data = data)
                }
            } catch (e: Exception) {
                ToolExecutionResult.failure("Ошибка регулировки громкости: ${e.localizedMessage}", "MEDIA_ERROR")
            }
        }

        val keyCode = when (intent) {
            MediaIntent.PAUSE_MEDIA -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaIntent.PLAY_MEDIA, MediaIntent.RESUME_MEDIA -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaIntent.NEXT_TRACK -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaIntent.PREVIOUS_TRACK -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaIntent.STOP_MEDIA -> KeyEvent.KEYCODE_MEDIA_STOP
            MediaIntent.TOGGLE_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaIntent.VOLUME_UP, MediaIntent.VOLUME_DOWN -> return ToolExecutionResult.failure(
                summary = "Некорректный вызов громкости через media.control",
                error = "INVALID_VOLUME_CALL"
            )
        }

        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            // dispatchMediaKeyEvent не сообщает, применил ли плеер команду:
            // формулировка фиксирует действие, а не неподтверждаемый результат.
            val actionLabel = when (intent) {
                MediaIntent.PAUSE_MEDIA -> "Команда паузы отправлена медиаплееру"
                MediaIntent.PLAY_MEDIA -> "Команда воспроизведения отправлена медиаплееру"
                MediaIntent.RESUME_MEDIA -> "Команда продолжения отправлена медиаплееру"
                MediaIntent.NEXT_TRACK -> "Команда следующего трека отправлена медиаплееру"
                MediaIntent.PREVIOUS_TRACK -> "Команда предыдущего трека отправлена медиаплееру"
                MediaIntent.STOP_MEDIA -> "Команда остановки отправлена медиаплееру"
                MediaIntent.TOGGLE_PLAY_PAUSE -> "Команда play/pause отправлена медиаплееру"
                MediaIntent.VOLUME_UP, MediaIntent.VOLUME_DOWN -> "Громкость изменена"
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
