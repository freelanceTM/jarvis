package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.memory.model.MemoryType
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RememberFactTool @Inject constructor(
    private val memoryManager: JarvisMemoryManager
) : JarvisTool {

    override val name: String = "remember_fact"
    override val description: String = "Сохраняет постоянный факт о пользователе в долговременную память (имя, машина, город, предпочтения, проекты, контакты)"
    override val risk: ToolRisk = ToolRisk.SAFE

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

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val key = arguments["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val value = arguments["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (key.isEmpty() || value.isEmpty()) {
            return ToolResult.Error("Не указан ключ или значение для сохранения", "MISSING_PARAMS")
        }

        memoryManager.remember(
            type = MemoryType.FACT,
            content = "$key: $value",
            key = key,
            value = value,
            importance = 0.9f
        )

        return ToolResult.Success("Запомнил: $key — $value")
    }
}
