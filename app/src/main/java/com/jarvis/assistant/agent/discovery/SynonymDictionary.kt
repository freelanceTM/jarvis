package com.jarvis.assistant.agent.discovery

object SynonymDictionary {

    // Ключ: корень/стем слова → список синонимов (тоже стемы на русском и английском)
    private val synonymGroups: List<Set<String>> = listOf(
        // Действия включения / запуск
        setOf("включ", "вруби", "вруб", "зажг", "зажж", "активир", "запуст", "открой", "откр", "старт", "постав", "turn on", "enable", "open", "start", "launch"),
        
        // Действия выключения / остановка
        setOf("выключ", "выруби", "выруб", "погаси", "потуш", "деактивир", "закрой", "стоп", "отключ", "turn off", "disable", "close", "stop"),

        // Фонарик / Свет / Лампочка
        setOf("фонар", "фонарик", "свет", "посвет", "свети", "вспышк", "подсвет", "лампочк", "лампа", "фара", "torch", "flashlight", "flash", "light", "lamp"),

        // Громкость / Звук
        setOf("громк", "звук", "тише", "тиш", "потише", "громче", "погромче", "децибел", "volume", "sound", "audio", "loud", "quiet", "mute", "unmute"),
        setOf("прибав", "увелич", "повыс", "добав", "подним", "increase", "up", "raise"),
        setOf("убав", "уменьш", "пониз", "снизь", "сниж", "decrease", "down", "lower"),

        // Батарея / Заряд / Энергия
        setOf("батаре", "заряд", "аккумулят", "аккум", "процент", "энерг", "battery", "charge", "power", "percent", "level"),

        // Время / Дата / Часы
        setOf("врем", "час", "минут", "дат", "числ", "день", "сегодн", "time", "date", "clock", "today", "hour"),

        // Звонки / Набор номера
        setOf("звон", "позвон", "вызов", "набер", "набир", "call", "dial", "phone"),

        // Контакты / Телефонная книга / Близкие
        setOf("контакт", "номер", "телефон", "абонент", "мама", "маме", "папа", "папе", "брат", "сестр", "друг", "контакты", "книга", "contact", "contacts", "book"),

        // Сообщения / SMS / Мессенджеры
        setOf("сообщен", "смс", "sms", "напиш", "отправ", "текст", "месседж", "message", "send", "text", "msg"),

        // Приложения / Софт
        setOf("приложен", "прог", "app", "программ", "запуст", "откр", "application", "software"),

        // Bluetooth
        setOf("блютуз", "bluetooth", "bt", "блютус", "беспровод", "wireless"),

        // Wi-Fi / Сеть / Интернет
        setOf("wifi", "вайфай", "интернет", "сеть", "wi-fi", "internet", "network", "connection"),

        // Яркость / Экран / Дисплей
        setOf("яркост", "экран", "подсветк", "дисплей", "brightness", "screen", "display"),

        // Музыка / Медиа / Треки
        setOf("музык", "трек", "песн", "плеер", "воспроизвед", "мелод", "аудио", "music", "song", "track", "player", "play"),
        setOf("пауз", "останов", "стоп", "приостанов", "pause", "hold"),
        setOf("следующ", "дальш", "перемот", "скип", "next", "skip", "forward"),
        setOf("предыдущ", "назад", "верн", "previous", "prev", "back"),

        // Навигация / Карты / Маршрут
        setOf("навигац", "маршрут", "карт", "дорог", "путь", "адрес", "ехать", "navigation", "map", "route", "drive", "navigate"),

        // Будильник / Таймер / Напоминания
        setOf("будильник", "таймер", "напомин", "alarm", "напомн", "разбуд", "timer", "remind", "wake"),

        // Скриншот / Снимок экрана
        setOf("скриншот", "снимок", "экран", "захват", "screenshot", "capture", "snapshot"),

        // Режим «Не беспокоить» / Тишина
        setOf("беспоко", "тишин", "уведомлен", "dnd", "бесшумн", "silent", "quiet", "do not disturb"),

        // Память / Факты / Запоминание
        setOf("запомн", "помн", "сохран", "факт", "знай", "remember", "save", "fact"),
        setOf("забуд", "удал", "сотр", "очист", "forget", "delete", "erase", "clear"),
        setOf("вспомн", "напомн", "что я", "recall", "memory"),

        // Поиск / Веб / Google
        setOf("найд", "поиск", "погугл", "search", "информац", "гугл", "google", "find", "lookup"),

        // Буфер обмена
        setOf("буфер", "копир", "скопир", "вставь", "clipboard", "copy", "paste"),

        // Календарь / События
        setOf("календар", "событ", "встреч", "расписан", "план", "calendar", "event", "schedule", "meeting"),

        // Поделиться / Share
        setOf("подел", "отправ", "переш", "share", "расшар")
    )

    // Быстрый индекс: стем → номер группы
    private val stemToGroup: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        synonymGroups.forEachIndexed { index, group ->
            group.forEach { stem -> map[stem.lowercase()] = index }
        }
        map
    }

    /**
     * Возвращает все синонимы для данного слова (по стемам)
     */
    fun getSynonyms(word: String): Set<String> {
        val w = word.lowercase().trim()
        if (w.length < 3) return emptySet()

        // 1. Поиск по префиксу слова (от длинного к короткому)
        for (len in (w.length downTo 3)) {
            val stem = w.take(len)
            val groupIdx = stemToGroup[stem]
            if (groupIdx != null) {
                return synonymGroups[groupIdx]
            }
        }

        // 2. Поиск по вхождению любого известного корня внутри слова
        for ((stem, groupIdx) in stemToGroup) {
            if (stem.length >= 3 && w.contains(stem)) {
                return synonymGroups[groupIdx]
            }
        }

        return emptySet()
    }

    /**
     * Проверяет, являются ли два слова синонимами
     */
    fun areSynonyms(word1: String, word2: String): Boolean {
        val syns = getSynonyms(word1)
        if (syns.isEmpty()) return false
        val w2 = word2.lowercase()
        return syns.any { stem -> w2.startsWith(stem) || w2.contains(stem) }
    }
}
