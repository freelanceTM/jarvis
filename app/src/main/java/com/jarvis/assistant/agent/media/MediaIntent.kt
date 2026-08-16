package com.jarvis.assistant.agent.media

/**
 * Нормализованное медиа-намерение.
 *
 * Раньше фраза «включи музыку» превращалась в действие NEXT_TRACK, потому что
 * маппинг шёл напрямую «фраза → произвольное действие». Теперь между ними есть
 * явный слой намерения: фраза → MediaIntent → аргумент инструмента.
 */
enum class MediaIntent(val action: String) {
    PLAY_MEDIA("play"),
    PAUSE_MEDIA("pause"),
    TOGGLE_PLAY_PAUSE("play_pause"),
    NEXT_TRACK("next"),
    PREVIOUS_TRACK("previous"),
    STOP_MEDIA("stop")
}

/**
 * Разбор русских и английских формулировок в [MediaIntent].
 *
 * Правила намеренно упорядочены: более специфичные конструкции («следующий трек»)
 * проверяются раньше общих («включи»), иначе «включи следующий трек» уедет в PLAY.
 */
object MediaIntentParser {

    fun parse(rawPhrase: String): MediaIntent? {
        val q = rawPhrase.lowercase().trim()
        if (q.isEmpty()) return null

        // 1. Переключение вперёд
        if (NEXT_PATTERNS.any { q.contains(it) }) return MediaIntent.NEXT_TRACK

        // 2. Переключение назад
        if (PREV_PATTERNS.any { q.contains(it) }) return MediaIntent.PREVIOUS_TRACK

        // 3. Остановка (стоп «жёстче» паузы)
        if (STOP_PATTERNS.any { q.contains(it) }) return MediaIntent.STOP_MEDIA

        // 4. Пауза
        if (PAUSE_PATTERNS.any { q.contains(it) }) return MediaIntent.PAUSE_MEDIA

        // 5. Воспроизведение. «включи музыку» — это именно PLAY_MEDIA.
        if (PLAY_PATTERNS.any { q.contains(it) }) return MediaIntent.PLAY_MEDIA

        // 6. Общее переключение состояния
        if (TOGGLE_PATTERNS.any { q.contains(it) }) return MediaIntent.TOGGLE_PLAY_PAUSE

        return null
    }

    /**
     * Нормализация аргумента, пришедшего от LLM или из автоматизации,
     * в канонический action инструмента media.control.
     */
    fun normalizeAction(rawAction: String): MediaIntent? {
        val a = rawAction.lowercase().trim()
        MediaIntent.entries.firstOrNull { it.action == a }?.let { return it }
        return when (a) {
            "prev" -> MediaIntent.PREVIOUS_TRACK
            "playpause", "play-pause", "toggle" -> MediaIntent.TOGGLE_PLAY_PAUSE
            "resume", "continue" -> MediaIntent.PLAY_MEDIA
            else -> parse(a)
        }
    }

    private val NEXT_PATTERNS = listOf(
        "следующий трек", "следующая песня", "следующую песню", "следующий",
        "переключи вперед", "переключи вперёд", "перемотай вперед", "перемотай вперёд",
        "дальше", "след трек", "next track", "next song", "skip"
    )

    private val PREV_PATTERNS = listOf(
        "предыдущий трек", "предыдущая песня", "предыдущую песню", "предыдущий",
        "назад трек", "верни трек", "прошлый трек", "previous track", "previous song"
    )

    private val STOP_PATTERNS = listOf(
        "останови музыку", "выключи музыку", "выключить музыку", "стоп музыка",
        "музыка стоп", "прекрати играть", "stop music"
    )

    private val PAUSE_PATTERNS = listOf(
        "поставь на паузу", "на паузу", "пауза", "паузу", "приостанови",
        "притормози музыку", "pause"
    )

    private val PLAY_PATTERNS = listOf(
        "включи музыку", "включить музыку", "включи песню", "включи трек",
        "поставь музыку", "запусти музыку", "играй музыку", "играй",
        "продолжи воспроизведение", "продолжи музыку", "возобнови",
        "вруби музыку", "play music", "play"
    )

    private val TOGGLE_PATTERNS = listOf("плей", "воспроизведение", "play_pause")
}
