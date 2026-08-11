package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.hardware.camera2.CameraManager
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "flashlight"
    override val description: String = "Включает или выключает фонарик (вспышку) на телефоне"
    override val risk: ToolRisk = ToolRisk.LOW

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("enabled", buildJsonObject {
                put("type", "boolean")
                put("description", "true - включить фонарик, false - выключить")
            })
        })
        put("required", buildJsonArray { add("enabled") })
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.Error("Камера/фонарик недоступны на устройстве", "NO_CAMERA_SERVICE")

        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolResult.Error("Фонарик не найден на устройстве", "NO_CAMERA_ID")

            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult.Success(if (enabled) "Фонарик включён" else "Фонарик выключен")
        } catch (e: Exception) {
            ToolResult.Error("Не удалось переключить фонарик: ${e.localizedMessage}", "TORCH_ERROR")
        }
    }
}
