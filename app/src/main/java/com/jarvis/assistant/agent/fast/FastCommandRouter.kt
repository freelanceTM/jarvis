package com.jarvis.assistant.agent.fast

import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FastRouteResult {
    data class HandledLocally(
        val toolCall: ToolCall? = null,
        val immediateVoiceResponse: String
    ) : FastRouteResult

    data object ForwardToLlm : FastRouteResult
}

/**
 * Fast Command Router v0.4 (Локальный NLU-движок без интернета и без LLM)
 * Время срабатывания: < 10 миллисекунд.
 */
@Singleton
class FastCommandRouter @Inject constructor() {

    fun route(rawQuery: String): FastRouteResult {
        val q = rawQuery.lowercase().trim()
            .replace(Regex("^(джарвис|jarvis|жарвис|джей|диджей)[,\\s]*"), "")
            .trim()

        if (q.isEmpty()) return FastRouteResult.ForwardToLlm

        // 1. Приветствия и проверка связи
        if (q in listOf("ты тут", "ты тут?", "ты здесь", "ты здесь?", "але", "алло", "на связи")) {
            return FastRouteResult.HandledLocally(immediateVoiceResponse = "Да, сэр, я на связи. Чем могу помочь?")
        }
        if (q in listOf("привет", "здравствуй", "добрый день", "добрый вечер", "доброе утро", "хай", "салам")) {
            return FastRouteResult.HandledLocally(immediateVoiceResponse = "Приветствую, сэр. Я готов к работе.")
        }

        // 2. Управление медиа и музыкой (Play, Pause, Next, Prev)
        if (q.contains("пауз") || q == "стоп музыка" || q == "музыка стоп") {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "media.control", arguments = buildJsonObject { put("action", "pause") }),
                immediateVoiceResponse = "Музыка поставлена на паузу, сэр."
            )
        }
        if (q.contains("следующий трек") || q.contains("следующая песня") || q == "дальше" || q == "след трек") {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "media.control", arguments = buildJsonObject { put("action", "next") }),
                immediateVoiceResponse = "Включаю следующий трек, сэр."
            )
        }
        if (q.contains("предыдущий трек") || q.contains("назад трек")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "media.control", arguments = buildJsonObject { put("action", "previous") }),
                immediateVoiceResponse = "Переключаю назад, сэр."
            )
        }

        // 3. Фонарик (Flashlight)
        if (q.contains("фонарик") || q.contains("вспышк") || q.contains("посвети") || q.contains("свет")) {
            val isOff = q.contains("выключ") || q.contains("погаси") || q.contains("туши") || q.contains("выруби")
            val isToggle = q.contains("включ") || q.contains("зажги") || q.contains("посвети") || q.contains("фонарик") || q.contains("вруби")
            if (isOff || isToggle) {
                val enabled = !isOff
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        toolId = "device.flashlight",
                        arguments = buildJsonObject { put("enabled", enabled) }
                    ),
                    immediateVoiceResponse = if (enabled) "Фонарик включён, сэр." else "Фонарик выключен, сэр."
                )
            }
        }

        // 4. Громкость (Volume)
        if (q.contains("громк") || q.contains("звук") || q.contains("тише") || q.contains("погромче") || q.contains("прибавь") || q.contains("убавь")) {
            val percentMatch = Regex("""(\d+)\s*(%|процент)""").find(q)
            if (percentMatch != null) {
                val percent = percentMatch.groupValues[1].toIntOrNull() ?: 50
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        toolId = "device.volume",
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
                    toolId = "device.volume",
                    arguments = buildJsonObject { put("action", action) }
                ),
                immediateVoiceResponse = voice
            )
        }

        // 5. Батарея
        if (q.contains("батаре") || q.contains("заряд") || q.contains("аккумулятор") || q.contains("сколько процентов")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "system.battery", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Проверяю заряд батареи, сэр."
            )
        }

        // 6. Время и дата
        if (q.contains("время") || q.contains("который час") || q.contains("сколько времени") || q.contains("число") || q.contains("дата") || q.contains("день недели")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "system.time", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Проверяю время, сэр."
            )
        }

        // 7. Скриншот
        if (q.contains("скриншот") || q.contains("снимок экрана")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.screenshot", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Делаю скриншот экрана, сэр."
            )
        }

        // 8. Режим «Не беспокоить» (DND)
        if (q.contains("не беспокоить") || q.contains("режим тишины") || q.contains("без уведомлений")) {
            val isOff = q.contains("выключ") || q.contains("отключ")
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.dnd", arguments = buildJsonObject { put("enabled", !isOff) }),
                immediateVoiceResponse = if (!isOff) "Режим 'Не беспокоить' активирован, сэр." else "Режим 'Не беспокоить' отключен, сэр."
            )
        }

        // 9. Навигация и карты
        if (q.startsWith("навигатор в") || q.startsWith("маршрут в") || q.startsWith("поехали в") || q.startsWith("проложи маршрут до")) {
            val dest = q.replace(Regex("^(навигатор в|маршрут в|поехали в|проложи маршрут до)\\s*"), "").trim()
            if (dest.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(toolId = "location.navigation", arguments = buildJsonObject { put("destination", dest) }),
                    immediateVoiceResponse = "Прокладываю маршрут в $dest, сэр."
                )
            }
        }

        // 10. Телефонный звонок
        if (q.startsWith("позвони") || q.startsWith("набери") || q.startsWith("вызови")) {
            val contact = q.replace(Regex("^(позвони|набери|вызови)\\s*"), "").trim()
            if (contact.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(toolId = "communication.call", arguments = buildJsonObject { put("recipient", contact) }),
                    immediateVoiceResponse = "Набираю $contact, сэр."
                )
            }
        }

        // 11. Запуск приложений
        if (q.startsWith("открой") || q.startsWith("запусти") || q.startsWith("включи") || q.startsWith("перейди в") || q.startsWith("вруби")) {
            val app = when {
                q.contains("телеграм") || q.contains("telegram") || q.contains("телегу") || q.contains("тг") || q.contains("tg") -> "telegram"
                q.contains("ютуб") || q.contains("youtube") || q.contains("ют") -> "youtube"
                q.contains("ватсап") || q.contains("whatsapp") || q.contains("вацап") -> "whatsapp"
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
                        toolId = "device.open_app",
                        arguments = buildJsonObject { put("app_name", app) }
                    ),
                    immediateVoiceResponse = "Открываю $appTitle, сэр."
                )
            }
        }

        // 12. Bluetooth & Wi-Fi
        if (q.contains("блютуз") || q.contains("bluetooth")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.bluetooth", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Открываю панель Bluetooth, сэр."
            )
        }
        if (q.contains("вайфай") || q.contains("wifi") || q.contains("wi-fi") || q.contains("интернет")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.wifi", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Открываю настройки Wi-Fi, сэр."
            )
        }

        // 13. Информация об устройстве
        if (q.contains("модель") || q.contains("что за телефон") || q.contains("версия андроид") || q.contains("характеристики")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "system.device_info", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Получаю информацию об устройстве, сэр."
            )
        }

        return FastRouteResult.ForwardToLlm
    }
}
