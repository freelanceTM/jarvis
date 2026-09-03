package com.jarvis.assistant.agent.tools.accessibility

/**
 * Контентный санитайзер текста узлов экрана — третий слой Accessibility
 * Lockdown (после пакетной политики и пропуска парольных полей).
 *
 * Задача: 2FA-коды и картоподобные номера, которые появляются ВНУТРИ обычных
 * приложений (код входа в мессенджере, номер карты в заметке/чеке), не должны
 * уйти в LLM даже когда пакет разрешён. Правила консервативны в сторону
 * маскировки: ложное срабатывание стоит одну «••••» в summary, пропуск — утечку.
 *
 * Что НЕ маскируется (проверено тестами): время «18:00», суммы «50 000»,
 * годы «2020», номера телефонов «+7 999 100 20 30» (группы < 5 цифр),
 * десятичные дроби.
 */
object ScreenTextSanitizer {

    /** Маска, подставляемая вместо чувствительного значения. */
    const val MASK = "••••"

    private const val MAX_CODE_LENGTH = 8
    private const val MIN_CODE_LENGTH = 4
    private const val MIN_CARD_DIGITS = 13
    private const val MAX_CARD_DIGITS = 19

    /**
     * Код-контекст в том же узле: рядом со словом «код/code/otp/2fa/пароль…»
     * короткие буквенно-цифровые токены маскируются (Steam Guard «RXT4Q»,
     * «код: 4821»).
     */
    private val CODE_CONTEXT = Regex(
        "(код|кодовое|code|otp|2fa|пароль|password|подтвержд\\w*|verif\\w*|authenticat\\w*|pin)",
        RegexOption.IGNORE_CASE
    )

    /** Токен: последовательность букв/цифр (буквы/цифры/разделители не внутри). */
    private val TOKEN = Regex("[A-Za-z0-9]+")

    /** Чисто цифровой токен 4–8 знаков в код-контексте ИЛИ 6–8 без контекста. */
    private fun isSensitiveToken(token: String, hasCodeContext: Boolean): Boolean {
        if (!token.any { it.isDigit() }) return false
        return when {
            token.length < MIN_CODE_LENGTH || token.length > MAX_CODE_LENGTH -> false
            token.length >= 6 -> true // 6–8 цифр/цифро-букв: OTP-подобное всегда
            hasCodeContext -> true // 4–5 знаков только рядом со словом «код»
            else -> false
        }
    }

    /** Картоподобные группы: 13–19 цифр через пробелы/дефисы («4276 1600 1234 5678»). */
    private val CARD_LIKE = Regex(
        "\\b\\d[\\d-]{${MIN_CARD_DIGITS - 1},${MAX_CARD_DIGITS - 1}}([ ]?\\d{3,4}){1,3}\\b"
    )

    /**
     * Маскирует чувствительные значения в тексте одного узла экрана.
     *
     * @return пара (безопасный текст, количество замаскированных значений).
     */
    fun sanitize(text: String): Pair<String, Int> {
        var masked = 0
        var result = text

        // 1. Картоподобные последовательности (до токенизации: у них свои разделители).
        result = CARD_LIKE.replace(result) {
            masked++
            MASK
        }

        // 2. Коды/OTP внутри текущего узла.
        val hasCodeContext = CODE_CONTEXT.containsMatchIn(result)
        if (hasCodeContext || result.any { it.isDigit() }) {
            result = TOKEN.replace(result) { match ->
                val token = match.value
                if (isSensitiveToken(token, hasCodeContext)) {
                    masked++
                    MASK
                } else {
                    token
                }
            }
        }
        return result to masked
    }
}
