package com.jarvis.server.privacy

import com.jarvis.server.api.ApiPrivacyLevel
import java.text.Normalizer

/** Server-side fail-safe for clients that incorrectly label secret text NORMAL. */
object PromptPrivacyClassifier {
    private val sensitivePatterns = listOf(
        Regex(
            """\b(?:мой|моя|моё|мои|моего|моей|my)\s+""" +
                """(?:парол\p{L}*|пин-код|пинкод|пин|pin-код|pinкод|pin|password|cvv|cvc|""" +
                """seed\s+phrase|мнемоническ\p{L}*\s+фраз\p{L}*|""" +
                """приватн\p{L}*\s+ключ\p{L}*|private\s+key|api\s+key|токен\p{L}*|""" +
                """диагноз\p{L}*|медицинск\p{L}*\s+(?:карт\p{L}*|данн\p{L}*)|""" +
                """результат\p{L}*\s+анализ\p{L}*)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:парол\p{L}*|пин-код|пинкод|пин|pin-код|pinкод|pin|password|cvv|cvc|""" +
                """seed\s+phrase|мнемоническ\p{L}*\s+фраз\p{L}*|""" +
                """приватн\p{L}*\s+ключ\p{L}*|private\s+key|api\s+key|токен\p{L}*)""" +
                """\s*(?::|=|—|\bэто\b|\bis\b)\s*\S+""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:код\s+из\s+(?:смс|sms)|одноразов\p{L}*\s+код\p{L}*|otp)\D{0,12}\d{4,8}\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:номер\s+(?:моей\s+)?карт\p{L}*|card\s+number)\D{0,12}(?:\d[ -]?){13,19}\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b""", RegexOption.IGNORE_CASE)
    )

    private val privatePatterns = listOf(
        Regex(
            """\b(?:мой|моя|моё|мои|моего|моей|my)\s+""" +
                """(?:домашн\p{L}*\s+адрес\p{L}*|личн\p{L}*\s+переписк\p{L}*|""" +
                """паспорт\p{L}*|паспортн\p{L}*\s+данн\p{L}*|номер\s+телефон\p{L}*)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:данн\p{L}*\s+(?:моего|моей)\s+паспорта|прочитай\s+мою\s+личную\s+переписку)\b""",
            RegexOption.IGNORE_CASE
        )
    )

    fun classify(text: String): ApiPrivacyLevel {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isEmpty()) return ApiPrivacyLevel.NORMAL
        if (sensitivePatterns.any { it.containsMatchIn(normalized) }) return ApiPrivacyLevel.SENSITIVE
        if (privatePatterns.any { it.containsMatchIn(normalized) }) return ApiPrivacyLevel.PRIVATE
        return ApiPrivacyLevel.NORMAL
    }

    fun strongest(explicit: ApiPrivacyLevel, detected: ApiPrivacyLevel): ApiPrivacyLevel =
        if (explicit.ordinal >= detected.ordinal) explicit else detected
}
