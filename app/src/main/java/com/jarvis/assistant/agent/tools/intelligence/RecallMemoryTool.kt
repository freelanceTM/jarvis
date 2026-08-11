package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.memory.JarvisMemoryManager
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecallMemoryTool @Inject constructor(
    private val memoryManager: JarvisMemoryManager
) : JarvisTool {

    override val name: String = "recall_memory"
    override val description: String = "Ищет факты о пользователе в долговременной памяти JARVIS"
    override val risk: ToolRisk = ToolRisk.SAFE

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

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val memories = memoryManager.retrieveRelevantMemories(query)

        if (memories.isEmpty()) {
            return ToolResult.Success("В памяти пока нет информации по запросу '$query'")
        }

        val summary = memories.joinToString("; ") { "${it.keyName}: ${it.valueText}" }
        return ToolResult.Success("Найдено в памяти: $summary")
    }
}
