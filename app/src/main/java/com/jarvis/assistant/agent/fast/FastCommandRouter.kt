package com.jarvis.assistant.agent.fast

import com.jarvis.assistant.agent.media.MediaIntent
import com.jarvis.assistant.agent.media.MediaIntentParser
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
                    "media.control" -> buildJsonObject { put("action", MediaIntent.PLAY_MEDIA.action) }
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
        if (q in listOf("привет", "здравствуй", "добрый день", "добрый вечер", "хай", "салам")) {
            return FastRouteResult.HandledLocally(immediateVoiceResponse = "Приветствую, сэр. Я готов к работе.")
        }

        // 2.1 Персональный аудио-брифинг в ухо ("Джарвис, брифинг", "Утренняя сводка", "Что нового")
        if (q.contains("брифинг") || q.contains("сводка") || q.contains("что нового") || q == "утренний брифинг" || q == "вечерний брифинг") {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "productivity.ear_briefing", arguments = JsonObject(emptyMap())),
                immediateVoiceResponse = "Формирую персональный брифинг, сэр."
            )
        }

        // 2.2 Мгновенный голосовой перевод в ухо ("Джарвис, переведи на английский...", "Как переводится...")
        if (q.startsWith("переведи") || q.startsWith("как переводится") || q.startsWith("переводчик") || q.contains("режим переводчика")) {
            val targetLang = when {
                q.contains("на английск") || q.contains("по-английски") || q.contains("in english") -> "en"
                q.contains("на туркменск") || q.contains("по-туркменски") || q.contains("türkmen") -> "tk"
                q.contains("на турецк") || q.contains("по-турецки") || q.contains("türkçe") -> "tr"
                q.contains("на немецк") || q.contains("по-немецки") || q.contains("auf deutsch") -> "de"
                q.contains("на китайск") || q.contains("по-китайски") -> "zh"
                q.contains("на арабск") || q.contains("по-арабски") -> "ar"
                else -> "ru"
            }

            val textToTranslate = q.replace(Regex("^(переведи на [а-яa-z]+|переведи фразу|переведи|как переводится|переводчик)\\s*"), "").trim()
            if (textToTranslate.isNotEmpty()) {
                return FastRouteResult.HandledLocally(
                    toolCall = ToolCall(
                        toolId = "intelligence.translate",
                        arguments = buildJsonObject {
                            put("text", textToTranslate)
                            put("target_lang", targetLang)
                        }
                    ),
                    immediateVoiceResponse = "Перевожу, сэр."
                )
            }
        }

        // 3. Управление медиа и музыкой (через нормализацию намерения)
        // Важно: "включи музыку" -> PLAY_MEDIA, а не NEXT_TRACK.
        MediaIntentParser.parse(q)?.let { mediaIntent ->
            val voice = when (mediaIntent) {
                MediaIntent.PLAY_MEDIA -> "Включаю музыку, сэр."
                MediaIntent.PAUSE_MEDIA -> "Музыка поставлена на паузу, сэр."
                MediaIntent.NEXT_TRACK -> "Включаю следующий трек, сэр."
                MediaIntent.PREVIOUS_TRACK -> "Переключаю назад, сэр."
                MediaIntent.STOP_MEDIA -> "Останавливаю воспроизведение, сэр."
                MediaIntent.TOGGLE_PLAY_PAUSE -> "Переключаю воспроизведение, сэр."
            }
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(
                    toolId = "media.control",
                    arguments = buildJsonObject { put("action", mediaIntent.action) }
                ),
                immediateVoiceResponse = voice
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

        // 5.1 Яркость экрана
        // Алгоритм (в туле): canWrite() → YES: реально изменить яркость;
        // NO: открыть системный экран ACTION_MANAGE_WRITE_SETTINGS.
        // Здесь определяем намерение: «до N» — абсолютный percent,
        // «на N» — относительный delta, без числа — глаголы ±10 / чтение.
        if (q.contains("яркост") || q.contains("ярче") || q.contains("темне") || q.contains("brightness")) {
            val percentMatch = Regex("""(\d+)\s*(%|процент)""").find(q)
            val numberMatch = Regex("""(\d+)""").find(q)
            val number = percentMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: numberMatch?.groupValues?.get(1)?.toIntOrNull()

            val isDecrease = q.contains("меньш") || q.contains("пониз") ||
                q.contains("убав") || q.contains("темне") || q.contains("тускл")

            val arguments = when {
                // «на максимум / на всю» → 100, «на минимум» → 0
                q.contains("максимум") || q.contains("на всю") || q.contains("по максимум") ->
                    buildJsonObject { put("percent", 100) }
                q.contains("минимум") || q.contains("по минимум") || q.contains("на минимальн") ->
                    buildJsonObject { put("percent", 0) }

                // «увеличь/уменьши яркость НА N» → относительное смещение
                number != null && q.contains(" на ") ->
                    buildJsonObject { put("delta", if (isDecrease) -number else number) }

                // «яркость до N» / «яркость N процентов» → абсолютное значение
                number != null ->
                    buildJsonObject { put("percent", number) }

                // Глаголы без числа: ярче/темнее ±10
                isDecrease -> buildJsonObject { put("delta", -10) }
                q.contains("ярче") || q.contains("увелич") || q.contains("прибав") ->
                    buildJsonObject { put("delta", 10) }

                // Просто «какая яркость» — чтение текущего значения.
                else -> buildJsonObject { }
            }

            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "device.brightness", arguments = arguments),
                immediateVoiceResponse = "Настраиваю яркость, сэр."
            )
        }

        // 5.2 Погода
        // Честный флоу v0.2 (никакого зашитого города):
        //   «Какая погода?»          → LocationProvider (GPS) → WeatherProvider
        //   «Погода в Берлине?»      → Geocoder → WeatherProvider
        // Город после «в/во» передаётся параметром location; без города —
        // пустые аргументы, тул сам определит местоположение.
        if ((q.contains("погод") && !q.contains("погоди")) || q.contains("weather")) {
            // \p{L} — любые буквы (кириллица + латиница), \w матчит только ASCII.
            val cityMatch = Regex("""погод\p{L}*\s+(?:в|во)\s+([\p{L}\-]{2,40})""").find(q)
            val city = cityMatch?.groupValues?.get(1)?.trim()
                ?.let(::normalizeCityName)
                ?.takeIf { it.isNotBlank() && it != "На" }

            val arguments = if (city != null && !isCurrentLocationKeyword(city)) {
                buildJsonObject { put("location", city) }
            } else {
                buildJsonObject { }
            }

            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(toolId = "intelligence.weather", arguments = arguments),
                immediateVoiceResponse = "Запрашиваю погоду, сэр."
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
        // Фразы про Bluetooth/Wi-Fi сюда не попадают: «открой настройки
        // блютуз» должно открыть системный экран Bluetooth (секция 13),
        // а не приложение «Настройки».
        if (!q.contains("блютуз") && !q.contains("bluetooth") &&
            !q.contains("вайфай") && !q.contains("wifi") && !q.contains("wi-fi") &&
            (q.startsWith("открой") || q.startsWith("запусти") || q.startsWith("включи") ||
                q.startsWith("перейди в") || q.startsWith("вруби"))
        ) {
            val app = when {
                q.contains("телеграм") || q.contains("telegram") || q.contains("телегу") || q.contains("тг") || q.contains("tg") -> "telegram"
                q.contains("ютуб") || q.contains("youtube") -> "youtube"
                q.contains("ватсап") || q.contains("whatsapp") || q.contains("вацап") -> "whatsapp"
                q.contains("камер") || q.contains("camera") || q.contains("фотк") -> "camera"
                q.contains("хром") || q.contains("chrome") || q.contains("браузер") -> "chrome"
                q.contains("спотифай") || q.contains("spotify") || q.contains("плеер") -> "spotify"
                q.contains("настройк") -> "settings"
                q.contains("калькулятор") -> "calculator"
                q.contains("карт") || q.contains("maps") || q.contains("навигатор") -> "maps"
                else -> ""
            }

            if (app.isNotEmpty()) {
                // «Открой YouTube и найди UFC» — это НЕ одиночный запуск приложения:
                // уходим в планировщик, который построит UI-цепочку
                // (открыть → поле поиска → ввод → VERIFY результата).
                if (q.contains("найди") || q.contains("поищи") || q.contains("ищи") || q.contains("найти")) {
                    return FastRouteResult.ForwardToLlm
                }

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
        // Честный UX: JARVIS не переключает Bluetooth/Wi-Fi сам (Android 10+
        // и 13+ запрещают приложениям это делать), а сообщает текущее состояние
        // и открывает системный экран, где переключение выполняет пользователь.
        // Роутер передаёт тулу намерение (enable/disable/toggle/status) —
        // итоговую фразу формирует тул по реальному результату.
        if (q.contains("блютуз") || q.contains("bluetooth")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(
                    toolId = "device.bluetooth",
                    arguments = buildJsonObject { put("action", resolveToggleIntent(q)) }
                ),
                immediateVoiceResponse = "Проверяю Bluetooth, сэр."
            )
        }
        if (q.contains("вайфай") || q.contains("wifi") || q.contains("wi-fi") || q.contains("интернет")) {
            return FastRouteResult.HandledLocally(
                toolCall = ToolCall(
                    toolId = "device.wifi",
                    arguments = buildJsonObject { put("action", resolveToggleIntent(q)) }
                ),
                immediateVoiceResponse = "Проверяю Wi-Fi, сэр."
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

    /**
     * Определяет намерение пользователя по глаголам: включить / выключить /
     * переключить. Без глагола — запрос статуса.
     *
     * Проверка «выключить» идёт раньше «включить»: обе группы слов содержат
     * корень «ключ», но «вы…»-формы не должны попадать в enable.
     */
    private fun resolveToggleIntent(query: String): String = when {
        query.contains("выключи") || query.contains("выключить") ||
            query.contains("отключи") || query.contains("отключить") ||
            query.contains("выруби") || query.contains("выключай") -> "disable"
        query.contains("включи") || query.contains("включить") ||
            query.contains("вруби") || query.contains("включай") -> "enable"
        query.contains("переключи") || query.contains("переключить") -> "toggle"
        else -> "status"
    }

    /** Слова «здесь/тут/рядом» — не город, а указание на текущее местоположение. */
    private fun isCurrentLocationKeyword(value: String): Boolean {
        val v = value.lowercase()
        return v in setOf(
            "current_location", "current", "здесь", "тут", "рядом",
            "текущее местоположение", "мое местоположение", "моё местоположение"
        )
    }

    /**
     * Лёгкая нормализация названия города из падежной формы в именительную:
     * «в Берлине» → «Берлин», «в Ашхабаде» → «Ашхабад».
     *
     * Безопасные ограничения: убираем конечное «е» только если перед ним
     * согласная (не «в»/«й» и не гласная): «Москве» и «Дубае» не трогаем —
     * геокодер разберётся по неточному совпадению.
     */
    private fun normalizeCityName(raw: String): String {
        var city = raw.replaceFirstChar { it.uppercase() }
        if (city.length > 4 && city.endsWith("е")) {
            val prev = city[city.length - 2].lowercaseChar()
            if (prev.isLetter() && prev !in "вйаеёиоуыэюя") {
                city = city.dropLast(1)
            }
        }
        return city
    }
}
