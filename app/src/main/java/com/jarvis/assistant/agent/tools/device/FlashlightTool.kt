package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.verification.ExecutionVerification
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фонарик С ВЕРИФИКАЦИЕЙ результата (execute → verify → SUCCESS).
 *
 * 1. Камера выбирается по признаку вспышки (FLASH_INFO_AVAILABLE + задняя),
 *    а не «первая в списке» — иначе setTorchMode падает или молчит.
 * 2. Состояние вспышки подтверждается через [CameraManager.TorchCallback]:
 *    SUCCESS только если система сообщила фактическое включение/выключение.
 *    Регистрация callback сразу доставляет текущее состояние, поэтому случай
 *    «фонарик уже был включён» тоже верифицирован (идемпотентный успех).
 */
@Singleton
class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.flashlight"
    override val description: String = "Включает или выключает фонарик (вспышку) на телефоне"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "true - включить фонарик, false - выключить")
            }
        }
        put("required", buildJsonArray { add("enabled") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolExecutionResult.failure("Камера/фонарик недоступны на устройстве", "NO_CAMERA_SERVICE")

        return try {
            val cameraId = flashCameraId(cameraManager)
                ?: return ToolExecutionResult.failure("Фонарик не найден на устройстве", "NO_CAMERA_ID")

            val outcome = setTorchVerified(cameraManager, cameraId, enabled)
            if (!outcome.confirmed) {
                return ToolExecutionResult.failure(
                    "Система не подтвердила переключение фонарика",
                    "TORCH_VERIFY_FAILED",
                    data = buildJsonObject { put("enabled", enabled) }
                )
            }

            val summary = when {
                enabled && outcome.alreadyInState -> "Фонарик уже был включён"
                enabled -> "Фонарик включён"
                outcome.alreadyInState -> "Фонарик уже был выключен"
                else -> "Фонарик выключен"
            }
            ToolExecutionResult.success(
                summary = summary,
                rollbackData = buildJsonObject { put("prev_enabled", !enabled) },
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("verified", true)
                    put("already_in_state", outcome.alreadyInState)
                }
            )
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось переключить фонарик: ${e.localizedMessage}", "TORCH_ERROR")
        }
    }

    override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val prev = rollbackData?.get("prev_enabled")?.jsonPrimitive?.booleanOrNull ?: return false
        return try {
            val cameraId = flashCameraId(cameraManager) ?: return false
            setTorchVerified(cameraManager, cameraId, prev).confirmed
        } catch (_: Exception) {
            false
        }
    }

    /** Результат верифицированного переключения вспышки. */
    private data class TorchOutcome(
        /** true — система через TorchCallback подтвердила целевое состояние. */
        val confirmed: Boolean,
        /** true — фонарик УЖЕ был в целевом состоянии, запись не требовалась. */
        val alreadyInState: Boolean
    )

    /**
     * Включает/выключает вспышку и ПОДТВЕРЖДАЕТ результат через TorchCallback.
     *
     * Порядок честной проверки:
     *  1. регистрируем callback и ждём доставки текущего состояния;
     *  2. если состояние уже целевое — верифицировано без записи (идемпотентно);
     *  3. иначе setTorchMode и поллинг до фактического перехода (bounded по времени);
     *  4. не подтвердилось за бюджет — confirmed=false (инструмент вернёт FAILURE, не SUCCESS).
     */
    private suspend fun setTorchVerified(cameraManager: CameraManager, cameraId: String, expected: Boolean): TorchOutcome {
        // AtomicBoolean: callback доставляется из главного потока, поллинг — на IO.
        val torchOn = java.util.concurrent.atomic.AtomicBoolean(!expected)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) torchOn.set(enabled)
            }
        }
        cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        try {
            // Доставка текущего состояния вспышки (onTorchModeChanged приходит сразу).
            delay(TORCH_STATE_SETTLE_MS)
            if (torchOn.get() == expected) {
                return TorchOutcome(confirmed = true, alreadyInState = true)
            }
            cameraManager.setTorchMode(cameraId, expected)
            val deadline = System.currentTimeMillis() + TORCH_VERIFY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (torchOn.get() == expected) return TorchOutcome(confirmed = true, alreadyInState = false)
                delay(TORCH_POLL_STEP_MS)
            }
            return TorchOutcome(confirmed = torchOn.get() == expected, alreadyInState = false)
        } finally {
            runCatching { cameraManager.unregisterTorchCallback(callback) }
        }
    }

    /** Камера со вспышкой: сначала задняя, затем любая (см. [ExecutionVerification.pickFlashCameraId]). */
    private fun flashCameraId(cameraManager: CameraManager): String? {
        val ids = try {
            cameraManager.cameraIdList.toList()
        } catch (_: Exception) {
            return null
        }
        return ExecutionVerification.pickFlashCameraId(
            cameraIds = ids,
            hasFlash = { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            },
            isBackFacing = { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }.getOrDefault(false)
            }
        )
    }

    private companion object {
        /** Время доставки текущего состояния вспышки в callback. */
        const val TORCH_STATE_SETTLE_MS = 150L

        /** Бюджет подтверждения перехода вспышки (суммарно << executionTimeoutMs 4 с). */
        const val TORCH_VERIFY_TIMEOUT_MS = 800L

        /** Шаг поллинга состояния вспышки. */
        const val TORCH_POLL_STEP_MS = 60L
    }
}
