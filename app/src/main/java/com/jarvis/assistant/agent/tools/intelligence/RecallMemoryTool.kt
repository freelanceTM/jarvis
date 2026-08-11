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
class RecallMemoryTool @Inject constructor(
    private val memoryManager: JarvisMemoryManager
) : JarvisTool {

    override val toolId: String = "memory.recall"
    override val description: String = "Ищет факты о пользователе в долговременной семантической памяти JARVIS"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Что нужно вспомнить о пользователе")
            }
        }
        put("required", buildJsonArray { add("query") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val memories = memoryManager.recall(query, limit = 4)

        if (memories.isEmpty()) {
            return ToolExecutionResult.success("В памяти пока нет информации по запросу '$query'")
        }

        val summary = memories.joinToString("; ") { it.content }
        val dataObj = buildJsonObject {
            put("count", memories.size)
        }

        return ToolExecutionResult.success("Найдено в памяти: $summary", data = dataObj)
    }
}
