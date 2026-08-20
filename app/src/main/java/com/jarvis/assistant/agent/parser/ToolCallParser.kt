package com.jarvis.assistant.agent.parser

import android.util.Log
import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolCallParser @Inject constructor(
    private val json: Json
) {
    companion object {
        private const val TAG = "ToolCallParser"
        internal const val MAX_LLM_OUTPUT_CHARS = 1_000_000
    }

    /**
     * Извлекает список структурированных вызовов инструментов из ответа LLM.
     * Поддерживает чистый JSON и JSON-объект внутри обычного текста/code fence.
     */
    fun parse(rawLlmOutput: String, userPrompt: String = ""): List<ToolCall> {
        if (rawLlmOutput.length > MAX_LLM_OUTPUT_CHARS) {
            Log.w(TAG, "parse: ответ LLM превышает безопасный предел")
            return emptyList()
        }

        val trimmed = rawLlmOutput.trim()
        if (trimmed.isEmpty()) return emptyList()

        for (candidate in extractJsonObjects(trimmed)) {
            val element = try {
                json.parseToJsonElement(candidate) as? JsonObject
            } catch (e: Exception) {
                Log.w(TAG, "parse: пропущен некорректный JSON-объект (${e.javaClass.simpleName})")
                null
            } ?: continue

            val calls = parseObject(element)
            if (calls.isNotEmpty()) return calls
        }

        return emptyList()
    }

    private fun parseObject(element: JsonObject): List<ToolCall> {
        val callsArray = element["tool_calls"] as? JsonArray
        if (callsArray != null) {
            // Один повреждённый элемент не должен уничтожать остальные
            // корректные tool calls в том же ответе.
            val calls = callsArray.mapNotNull(::parseCallObject)
            if (calls.isNotEmpty()) return calls
        }

        return parseCallObject(element)?.let(::listOf).orEmpty()
    }

    private fun parseCallObject(element: kotlinx.serialization.json.JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        val toolId = primitiveContent(obj["tool"])
            ?: primitiveContent(obj["toolId"])
            ?: primitiveContent(obj["name"])
            ?: return null
        val cleanToolId = toolId.trim()
        if (cleanToolId.isEmpty() || cleanToolId == "tool_name") return null

        val args = (obj["arguments"] as? JsonObject)
            ?: (obj["params"] as? JsonObject)
            ?: JsonObject(emptyMap())
        return ToolCall(toolId = cleanToolId, arguments = args)
    }

    private fun primitiveContent(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull

    /**
     * Находит сбалансированные JSON-объекты, учитывая строки и escaped quotes.
     * В отличие от substring(first '{', last '}') не склеивает два независимых
     * объекта и не ломается на фигурных скобках внутри строковых аргументов.
     */
    private fun extractJsonObjects(text: String): Sequence<String> = sequence {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        for (index in text.indices) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }

            when (ch) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        yield(text.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
    }
}
