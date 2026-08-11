package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForgetMemoryTool @Inject constructor(
    private val memoryManager: JarvisMemoryManager
) : JarvisTool {

    override val toolId: String = "memory.forget"
    override val description: String = "Удаляет указанный факт или воспоминание из долговременной памяти (например: BMW, машина, старый адрес)"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "string")
                put("description", "Что нужно забыть или удалить из памяти")
            }
        }
        put("required", buildJsonArray { add("target") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val target = arguments["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (target.isEmpty()) {
            return ToolExecutionResult.failure("Не указано, что нужно забыть", "MISSING_TARGET")
        }

        val result = memoryManager.forgetMemory(target)
        val dataObj = buildJsonObject {
            put("deleted_count", result.deletedCount)
        }

        return ToolExecutionResult.success(result.confirmationMessage, data = dataObj)
    }
}
