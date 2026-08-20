package com.jarvis.assistant.agent.decision

import java.text.Normalizer

/**
 * Консервативная локальная классификация текста до любого cloud-вызова.
 *
 * Классификатор намеренно распознаёт только сильные признаки: владение
 * credential/medical data, значение секрета, номер карты и личные документы.
 * Общие вопросы вроде «как сменить пароль» остаются NORMAL. False negative
 * дополнительно страхуется явным [ExecutionRequest.privacyLevel] вызывающего.
 */
object PrivacyClassifier {
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

    fun classify(text: String): PrivacyLevel {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isEmpty()) return PrivacyLevel.NORMAL
        if (sensitivePatterns.any { it.containsMatchIn(normalized) }) return PrivacyLevel.SENSITIVE
        if (privatePatterns.any { it.containsMatchIn(normalized) }) return PrivacyLevel.PRIVATE
        return PrivacyLevel.NORMAL
    }

    fun strongest(explicit: PrivacyLevel, detected: PrivacyLevel): PrivacyLevel =
        if (explicit.ordinal >= detected.ordinal) explicit else detected
}
