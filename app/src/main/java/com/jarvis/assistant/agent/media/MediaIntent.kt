package com.jarvis.assistant.agent.media

/**
 * Нормализованное медиа-намерение.
 *
 * Полная модель intent v0.2:
 *
 *   PLAY         «включи музыку»
 *   PAUSE        «поставь на паузу»
 *   RESUME       «продолжи» (после паузы — в отличие от PLAY не «запустить заново»)
 *   NEXT         «следующий трек»
 *   PREVIOUS     «предыдущий трек»
 *   STOP         «выключи музыку»
 *   TOGGLE       «плей/пауза» (переключение состояния)
 *   VOLUME_UP    «сделай музыку громче»
 *   VOLUME_DOWN  «сделай музыку тише»
 *
 * Раньше фраза «включи музыку» могла превращаться в NEXT_TRACK, потому что
 * маппинг шёл напрямую «фраза → произвольное действие». Теперь между ними
 * явный слой намерения: фраза → MediaIntent → аргумент инструмента.
 * Каждый интент маппится на конкретное действие без «угадывания».
 */
enum class MediaIntent(val action: String) {
    PLAY_MEDIA("play"),
    PAUSE_MEDIA("pause"),
    RESUME_MEDIA("resume"),
    TOGGLE_PLAY_PAUSE("play_pause"),
    NEXT_TRACK("next"),
    PREVIOUS_TRACK("previous"),
    STOP_MEDIA("stop"),
    VOLUME_UP("volume_up"),
    VOLUME_DOWN("volume_down")
}

/**
 * Разбор русских и английских формулировок в [MediaIntent].
 *
 * Правила намеренно упорядочены: более специфичные конструкции («следующий
 * трек») проверяются раньше общих («включи»), иначе «включи следующий трек»
 * уедет в PLAY. Громкость распознаётся только в медиа-контексте
 * («сделай музыку громче») — общее «сделай громче» обрабатывается
 * отдельным тулом громкости.
 */
object MediaIntentParser {

    fun parse(rawPhrase: String): MediaIntent? {
        val q = rawPhrase.lowercase().trim()
        if (q.isEmpty()) return null

        // 1. Переключение вперёд (самое специфичное). Одиночные слова
        // «следующий/дальше» допустимы только как вся команда: иначе вопрос
        // «когда следующий матч?» неожиданно переключал музыку.
        if (NEXT_PATTERNS.any { q.contains(it) } || q in NEXT_STANDALONE) {
            return MediaIntent.NEXT_TRACK
        }

        // 2. Переключение назад — то же правило для «предыдущий».
        if (PREV_PATTERNS.any { q.contains(it) } || q in PREV_STANDALONE) {
            return MediaIntent.PREVIOUS_TRACK
        }

        // 3. Остановка (стоп «жёстче» паузы)
        if (STOP_PATTERNS.any { q.contains(it) }) return MediaIntent.STOP_MEDIA

        // 4. Продолжить после паузы (RESUME — отдельный интент от PLAY;
        //    проверяется раньше PAUSE, чтобы «продолжи после паузы» не уехало в паузу)
        if (RESUME_PATTERNS.any { q.contains(it) } || q in RESUME_STANDALONE) {
            return MediaIntent.RESUME_MEDIA
        }

        // 5. Пауза
        if (PAUSE_PATTERNS.any { q.contains(it) } || q in PAUSE_STANDALONE) {
            return MediaIntent.PAUSE_MEDIA
        }

        // 6. Громкость медиа (только при медиа-контексте: музык/трек/песн/плеер)
        if (hasMediaContext(q)) {
            if (VOLUME_UP_PATTERNS.any { q.contains(it) }) return MediaIntent.VOLUME_UP
            if (VOLUME_DOWN_PATTERNS.any { q.contains(it) }) return MediaIntent.VOLUME_DOWN
        }

        // 7. Воспроизведение. «включи музыку» — это именно PLAY_MEDIA.
        if (PLAY_PATTERNS.any { q.contains(it) } || q in PLAY_STANDALONE) {
            return MediaIntent.PLAY_MEDIA
        }

        // 8. Общее переключение состояния
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
            "resume", "continue" -> MediaIntent.RESUME_MEDIA
            "up", "volume-up" -> MediaIntent.VOLUME_UP
            "down", "volume-down" -> MediaIntent.VOLUME_DOWN
            else -> parse(a)
        }
    }

    private fun hasMediaContext(q: String): Boolean =
        MEDIA_CONTEXT_WORDS.any { q.contains(it) }

    private val MEDIA_CONTEXT_WORDS = listOf(
        "музык", "трек", "песн", "плеер", "мелоди", "звук", "music", "song", "track", "audio"
    )

    private val NEXT_PATTERNS = listOf(
        "следующий трек", "следующая песня", "следующую песню",
        "переключи вперед", "переключи вперёд", "перемотай вперед", "перемотай вперёд",
        "след трек", "next track", "next song", "skip track"
    )
    private val NEXT_STANDALONE = setOf("следующий", "дальше", "next", "skip")

    private val PREV_PATTERNS = listOf(
        "предыдущий трек", "предыдущая песня", "предыдущую песню",
        "назад трек", "верни трек", "прошлый трек", "previous track", "previous song"
    )
    private val PREV_STANDALONE = setOf("предыдущий", "назад", "previous")

    private val STOP_PATTERNS = listOf(
        "останови музыку", "выключи музыку", "выключить музыку", "стоп музыка",
        "музыка стоп", "прекрати играть", "stop music"
    )

    private val PAUSE_PATTERNS = listOf(
        "поставь на паузу", "на паузу", "приостанови музыку", "приостанови воспроизведение",
        "притормози музыку", "pause music"
    )
    private val PAUSE_STANDALONE = setOf("пауза", "паузу", "приостанови", "pause")

    private val RESUME_PATTERNS = listOf(
        "продолжи музыку", "продолжи воспроизведение", "продолжи после паузы", "продолжай музыку",
        "возобнови музыку", "возобнови воспроизведение", "continue music", "resume music"
    )
    private val RESUME_STANDALONE = setOf("продолжи", "продолжай", "continue", "resume")

    private val VOLUME_UP_PATTERNS = listOf(
        "громче", "прибавь звук", "прибавь громкость", "сделай громче",
        "увеличь громкость", "louder", "volume up"
    )

    private val VOLUME_DOWN_PATTERNS = listOf(
        "тише", "убавь звук", "убавь громкость", "сделай тише",
        "уменьши громкость", "quieter", "volume down"
    )

    private val PLAY_PATTERNS = listOf(
        "включи музыку", "включить музыку", "включи песню", "включи трек",
        "поставь музыку", "запусти музыку", "играй музыку",
        "вруби музыку", "play music"
    )
    private val PLAY_STANDALONE = setOf("играй", "play")

    private val TOGGLE_PATTERNS = listOf("плей", "воспроизведение", "play_pause")
}
