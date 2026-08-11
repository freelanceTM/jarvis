package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.memory.model.MemoryType
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RememberFactTool @Inject constructor(
    private val memoryManager: JarvisMemoryManager
) : JarvisTool {

    override val toolId: String = "memory.remember"
    override val description: String = "Сохраняет постоянный факт о пользователе в долговременную память (имя, машина, город, предпочтения, проекты)"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("key") {
                put("type", "string")
                put("description", "Ключ факта (например: user.name, user.car, user.city)")
            }
            putJsonObject("value") {
                put("type", "string")
                put("description", "Значение факта (например: Александр, BMW M5, Москва)")
            }
        }
        put("required", buildJsonArray { add("key"); add("value") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val key = arguments["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val value = arguments["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (key.isEmpty() || value.isEmpty()) {
            return ToolExecutionResult.failure("Не указан ключ или значение для сохранения", "MISSING_PARAMS")
        }

        memoryManager.remember(
            type = MemoryType.FACT,
            content = "$key: $value",
            key = key,
            value = value,
            importance = 0.9f
        )

        val rollbackData = buildJsonObject {
            put("rollback_key", key)
        }

        return ToolExecutionResult.success("Запомнил: $key — $value", rollbackData = rollbackData)
    }

    override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        val key = rollbackData?.get("rollback_key")?.jsonPrimitive?.contentOrNull ?: return false
        memoryManager.deleteMemoryByKey(key)
        return true
    }
}
