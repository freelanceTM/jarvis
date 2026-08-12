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
 * Fast Command Router v0.5 (Локальный NLU-движок с поддержкой Memory & Automation Governance)
 * Время срабатывания: < 10 миллисекунд.
 */
@Singleton
class FastCommandRouter @Inject constructor() {

    fun route(rawQuery: String): FastRouteResult {
        val q = rawQuery.lowercase().trim()
            .replace(Regex("^(джарвис|jarvis|жарвис|джей|диджей)[,\\s]*"), "")
            .trim()

        if (q.isEmpty()) return FastRouteResult.ForwardToLlm

        // 1. Команды забывания / очистки памяти (Memory 2.0 Governance)
        if (q.startsWith("забудь") || q.startsWith("удали из памяти") || q.startsWith("сотри из памяти")) {
            val target = q.replace(Regex("^(забудь|удали из памяти|сотри из памяти)\\s*"), "")
                .replace(Regex("^(что я|что у меня|обо мне|про|мою|мой)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        toolId = "memory.forget",
                        arguments = buildJsonObject { put("target", target) }
                    ),
                    immediateVoiceResponse = "Удаляю информацию о \"$target\" из памяти, сэр."
                )
            }
        }

        // 1.1 Создание голосовых сценариев автоматизации ("Когда подключатся наушники — включи музыку")
        if (q.startsWith("когда") || q.startsWith("при подключении") || q.startsWith("если")) {
            val isHeadphones = q.contains("наушник") || q.contains("гарнитур")
            val isWifi = q.contains("вайфай") || q.contains("wifi") || q.contains("интернет")
            val isBattery = q.contains("батаре") || q.contains("заряд") || q.contains("разрядит")

            if (isHeadphones || isWifi || isBattery) {
                val triggerType = when {
                    isHeadphones && (q.contains("отключ") || q.contains("сниму")) -> "HEADPHONES_DISCONNECTED"
                    isHeadphones -> "HEADPHONES_CONNECTED"
                    isBattery -> "BATTERY_LOW"
                    isWifi -> "WIFI_CONNECTED"
                    else -> "HEADPHONES_CONNECTED"
                }

                val actionTool = when {
                    q.contains("музык") || q.contains("трек") || q.contains("песн") || q.contains("плей") -> "media.control"
                    q.contains("фонарик") -> "device.flashlight"
                    q.contains("тише") || q.contains("громче") || q.contains("звук") -> "device.volume"
                    q.contains("телеграм") || q.contains("тг") -> "device.open_app"
                    else -> "media.control"
                }

                val actionParams = when (actionTool) {
                    "media.control" -> buildJsonObject { put("action", "next") }
                    "device.flashlight" -> buildJsonObject { put("enabled", !q.contains("выключ")) }
                    "device.volume" -> buildJsonObject { put("action", if (q.contains("тише")) "down" else "up") }
                    "device.open_app" -> buildJsonObject { put("app_name", "telegram") }
                    else -> buildJsonObject { }
                }

                val ruleName = when (triggerType) {
                    "HEADPHONES_CONNECTED" -> "Режим подключения наушников"
                    "HEADPHONES_DISCONNECTED" -> "Режим отключения наушников"
                    "BATTERY_LOW" -> "Режим низкого заряда"
                    "WIFI_CONNECTED" -> "Режим Wi-Fi"
                    else -> "Пользовательское правило"
                }

                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        toolId = "productivity.create_automation",
                        arguments = buildJsonObject {
                            put("name", ruleName)
                            put("trigger_type", triggerType)
                            put("tool_action", actionTool)
                            put("action_params", actionParams)
                            put("voice_announcement", "Автоматизация '$ruleName' успешно сработала, сэр.")
                        }
                    ),
                    immediateVoiceResponse = "Создаю автоматизацию: при событии '$ruleName' выполнить $actionTool, сэр."
                )
            }
        }

        // 2. Приветствия и проверка связи
        if (q in listOf("ты тут", "ты тут?", "ты здесь", "ты здесь?", "але", "алло", "на связи")) {
            return FastRouteResult.HandledLocally(immediateVoiceResponse = "Да, сэр, я на связи. Чем могу помочь?")
        }
        if (q in listOf("привет", "здравствуй", "добрый день", "добрый вечер", "доброе утро", "хай", "салам")) {
            return FastRouteResult.HandledLocally(immediateVoiceResponse = "Приветствую, сэр. Я готов к работе.")
        }

        // 3. Управление медиа и музыкой
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

        // 4. Фонарик
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

        // 5. Громкость
        if (q.contains("громк") || q.contains("звук") || q.contains("тише") || q.contains("тиш") || q.contains("громче") || q.contains("громч") || q.contains("погромче") || q.contains("прибавь") || q.contains("убавь") || q.contains("прибав") || q.contains("убав")) {
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

        // 6. Батарея
        if (q.contains("батаре") || q.contains("заряд") || q.contains("аккумулятор") || q.contains("сколько процентов")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "system.battery", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Проверяю заряд батареи, сэр."
            )
        }

        // 7. Время и дата
        if (q.contains("время") || q.contains("который час") || q.contains("сколько времени") || q.contains("число") || q.contains("дата") || q.contains("день недели")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "system.time", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Проверяю время, сэр."
            )
        }

        // 8. Скриншот
        if (q.contains("скриншот") || q.contains("снимок экрана")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.screenshot", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Делаю скриншот экрана, сэр."
            )
        }

        // 8.1 Считывание контента экрана ("Джарвис, что на экране?", "Прочитай экран")
        if (q.contains("что на экране") || q.contains("прочитай экран") || q.contains("что написано") || q.contains("что тут написано") || q == "экран") {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "accessibility.screen_reader", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Считываю информацию с экрана, сэр."
            )
        }

        // 8.2 Нажатие на кнопки и UI навигация ("Нажми на Отправить", "Кликни Войти", "Прокрути вниз")
        if (q.startsWith("нажми на") || q.startsWith("кликни на") || q.startsWith("нажми") || q.startsWith("тапни по") || q.startsWith("кликни")) {
            val target = q.replace(Regex("^(нажми на|кликни на|нажми|тапни по|кликни)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        toolId = "accessibility.ui_click",
                        arguments = buildJsonObject {
                            put("action", "click")
                            put("target", target)
                        }
                    ),
                    immediateVoiceResponse = "Нажимаю на \"$target\", сэр."
                )
            }
        }
        if (q.contains("прокрути вниз") || q.contains("листай вниз") || q.contains("вниз страницу") || q == "скролл вниз") {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(
                    toolId = "accessibility.ui_click",
                    arguments = buildJsonObject { put("action", "scroll_down") }
                ),
                immediateVoiceResponse = "Прокручиваю вниз, сэр."
            )
        }
        if (q.contains("прокрути вверх") || q.contains("листай вверх") || q.contains("вверх страницу") || q == "скролл вверх") {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(
                    toolId = "accessibility.ui_click",
                    arguments = buildJsonObject { put("action", "scroll_up") }
                ),
                immediateVoiceResponse = "Прокручиваю вверх, сэр."
            )
        }

        // 9. Режим «Не беспокоить» (DND)
        if (q.contains("не беспокоить") || q.contains("режим тишины") || q.contains("без уведомлений")) {
            val isOff = q.contains("выключ") || q.contains("отключ")
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.dnd", arguments = buildJsonObject { put("enabled", !isOff) }),
                immediateVoiceResponse = if (!isOff) "Режим 'Не беспокоить' активирован, сэр." else "Режим 'Не беспокоить' отключен, сэр."
            )
        }

        // 10. Навигация и карты
        if (q.startsWith("навигатор в") || q.startsWith("маршрут в") || q.startsWith("поехали в") || q.startsWith("проложи маршрут до")) {
            val dest = q.replace(Regex("^(навигатор в|маршрут в|поехали в|проложи маршрут до)\\s*"), "").trim()
            if (dest.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(toolId = "location.navigation", arguments = buildJsonObject { put("destination", dest) }),
                    immediateVoiceResponse = "Прокладываю маршрут в $dest, сэр."
                )
            }
        }

        // 11. Телефонный звонок
        if (q.startsWith("позвони") || q.startsWith("набери") || q.startsWith("вызови")) {
            val contact = q.replace(Regex("^(позвони|набери|вызови)\\s*"), "").trim()
            if (contact.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(toolId = "communication.call", arguments = buildJsonObject { put("recipient", contact) }),
                    immediateVoiceResponse = "Набираю $contact, сэр."
                )
            }
        }

        // 12. Запуск приложений
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

        // 13. Bluetooth & Wi-Fi
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

        // 14. Информация об устройстве
        if (q.contains("модель") || q.contains("что за телефон") || q.contains("версия андроид") || q.contains("характеристики")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "system.device_info", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Получаю информацию об устройстве, сэр."
            )
        }

        return FastRouteResult.ForwardToLlm
    }
}
