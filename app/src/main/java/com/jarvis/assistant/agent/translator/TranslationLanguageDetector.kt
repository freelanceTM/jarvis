package com.jarvis.assistant.agent.translator

/**
 * Эвристическое определение языка фразы по письменности (Language Detection).
 *
 * Используется в режиме AUTO синхронного переводчика, когда язык собеседника
 * заранее неизвестен. Это ЧЕСТНАЯ эвристика, а не ML-модель:
 *
 *  - кириллица          → русский (ru);
 *  - иероглифы (CJK)    → китайский (zh);
 *  - арабская вязь      → арабский (ar);
 *  - латиница           → по маркерам письма:
 *                         ň/ý/ž/ä → туркменский (tk),
 *                         ğ/ı/ç/ş → турецкий (tr),
 *                         ß (или ö/ü без тюркских маркеров) → немецкий (de),
 *                         иначе → английский (en).
 *
 * Точность намеренно не рекламируется как 100%: для спорных латинских текстов
 * возвращается "en" как нейтральный дефолт. Бэкенд перевода в режиме AUTO
 * получает sourceLang="auto" и определяет язык сам — детектор нужен только
 * для отображения определённого языка в UI.
 */
object TranslationLanguageDetector {

    /** @return код языка (ru/en/tk/tr/de/zh/ar) или null, если текст пуст. */
    fun detect(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null

        var cyrillic = 0
        var latin = 0
        var cjk = 0
        var arabic = 0
        var totalLetters = 0

        for (ch in t) {
            when {
                ch in '\u0400'..'\u04FF' -> { cyrillic++; totalLetters++ }
                ch in 'a'..'z' || ch in 'A'..'Z' -> { latin++; totalLetters++ }
                ch in '\u4E00'..'\u9FFF' || ch in '\u3040'..'\u30FF' || ch in '\uAC00'..'\uD7AF' -> { cjk++; totalLetters++ }
                ch in '\u0600'..'\u06FF' -> { arabic++; totalLetters++ }
            }
        }

        if (totalLetters == 0) return null

        return when {
            // Кириллица доминирует (≥30% букв) → русский.
            cyrillic > 0 && cyrillic * 100 / totalLetters >= 30 -> "ru"
            cjk > 0 -> "zh"
            arabic > 0 -> "ar"
            latin > 0 -> detectLatin(t)
            else -> null
        }
    }

    private fun detectLatin(text: String): String {
        val lower = text.lowercase()

        // Специфичные туркменские буквы (ň, ý, ž) + ä (туркменский алфавит,
        // в турецком не используется).
        val tkMarkers = lower.count { it in "ňýžä" }
        // Специфичные турецкие буквы (ğ, ı) + общие тюркские ç/ş.
        val trMarkers = lower.count { it in "ğışç" }
        // Немецкий: ß — уникален; ö/ü считаем немецкими, только если нет
        // никаких тюркских маркеров (иначе это скорее tk/tr).
        val deMarkers = lower.count { it == 'ß' } +
            if (lower.any { it in "öü" } && tkMarkers == 0 && trMarkers == 0) 1 else 0

        return when {
            tkMarkers > 0 -> "tk"
            trMarkers > 0 -> "tr"
            deMarkers > 0 -> "de"
            else -> "en"
        }
    }

    /** Человекочитаемое имя определённого языка. */
    fun displayName(code: String): String = TranslationLanguages.displayName(code)
}
