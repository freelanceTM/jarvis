package com.jarvis.assistant.agent.fast

import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FastRouteResult {
    data class HandledLocally(
        val toolCall: ToolCall,
        val immediateVoiceResponse: String? = null
    ) : FastRouteResult

    data object ForwardToLlm : FastRouteResult
}

/**
 * Fast Command Router (Локальный NLU-движок без интернета и без LLM)
 * Время срабатывания: < 10 миллисекунд.
 */
@Singleton
class FastCommandRouter @Inject constructor() {

    fun route(rawQuery: String): FastRouteResult {
        val q = rawQuery.lowercase().trim()
            .replace(Regex("^(джарвис|jarvis|жарвис|джей|диджей)[,\\s]*"), "")
            .trim()

        if (q.isEmpty()) return FastRouteResult.ForwardToLlm

        // 1. Фонарик (Flashlight)
        if (q.contains("фонарик") || q.contains("вспышк") || q.contains("посвети") || q.contains("свет")) {
            val isOff = q.contains("выключ") || q.contains("погаси") || q.contains("туши") || q.contains("выруби")
            val isToggle = q.contains("включ") || q.contains("зажги") || q.contains("посвети") || q.contains("фонарик") || q.contains("вруби")
            if (isOff || isToggle) {
                val enabled = !isOff
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        name = "flashlight",
                        arguments = buildJsonObject { put("enabled", enabled) }
                    ),
                    immediateVoiceResponse = if (enabled) "Фонарик включён, сэр." else "Фонарик выключен, сэр."
                )
            }
        }

        // 2. Громкость (Volume Control)
        if (q.contains("громк") || q.contains("звук") || q.contains("тише") || q.contains("погромче") || q.contains("прибавь") || q.contains("убавь")) {
            val percentMatch = Regex("""(\d+)\s*(%|процент)""").find(q)
            if (percentMatch != null) {
                val percent = percentMatch.groupValues[1].toIntOrNull() ?: 50
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        name = "set_volume",
                        arguments = buildJsonObject {
                            put("action", "set")
                            put("percent", percent)
                        }
                    ),
                    immediateVoiceResponse = "Громкость установлена на $percent процентов, сэр."
                )
            }

            val action = when {
                q.contains("максимум") || q.contains("на всю") || q.contains("на полную") -> "max"
                q.contains("без звука") || q.contains("выключи звук") || q.contains("заглуши") || q.contains("мут") -> "mute"
                q.contains("тише") || q.contains("убавь") || q.contains("потише") || q.contains("уменьши") -> "down"
                q.contains("громче") || q.contains("прибавь") || q.contains("погромче") || q.contains("увеличь") -> "up"
                else -> "up"
            }

            val voice = when (action) {
                "max" -> "Громкость на максимуме, сэр."
                "mute" -> "Звук полностью отключен, сэр."
                "down" -> "Сделал тише, сэр."
                "up" -> "Сделал громче, сэр."
                else -> "Громкость изменена, сэр."
            }

            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(
                    name = "set_volume",
                    arguments = buildJsonObject { put("action", action) }
                ),
                immediateVoiceResponse = voice
            )
        }

        // 3. Батарея и заряд (Battery)
        if (q.contains("батаре") || q.contains("заряд") || q.contains("аккумулятор") || q.contains("сколько процентов")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(name = "get_battery", arguments = JsonObject(emptyMap()))
            )
        }

        // 4. Время, дата и день недели (Time / Date)
        if (q.contains("время") || q.contains("который час") || q.contains("сколько времени") || q.contains("число") || q.contains("дата") || q.contains("день недели")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(name = "get_time", arguments = JsonObject(emptyMap()))
            )
        }

        // 5. Запуск приложений (App Launcher)
        if (q.startsWith("открой") || q.startsWith("запусти") || q.startsWith("включи") || q.startsWith("перейди в") || q.startsWith("вруби")) {
            val app = when {
                q.contains("телеграм") || q.contains("telegram") || q.contains("телегу") -> "telegram"
                q.contains("ютуб") || q.contains("youtube") -> "youtube"
                q.contains("ватсап") || q.contains("whatsapp") -> "whatsapp"
                q.contains("камер") || q.contains("camera") || q.contains("фотк") -> "camera"
                q.contains("хром") || q.contains("chrome") || q.contains("браузер") -> "chrome"
                q.contains("музык") || q.contains("спотифай") || q.contains("spotify") || q.contains("плеер") -> "spotify"
                q.contains("настройк") -> "settings"
                q.contains("калькулятор") -> "calculator"
                q.contains("карт") || q.contains("maps") || q.contains("навигатор") -> "maps"
                else -> ""
            }

            if (app.isNotEmpty()) {
                val appTitle = when (app) {
                    "telegram" -> "Telegram"
                    "youtube" -> "YouTube"
                    "whatsapp" -> "WhatsApp"
                    "camera" -> "Камеру"
                    "chrome" -> "Chrome"
                    "spotify" -> "Музыку"
                    "settings" -> "Настройки"
                    "calculator" -> "Калькулятор"
                    "maps" -> "Карты"
                    else -> app
                }

                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        name = "open_app",
                        arguments = buildJsonObject { put("app_name", app) }
                    ),
                    immediateVoiceResponse = "Открываю $appTitle, сэр."
                )
            }
        }

        // 6. Bluetooth & Wi-Fi настройки
        if (q.contains("блютуз") || q.contains("bluetooth")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(name = "bluetooth_control", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Открываю панель Bluetooth, сэр."
            )
        }

        if (q.contains("вайфай") || q.contains("wifi") || q.contains("wi-fi") || q.contains("интернет")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(name = "wifi_control", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Открываю настройки Wi-Fi, сэр."
            )
        }

        // 7. Информация об устройстве
        if (q.contains("модель") || q.contains("что за телефон") || q.contains("версия андроид") || q.contains("характеристики")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(name = "get_device_info", arguments = JsonObject(emptyMap()))
            )
        }

        // Если команда сложная (рассуждение, анализ, вопросы, диалог) -> отправляем в Большой AI Мозг
        return FastRouteResult.ForwardToLlm
    }
}
