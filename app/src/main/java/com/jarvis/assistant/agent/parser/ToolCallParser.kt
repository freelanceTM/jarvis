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
        val trimmed = rawLlmOutput.trim()

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            val jsonCandidate = trimmed.substring(firstBrace, lastBrace + 1)
            try {
                val element = json.parseToJsonElement(jsonCandidate)
                if (element is JsonObject) {
                    val callsArray = element["tool_calls"]?.jsonArray
                    if (callsArray != null) {
                        callsArray.forEach { callElement ->
                            if (callElement is JsonObject) {
                                val toolId = callElement["tool"]?.jsonPrimitive?.contentOrNull
                                    ?: callElement["toolId"]?.jsonPrimitive?.contentOrNull
                                    ?: callElement["name"]?.jsonPrimitive?.contentOrNull

                                val argsObj = callElement["arguments"]?.jsonObject
                                    ?: callElement["params"]?.jsonObject
                                    ?: JsonObject(emptyMap())

                                if (!toolId.isNullOrBlank()) {
                                    toolCalls.add(ToolCall(toolId = toolId.trim(), arguments = argsObj))
                                }
                            }
                        }
                        if (toolCalls.isNotEmpty()) return toolCalls
                    }

                    // Single tool call format: {"tool": "...", "arguments": {...}}
                    val toolId = element["tool"]?.jsonPrimitive?.contentOrNull
                    if (!toolId.isNullOrBlank() && toolId != "tool_name") {
                        val argsObj = element["arguments"]?.jsonObject
                            ?: element["params"]?.jsonObject
                            ?: JsonObject(emptyMap())
                        return listOf(ToolCall(toolId = toolId.trim(), arguments = argsObj))
                    }
                }
            } catch (_: Exception) { }
        }

        return emptyList()
    }
}
