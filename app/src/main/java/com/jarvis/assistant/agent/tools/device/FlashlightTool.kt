package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.hardware.camera2.CameraManager
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

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
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolExecutionResult.failure("Фонарик не найден на устройстве", "NO_CAMERA_ID")

            cameraManager.setTorchMode(cameraId, enabled)
            val summary = if (enabled) "Фонарик включён" else "Фонарик выключен"
            val rollbackData = buildJsonObject {
                put("prev_enabled", !enabled)
            }

            ToolExecutionResult.success(summary = summary, rollbackData = rollbackData)
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось переключить фонарик: ${e.localizedMessage}", "TORCH_ERROR")
        }
    }

    override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val prev = rollbackData?.get("prev_enabled")?.jsonPrimitive?.booleanOrNull ?: return false
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
        cameraManager.setTorchMode(cameraId, prev)
        return true
    }
}
