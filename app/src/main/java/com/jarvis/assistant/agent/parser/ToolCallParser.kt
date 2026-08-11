package com.jarvis.assistant.agent.parser

import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolCallParser @Inject constructor(
    private val json: Json
) {
    /**
     * Извлекает список структурированных вызовов инструментов из ответа LLM
     */
    fun parse(rawLlmOutput: String, userPrompt: String = ""): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        // 1. Поиск JSON блоков в ответе LLM
        val jsonPattern = Regex("""\{[\s\S]*?"tool_calls"[\s\S]*?\}""")
        val match = jsonPattern.find(rawLlmOutput)

        if (match != null) {
            try {
                val jsonText = match.value.trim()
                val parsedElement = json.parseToJsonElement(jsonText).jsonObject
                val callsArray = parsedElement["tool_calls"]?.jsonArray

                callsArray?.forEach { callElement ->
                    val callObj = callElement.jsonObject
                    val toolId = callObj["tool"]?.jsonPrimitive?.contentOrNull
                        ?: callObj["toolId"]?.jsonPrimitive?.contentOrNull
                        ?: callObj["name"]?.jsonPrimitive?.contentOrNull

                    val argsObj = callObj["arguments"]?.jsonObject
                        ?: callObj["params"]?.jsonObject
                        ?: JsonObject(emptyMap())

                    if (!toolId.isNullOrBlank()) {
                        toolCalls.add(ToolCall(toolId = toolId.trim(), arguments = argsObj))
                    }
                }
                if (toolCalls.isNotEmpty()) return toolCalls
            } catch (_: Exception) { }
        }

        // 2. Поиск одиночного JSON вызова формата {"tool": "...", "arguments": {...}}
        val singlePattern = Regex("""\{[\s\S]*?"tool"[\s\S]*?\}""")
        val singleMatch = singlePattern.find(rawLlmOutput)
        if (singleMatch != null) {
            try {
                val singleObj = json.parseToJsonElement(singleMatch.value.trim()).jsonObject
                val toolId = singleObj["tool"]?.jsonPrimitive?.contentOrNull
                val argsObj = singleObj["arguments"]?.jsonObject
                    ?: singleObj["params"]?.jsonObject
                    ?: JsonObject(emptyMap())

                if (!toolId.isNullOrBlank() && toolId != "tool_name") {
                    return listOf(ToolCall(toolId = toolId.trim(), arguments = argsObj))
                }
            } catch (_: Exception) { }
        }

        return emptyList()
    }
}
