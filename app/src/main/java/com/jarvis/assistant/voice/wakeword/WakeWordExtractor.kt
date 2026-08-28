package com.jarvis.assistant.voice.wakeword

/**
 * Выделение пользовательского запроса из voice-строки, содержащей wake-word.
 *
 * CR-02: после удаления wake-word пустой результат НЕ должен возвращаться
 * как исходная строка — иначе «Джарвис» попадает в processUserQuery() и
 * провоцирует двойную обработку / паразитные AI-вызовы / неожиданные TTS.
 *
 * Ранее реализация была приватным методом `cleanWakeWord()` в
 * VoiceInteractionOrchestrator и возвращала `result.ifEmpty { raw }`, что
 * было корнем бага. Теперь:
 *  - `stripWakeWord(raw)` возвращает очищенный текст ИЛИ null, если после
 *    вырезания wake-word ничего не осталось (с учётом пунктуации);
 *  - список wake-words разделён на конфигурацию и логику, что удобно для
 *    тестирования.
 */
object WakeWordExtractor {

    /** Стандартные wake-words для русского/английского активации. */
    val DEFAULT_WAKE_WORDS: List<String> = listOf(
        "джарвис", "jarvis", "жарвис", "дарвис", "джей", "диджей", "джар"
    )

    /**
     * Удаляет wake-word из начала строки.
     *
     * @return очищенный запрос или null, если после вырезания wake-word
     *         осталась только пунктуация/пробелы (т.е. распознано ТОЛЬКО
     *         имя ассистента без команды).
     */
    fun extractQuery(
        raw: String,
        wakeWords: List<String> = DEFAULT_WAKE_WORDS
    ): String? {
        val cleaned = strip(raw, wakeWords)
        // CR-02: если после вырезания wake-word остались только пунктуация и
        // пробелы — команды нет: null вместо исходной строки/мусора.
        return cleaned.takeUnless { noiseOnly.matches(it) }
    }

    /**
     * @return true, если строка начинается (с точностью до пунктуации/пробелов
     * и одного короткого междометия из STT вроде «эй, джарвис») с любого из
     * wake-words.
     */
    fun containsWakeWord(
        text: String,
        wakeWords: List<String> = DEFAULT_WAKE_WORDS
    ): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return false
        val alternatives = wakeWords.joinToString("|") { Regex.escape(it) }
        // CR-02: prefix-only match — иначе ложные срабатывания на слова вроде
        // «жарко» (содержит «жар») или wake-word в середине фразы
        // («привет Джарвис…»). Междометие-допуск ограничен 1–2 буквами: «эй»,
        // «ну», «а» — реальные перекрикивания распознавателя; длинные слова
        // («привет», «как») междометиями не считаются и не активируют ассистента.
        return Regex("^$NOISE*(?:[a-zа-яё]{1,2}$NOISE+)?(?:$alternatives)")
            .containsMatchIn(lower)
    }

    private fun strip(raw: String, wakeWords: List<String>): String {
        var result = raw
        for (kw in wakeWords) {
            // Удаляем wake-word в начале строки вместе с прилегающей пунктуацией
            // и пробелами («Джарвис, сколько времени» → «сколько времени»,
            // «Джарвис стоп» → «стоп»). Для совместимости с существующей логикой
            // допускаем любой не-greedy префикс перед kw (пыль из распознавателя:
            // «привет Джарвис …»), как в старом cleanWakeWord().
            // (?iu): ASCII-only (?i) не сворачивает регистр кириллицы, из-за
            // чего «Жарвис»/«Дарвис»/«Джей» вообще не распознавались.
            result = result
                .replace(Regex("(?iu)^.*?${Regex.escape(kw)}$NOISE*"), "")
                .trim()
        }
        return result
    }

    private companion object {
        /**
         * Шум вокруг wake-word после STT: пробелы и пунктуация, включая
         * Unicode-тире и кавычки, которые не покрывает ASCII-класс `\p{Punct}`
         * (баг: «Джарвис — сколько времени» оставлял «—» в запросе).
         */
        private const val NOISE = "[\\s\\p{Punct}—–…«»„“”‘’]"

        private val noiseOnly = Regex("^$NOISE*$")
    }
}
