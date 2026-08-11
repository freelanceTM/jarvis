package com.jarvis.assistant.agent.parser

import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.json.*
import org.json.JSONObject
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
                    val toolName = callObj["tool"]?.jsonPrimitive?.contentOrNull
                        ?: callObj["name"]?.jsonPrimitive?.contentOrNull

                    val argsObj = callObj["arguments"]?.jsonObject
                        ?: callObj["params"]?.jsonObject
                        ?: JsonObject(emptyMap())

                    if (!toolName.isNullOrBlank()) {
                        toolCalls.add(ToolCall(name = toolName.trim(), arguments = argsObj))
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
                val toolName = singleObj["tool"]?.jsonPrimitive?.contentOrNull
                val argsObj = singleObj["arguments"]?.jsonObject
                    ?: singleObj["params"]?.jsonObject
                    ?: JsonObject(emptyMap())

                if (!toolName.isNullOrBlank() && toolName != "tool_name") {
                    return listOf(ToolCall(name = toolName.trim(), arguments = argsObj))
                }
            } catch (_: Exception) { }
        }

        // 3. Быстрый локальный эвристический парсер намерений (Zero-Latency Fallback)
        if (userPrompt.isNotBlank()) {
            val fallback = extractHeuristicIntent(userPrompt)
            if (fallback != null) {
                return listOf(fallback)
            }
        }

        return emptyList()
    }

    private fun extractHeuristicIntent(prompt: String): ToolCall? {
        val p = prompt.lowercase().trim()

        // Фонарик
        if (p.contains("фонарик") || p.contains("вспышк")) {
            val isOff = p.contains("выключ") || p.contains("погаси")
            return ToolCall(
                name = "flashlight",
                arguments = buildJsonObject { put("enabled", !isOff) }
            )
        }

        // Батарея
        if (p.contains("батаре") || p.contains("заряд") || p.contains("аккумулятор")) {
            return ToolCall(name = "get_battery", arguments = JsonObject(emptyMap()))
        }

        // Время / Дата
        if (p.contains("сколько времени") || p.contains("который час") || p.contains("какое число") || p.contains("какая дата")) {
            return ToolCall(name = "get_time", arguments = JsonObject(emptyMap()))
        }

        // Громкость
        if (p.contains("громк") || p.contains("звук")) {
            val action = when {
                p.contains("громче") || p.contains("прибав") -> "up"
                p.contains("тише") || p.contains("убав") -> "down"
                p.contains("выключ") || p.contains("без звука") || p.contains("мут") -> "mute"
                p.contains("максимум") || p.contains("на всю") -> "max"
                else -> "up"
            }
            return ToolCall(
                name = "set_volume",
                arguments = buildJsonObject { put("action", action) }
            )
        }

        // Запуск приложений
        if (p.startsWith("открой") || p.startsWith("запусти") || p.startsWith("включи")) {
            val app = when {
                p.contains("телеграм") || p.contains("telegram") || p.contains("телегу") -> "telegram"
                p.contains("ютуб") || p.contains("youtube") -> "youtube"
                p.contains("ватсап") || p.contains("whatsapp") -> "whatsapp"
                p.contains("камер") || p.contains("camera") || p.contains("фотк") -> "camera"
                p.contains("хром") || p.contains("chrome") || p.contains("браузер") -> "chrome"
                p.contains("музык") || p.contains("спотифай") || p.contains("spotify") -> "spotify"
                p.contains("настройк") -> "settings"
                p.contains("калькулятор") -> "calculator"
                else -> ""
            }
            if (app.isNotEmpty()) {
                return ToolCall(
                    name = "open_app",
                    arguments = buildJsonObject { put("app_name", app) }
                )
            }
        }

        // Настройки
        if (p.contains("блютуз") || p.contains("bluetooth")) {
            return ToolCall(
                name = "bluetooth_control",
                arguments = buildJsonObject { put("action", if (p.contains("выключ")) "disable" else "open_settings") }
            )
        }
        if (p.contains("вайфай") || p.contains("wifi") || p.contains("wi-fi")) {
            return ToolCall(
                name = "wifi_control",
                arguments = buildJsonObject { put("action", "open_settings") }
            )
        }

        return null
    }
}
