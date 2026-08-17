package com.jarvis.assistant.agent.translator

/**
 * Пресеты режимов синхронного переводчика (v0.2).
 *
 * Быстрые сценарии из коробки:
 *  - [AUTO]   — язык собеседника определяется автоматически, перевод на [targetCode];
 *  - [RU_TM]  — русский → туркменский;
 *  - [TM_RU]  — туркменский → русский;
 *  - [EN_RU]  — английский → русский;
 *  - [RU_EN]  — русский → английский.
 *
 * sourceCode == "auto" означает: распознавание без фиксированного языка,
 * язык фразы определяется по тексту (см. [TranslationLanguageDetector]),
 * а бэкенд перевода получает sourceLang="auto".
 *
 * Список расширяемый: новые пары добавляются значениями enum без изменения
 * конвейера (микрофон → STT → детекция → перевод → TTS → наушник).
 */
enum class InterpreterPreset(
    val id: String,
    val label: String,
    val sourceCode: String,
    val targetCode: String
) {
    AUTO("auto", "AUTO", "auto", "ru"),
    RU_TM("ru_tm", "RU → TM", "ru", "tk"),
    TM_RU("tm_ru", "TM → RU", "tk", "ru"),
    EN_RU("en_ru", "EN → RU", "en", "ru"),
    RU_EN("ru_en", "RU → EN", "ru", "en"),

    /** Ручной выбор пары языков в UI (вне быстрых пресетов). */
    CUSTOM("custom", "Ручной выбор", "ru", "en");

    companion object {
        /** Быстрые пресеты в порядке для UI (без ручного выбора). */
        val all: List<InterpreterPreset> = listOf(AUTO, RU_TM, TM_RU, EN_RU, RU_EN)

        /** Все значения, включая CUSTOM. */
        val allIncludingCustom: List<InterpreterPreset> = entries.toList()

        fun byId(id: String): InterpreterPreset? = allIncludingCustom.firstOrNull { it.id == id }
    }
}
