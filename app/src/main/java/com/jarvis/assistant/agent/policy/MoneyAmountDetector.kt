package com.jarvis.assistant.agent.policy

/**
 * Детектор денежных сумм в тексте аргументов — чистая логика, покрытая JVM-тестами.
 *
 * Задача: «Отправь Ивану 50 000» ДОЛЖНО распознаваться как платёжное действие,
 * даже если ни одной валюты в тексте нет. Ложные срабатывания неопасны
 * (лишнее подтверждение), пропуски — опасны (перевод без подтверждения),
 * поэтому правила консервативны в сторону обнаружения.
 */
object MoneyAmountDetector {

    /**
     * Валюты: символы и слова (рубли, доллары, евро, тенге, туркменский манат,
     * сум, гривна, лей, дирхам, юань, фунт).
     */
    private val CURRENCY = Regex(
        "(₽|\\$|€|£|рубл?\\.?|rub|тенге|tmt|manat|манат|сум\\w*|гривн\\w*|uah|лей\\w*|leu|дирхам\\w*|юан\\w*|cny|фунт\\w*)",
        RegexOption.IGNORE_CASE
    )

    /** Словесные множители: «50 тысяч», «2 млн», «10k». */
    private val MULTIPLIER = Regex(
        "(\\d[\\d\\s.,\\u00A0]{0,15})\\s*(тысяч\\w*|тыс\\.?|млн\\w*|миллион\\w*|k)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Число перед валютой: «5000 рублей», «50 000 ₽». */
    private val NUMBER_BEFORE_CURRENCY = Regex(
        "(\\d[\\d\\s.,\\u00A0]{0,15})\\s*(?:${CURRENCY.pattern})",
        RegexOption.IGNORE_CASE
    )

    /** Валюта перед числом: «$100», «€ 250», «₽1 500». */
    private val CURRENCY_BEFORE_NUMBER = Regex(
        "(?:${CURRENCY.pattern})\\s*(\\d[\\d\\s.,\\u00A0]{0,15})",
        RegexOption.IGNORE_CASE
    )

    /** Глаголы/слова денежного контекста. */
    private val MONEY_CONTEXT = Regex(
        "(отправ|перевед|перевод|перечисл|заплат|оплат|скину|скинь|деньг|платёж|платеж|донат)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Находит денежную сумму в тексте.
     *
     * @return сумма в основных единицах (для «50 тысяч» — 50 000), или null,
     *         если денежных признаков не обнаружено.
     */
    fun findAmount(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val normalized = text.replace('\u00A0', ' ')
        val hasCurrency = CURRENCY.containsMatchIn(normalized)
        val hasMoneyContext = MONEY_CONTEXT.containsMatchIn(normalized)

        // 1. Явная валюта: «5000 рублей», «$100», «€ 250».
        NUMBER_BEFORE_CURRENCY.find(normalized)?.let { match ->
            parseNumber(match.groupValues[1], multiplierFrom(match.groupValues[0]))?.let { return it }
        }
        CURRENCY_BEFORE_NUMBER.find(normalized)?.let { match ->
            parseNumber(match.groupValues[1])?.let { return it }
        }

        // 2. Словесный множитель: «50 тысяч», «2 млн», «10k» — требует
        //    денежного контекста (валюта ИЛИ глагол отправки/оплаты),
        //    чтобы «город 50 тысяч жителей» не был платёжём.
        MULTIPLIER.find(normalized)?.let { match ->
            if (hasCurrency || hasMoneyContext) {
                parseNumber(match.groupValues[1], multiplierFrom(match.groupValues[2]))?.let { return it }
            }
        }

        // 3. Крупное число в денежном контексте без валюты и множителя:
        //    «отправь Ивану 50000», «переведи 15000».
        if (hasMoneyContext) {
            NUMBER.find(normalized)?.let { match ->
                parseNumber(match.value)?.takeIf { it >= LARGE_AMOUNT_WITHOUT_CURRENCY }?.let { return it }
            }
        }
        return null
    }

    /** Порог «крупной суммы» для CRITICAL-формулировки. */
    const val LARGE_AMOUNT_THRESHOLD = 10_000L

    /** Минимальная сумма для правила «число + денежный глагол» без валюты. */
    private const val LARGE_AMOUNT_WITHOUT_CURRENCY = 1_000L

    private val NUMBER = Regex("\\d[\\d\\s.,\\u00A0]{0,15}")

    private fun multiplierFrom(fragment: String): Long = when {
        fragment.contains(Regex("тысяч|тыс", RegexOption.IGNORE_CASE)) -> 1_000L
        fragment.contains(Regex("млн|миллион", RegexOption.IGNORE_CASE)) -> 1_000_000L
        fragment.endsWith("k", ignoreCase = true) -> 1_000L
        else -> 1L
    }

    /**
     * Разбирает числовой литерал с пробелами/разделителями:
     * «50 000» → 50000, «50.000» → 50000 (группы по 3), «1.5 тысячи» → 1.5,
     * «50,000» → 50000.
     */
    private fun parseNumber(raw: String, multiplier: Long = 1L): Long? {
        var cleaned = raw.replace(Regex("[\\s\\u00A0]"), "")
        if (cleaned.isEmpty()) return null
        // Разделитель-разрядник: группы ровно по 3 цифры после разделителя.
        val grouped = Regex("^(\\d{1,3})([.,]\\d{3})+$")
        if (grouped.containsMatchIn(cleaned)) {
            cleaned = cleaned.replace(Regex("[.,]"), "")
            return cleaned.toLongOrNull()?.let { it * multiplier }
        }
        // Десятичная точка/запятая: «1.5 тысячи».
        val decimal = Regex("^\\d+[.,]\\d+$")
        if (decimal.containsMatchIn(cleaned)) {
            val value = cleaned.replace(',', '.').toDoubleOrNull() ?: return null
            return (value * multiplier).toLong()
        }
        return cleaned.toLongOrNull()?.let { it * multiplier }
    }

    /** Человекочитаемая сумма: 50000 → «50 000». */
    fun formatAmount(amount: Long): String {
        val sign = if (amount < 0) "-" else ""
        val digits = kotlin.math.abs(amount).toString()
        val sb = StringBuilder()
        for ((index, ch) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) sb.append(' ')
            sb.append(ch)
        }
        return sign + sb.toString()
    }
}

/**
 * Сопоставление получателя со списком доверенных контактов.
 * Номера сравниваются по последним 7 цифрам (телефонные коды стран/операторов
 * не должны ломать сопоставление), имена — по регистронезависимому равенству.
 */
object TrustedContactMatcher {

    /** Минимум цифр для сравнения номеров — защита от ложных совпадений. */
    private const val MIN_PHONE_DIGITS = 7

    fun isTrusted(recipient: String?, trustedContacts: Set<String>): Boolean {
        if (recipient.isNullOrBlank() || trustedContacts.isEmpty()) return false
        val recipientDigits = digitsOf(recipient)
        return trustedContacts.any { contact ->
            val contactDigits = digitsOf(contact)
            when {
                contactDigits.length >= MIN_PHONE_DIGITS && recipientDigits.length >= MIN_PHONE_DIGITS ->
                    recipientDigits.endsWith(contactDigits.takeLast(MIN_PHONE_DIGITS)) ||
                        contactDigits.endsWith(recipientDigits.takeLast(MIN_PHONE_DIGITS))

                else -> recipient.trim().equals(contact.trim(), ignoreCase = true)
            }
        }
    }

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }
}
