package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.media.AudioManager
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.verification.ExecutionVerification
import com.jarvis.assistant.agent.tools.verification.VolumeAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управление громкостью С ВЕРИФИКАЦИЕЙ результата (execute → verify → SUCCESS).
 *
 * Android применяет изменение громкости асинхронно и может МОЛЧА его отклонить
 * (режим «Не беспокоить», лимиты). Поэтому после мутации читается фактический
 * индекс потока, и SUCCESS возвращается только при подтверждённом изменении.
 * «Громкость увеличена» при уже максимальной громкости — запрещено.
 */
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
        val volumeAction = when (action) {
            "up", "громче" -> VolumeAction.UP
            "down", "тише" -> VolumeAction.DOWN
            "mute", "без звука" -> VolumeAction.MUTE
            "max", "максимум" -> VolumeAction.MAX
            "set", "установить" -> VolumeAction.SET
            else -> return ToolExecutionResult.failure("Неизвестное действие: $action", "UNKNOWN_ACTION")
        }

        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val minVol = try {
            am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } catch (_: IllegalArgumentException) {
            0
        }
        val prevVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        val rollbackData = buildJsonObject {
            put("prev_volume", prevVol)
        }

        return try {
            when (volumeAction) {
                VolumeAction.UP ->
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                VolumeAction.DOWN ->
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                VolumeAction.MUTE ->
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, minVol, AudioManager.FLAG_SHOW_UI)
                VolumeAction.MAX ->
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                VolumeAction.SET -> {
                    val p = arguments["percent"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 50
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, ExecutionVerification.volumeTargetIndex(p, maxVol), AudioManager.FLAG_SHOW_UI)
                }
            }

            // ------------------------------------------------------ VERIFY
            // Читаем фактический индекс (изменение применяется асинхронно)
            // и возвращаем SUCCESS только при подтверждённой мутации.
            val expected = when (volumeAction) {
                VolumeAction.MUTE -> minVol
                VolumeAction.MAX -> maxVol
                VolumeAction.SET ->
                    ExecutionVerification.volumeTargetIndex(
                        arguments["percent"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 50,
                        maxVol
                    )
                else -> null // направление проверит verifyVolumeChange по prev/actual
            }
            val actual = ExecutionVerification.pollFor(
                read = { am.getStreamVolume(AudioManager.STREAM_MUSIC) },
                satisfied = { value -> expected?.let { value == it } ?: (value != prevVol) }
            )

            val outcome = ExecutionVerification.verifyVolumeChange(
                action = volumeAction,
                previousIndex = prevVol,
                actualIndex = actual,
                maxIndex = maxVol,
                minIndex = minVol,
                requestedPercent = arguments["percent"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 50
            )
            val data = buildJsonObject {
                put("volume", actual)
                put("max", maxVol)
                put("previous_volume", prevVol)
            }
            if (outcome.verified) {
                ToolExecutionResult.success(outcome.summary, rollbackData = rollbackData, data = data)
            } else {
                ToolExecutionResult.failure(outcome.summary, outcome.reason ?: "VOLUME_VERIFY_FAILED", data = data)
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка изменения громкости: ${e.localizedMessage}", "AUDIO_ERROR")
        }
    }

    override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val prev = rollbackData?.get("prev_volume")?.jsonPrimitive?.intOrNull ?: return false
        return try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
            // Rollback тоже верифицируется: true только при подтверждённом возврате.
            val actual = ExecutionVerification.pollFor(
                read = { am.getStreamVolume(AudioManager.STREAM_MUSIC) },
                satisfied = { it == prev }
            )
            actual == prev
        } catch (_: Exception) {
            false
        }
    }
}
