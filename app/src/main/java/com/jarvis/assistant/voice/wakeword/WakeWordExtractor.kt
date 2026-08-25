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
        return cleaned.ifBlank { null }
    }

    /** @return true, если строка начинается (с точностью до пунктуации/пробелов) с любого из wake-words. */
    fun containsWakeWord(
        text: String,
        wakeWords: List<String> = DEFAULT_WAKE_WORDS
    ): Boolean {
        val lower = text.lowercase().trim()
        // CR-02: prefix-only match — иначе ложные срабатывания на слова вроде
        // «погода» (содержит подстроку «жар») или «интернет» («джар»?).
        // Допускаем ведущую пунктуацию/междометия из STT («эй, джарвис...»).
        return wakeWords.any { kw ->
            Regex("^[\\p{Punct}\\s]*$kw", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    private fun strip(raw: String, wakeWords: List<String>): String {
        var result = raw
        for (kw in wakeWords) {
            // Удаляем wake-word в начале строки вместе с прилегающей пунктуацией
            // и пробелами («Джарвис, сколько времени» → «сколько времени»,
            // «Джарвис стоп» → «стоп»). Для совместимости с существующей логикой
            // допускаем любой не-greedy префикс перед kw (пыль из распознавателя),
            // как в старом cleanWakeWord().
            result = result.replace(
                Regex("(?i)^.*?$kw[,\\s\\p{Punct}]*"),
                ""
            ).trim()
        }
        return result
    }
}
