package com.jarvis.assistant.agent.tools.system

import android.content.Context
import android.os.Build
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDeviceInfoTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "get_device_info"
    override val description: String = "Возвращает информацию об устройстве: модель, производитель, версия Android"
    override val risk: ToolRisk = ToolRisk.SAFE

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val androidVersion = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT

        val infoText = "Устройство $manufacturer $model, Android $androidVersion (API $sdk)"
        return ToolResult.Success(infoText)
    }
}
