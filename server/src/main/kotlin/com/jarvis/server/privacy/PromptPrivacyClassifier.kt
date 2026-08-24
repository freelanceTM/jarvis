package com.jarvis.server.privacy

import com.jarvis.server.api.ApiPrivacyLevel
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

enum class PrivacyReason {
    NONE,
    CREDENTIAL,
    AUTH_TOKEN,
    PRIVATE_KEY,
    DATABASE_URL,
    PAYMENT_DATA,
    GOVERNMENT_ID,
    MEDICAL_DATA,
    PRIVATE_CONTACT,
    PRIVATE_LOCATION,
    PRIVATE_DOCUMENT,
    CONFIDENTIAL_BUSINESS,
    EMPTY_INPUT,
    MALFORMED_INPUT,
    INPUT_TOO_LARGE,
    CLASSIFIER_FAILURE,
    NOT_CLASSIFIED
}

data class PrivacyClassification(
    val level: ApiPrivacyLevel,
    val reasons: Set<PrivacyReason>,
    val complete: Boolean
) {
    companion object {
        fun unknown(reason: PrivacyReason) =
            PrivacyClassification(ApiPrivacyLevel.UNKNOWN, setOf(reason), complete = false)
    }
}

data class PrivacyContent(
    val text: String,
    val relatedContent: List<String> = emptyList()
) {
}

fun interface ServerPrivacyClassifier {
    fun classify(content: PrivacyContent): PrivacyClassification
}

/** Deterministic, entirely local classification before any network/cloud path. */
object PromptPrivacyClassifier : ServerPrivacyClassifier {
    private const val MAX_CLASSIFIABLE_CHARS = 32_768

    private val rawTokenPatterns = listOf(
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}\b"""),
        Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:sk-[A-Za-z0-9_-]{12,}|gsk_[A-Za-z0-9_-]{12,}|AIza[A-Za-z0-9_-]{20,})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:jrv_|pdl_(?:live|sandbox)_)[A-Za-z0-9_-]{16,}\b""", RegexOption.IGNORE_CASE),
        Regex("""(?i)\bs\s*k\s*-\s*(?:[A-Za-z0-9_-]\s*){12,}""")
    )
    private val credentialAssignment = Regex(
        """(?i)\b(?:password|passwd|pwd|парол\p{L}*|api[\s_-]*key|access[\s_-]*token|""" +
            """refresh[\s_-]*token|secret|секрет\p{L}*|токен\p{L}*|""" +
            """pin[\s_-]*(?:code|код)?|пин[\s_-]*(?:код)?|seed[\s_-]*phrase|мнемоническ\p{L}*\s+фраз\p{L}*)\b""" +
            """["']?\s*(?::|=|—|\bis\b|\bэто\b)\s*["']?[^\s"',;]{4,}"""
    )
    private val ownedCredential = Regex(
        """(?i)\b(?:my|мой|моя|моё|мои)\s+(?:password|парол\p{L}*|pin|пин\p{L}*|""" +
            """api[\s_-]*key|token|токен\p{L}*|secret|секрет\p{L}*|seed\s*phrase|""" +
            """private\s*key|приватн\p{L}*\s*ключ\p{L}*)\b"""
    )
    private val privateKey = Regex(
        """-----\s*BEGIN\s+(?:(?:RSA|EC|OPENSSH|PGP)\s+)?PRIVATE\s+KEY\s*-----""",
        RegexOption.IGNORE_CASE
    )
    private val databaseUrl = Regex(
        """(?i)\b(?:jdbc:)?(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\+srv)?|redis)://""" +
            """[^\s/:@]+:[^\s/@]+@[^\s]+"""
    )
    private val otp = Regex(
        """(?i)(?:otp|one[\s_-]*time\s+code|одноразов\p{L}*\s+код\p{L}*|""" +
            """код\s+из\s+(?:смс|sms))\D{0,16}\d{4,8}"""
    )
    private val smsOtp = Regex(
        """(?i)код\s+из\s+(?:смс|sms)\s*[:=\-]?\s*[0-9]{4,8}"""
    )
    private val paymentContext = Regex(
        """(?i)\b(?:card\s*(?:number|no)|номер\s+(?:моей\s+)?карт\p{L}*|cvv|cvc)\b"""
    )
    private val iban = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b""", RegexOption.IGNORE_CASE)
    private val governmentId = Regex(
        """(?i)\b(?:passport|паспорт\p{L}*|national[\s_-]*id|social[\s_-]*security|ssn|""" +
            """инн|снилс)\b\D{0,20}[A-ZА-Я0-9][A-ZА-Я0-9 -]{5,24}\b"""
    )
    private val medical = Regex(
        """(?i)\b(?:my|мой|моя|моё|мои|моего|моей)\s+(?:diagnos\p{L}*|диагноз\p{L}*|""" +
            """medical\s+(?:record|data)|медицинск\p{L}*\s+(?:карт\p{L}*|данн\p{L}*)|""" +
            """результат\p{L}*\s+анализ\p{L}*)\b"""
    )
    private val privateContact = Regex(
        """(?i)\b(?:my|мой|моя|моё|мои|моего|моей)\s+(?:home\s+address|email|e-mail|""" +
            """phone\s+number|домашн\p{L}*\s+адрес\p{L}*|электронн\p{L}*\s+почт\p{L}*|""" +
            """номер\s+телефон\p{L}*)\b"""
    )
    private val structuredPrivateContact = Regex(
        """(?i)[\"']?(?:recipient|contact|phone|phone_number|email|e-mail)[\"']?\s*[:=]\s*[\"'][^\"']{2,}[\"']"""
    )
    private val emailAddress = Regex(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
    )
    private val internationalPhone = Regex(
        """(?<!\w)\+\d(?:[ ()-]?\d){8,14}(?!\w)"""
    )
    private val privateLocation = Regex(
        """(?i)\b(?:my|мой|моя|моё|мои)\s+(?:current\s+)?(?:device\s+)?(?:location|""" +
            """home\s+address|местоположен\p{L}*|геолокац\p{L}*|домашн\p{L}*\s+адрес\p{L}*)\b"""
    )
    private val preciseCoordinates = Regex(
        """(?i)[\"']?(?:latitude|longitude|lat|lon)[\"']?\s*[:=]\s*-?\d{1,3}(?:\.\d{3,})"""
    )
    private val privateDocument = Regex(
        """(?i)\b(?:private|personal|confidential|личн\p{L}*|приватн\p{L}*|конфиденциальн\p{L}*)\s+""" +
            """(?:document|file|letter|message|chat|переписк\p{L}*|документ\p{L}*|файл\p{L}*)\b"""
    )
    private val ownedPrivateReference = Regex(
        """(?i)\b(?:данн\p{L}*\s+(?:моего|моей)\s+паспорта|""" +
            """прочитай\s+мою\s+личн\p{L}*\s+переписк\p{L}*)\b"""
    )
    private val confidentialBusiness = Regex(
        """(?i)\b(?:confidential|strictly\s+confidential|коммерческ\p{L}*\s+тайн\p{L}*|""" +
            """конфиденциальн\p{L}*)\s+(?:business|contract|deal|financial|strategy|""" +
            """бизнес|договор\p{L}*|сделк\p{L}*|финансов\p{L}*|стратег\p{L}*)\b"""
    )

    override fun classify(content: PrivacyContent): PrivacyClassification {
        val parts = listOf(content.text) + content.relatedContent
        val totalLength = parts.sumOf(String::length)
        if (parts.all(String::isBlank)) {
            return PrivacyClassification.unknown(PrivacyReason.EMPTY_INPUT)
        }
        if (totalLength > MAX_CLASSIFIABLE_CHARS) {
            return PrivacyClassification.unknown(PrivacyReason.INPUT_TOO_LARGE)
        }
        if (parts.any(::isMalformed)) {
            return PrivacyClassification.unknown(PrivacyReason.MALFORMED_INPUT)
        }

        val variants = buildSet {
            parts.filter(String::isNotBlank).forEach { addVariants(it) }
            addVariants(parts.joinToString(" "))
        }
        val reasons = linkedSetOf<PrivacyReason>()
        for (value in variants) {
            if (rawTokenPatterns.any { it.containsMatchIn(value) }) reasons += PrivacyReason.AUTH_TOKEN
            if (credentialAssignment.containsMatchIn(value) || ownedCredential.containsMatchIn(value)) {
                reasons += PrivacyReason.CREDENTIAL
            }
            if (privateKey.containsMatchIn(value)) reasons += PrivacyReason.PRIVATE_KEY
            if (databaseUrl.containsMatchIn(value)) reasons += PrivacyReason.DATABASE_URL
            if (otp.containsMatchIn(value) || smsOtp.containsMatchIn(value) || iban.containsMatchIn(value) ||
                (paymentContext.containsMatchIn(value) && containsPaymentNumber(value))
            ) reasons += PrivacyReason.PAYMENT_DATA
            if (governmentId.containsMatchIn(value)) reasons += PrivacyReason.GOVERNMENT_ID
            if (medical.containsMatchIn(value)) reasons += PrivacyReason.MEDICAL_DATA
            if (privateContact.containsMatchIn(value) || structuredPrivateContact.containsMatchIn(value) ||
                emailAddress.containsMatchIn(value) || internationalPhone.containsMatchIn(value)
            ) reasons += PrivacyReason.PRIVATE_CONTACT
            if (privateLocation.containsMatchIn(value) || preciseCoordinates.containsMatchIn(value)) {
                reasons += PrivacyReason.PRIVATE_LOCATION
            }
            if (privateDocument.containsMatchIn(value) || ownedPrivateReference.containsMatchIn(value)) {
                reasons += PrivacyReason.PRIVATE_DOCUMENT
            }
            if (confidentialBusiness.containsMatchIn(value)) reasons += PrivacyReason.CONFIDENTIAL_BUSINESS
        }

        val sensitive = setOf(
            PrivacyReason.CREDENTIAL, PrivacyReason.AUTH_TOKEN, PrivacyReason.PRIVATE_KEY,
            PrivacyReason.DATABASE_URL, PrivacyReason.PAYMENT_DATA, PrivacyReason.GOVERNMENT_ID,
            PrivacyReason.MEDICAL_DATA
        )
        val level = when {
            reasons.any { it in sensitive } -> ApiPrivacyLevel.SENSITIVE
            reasons.isNotEmpty() -> ApiPrivacyLevel.PRIVATE
            else -> ApiPrivacyLevel.NORMAL
        }
        return PrivacyClassification(
            level = level,
            reasons = reasons.ifEmpty { setOf(PrivacyReason.NONE) },
            complete = true
        )
    }

    fun classifySafely(
        content: PrivacyContent,
        classifier: ServerPrivacyClassifier = this
    ): PrivacyClassification = try {
        val result = classifier.classify(content)
        if (!result.complete || result.level == ApiPrivacyLevel.UNKNOWN) {
            PrivacyClassification.unknown(
                result.reasons.firstOrNull() ?: PrivacyReason.NOT_CLASSIFIED
            )
        } else {
            result
        }
    } catch (_: Throwable) {
        PrivacyClassification.unknown(PrivacyReason.CLASSIFIER_FAILURE)
    }

    /** Compatibility helper for existing callers/tests. */
    fun classify(text: String): ApiPrivacyLevel = classifySafely(PrivacyContent(text)).level

    fun effective(
        declared: ApiPrivacyLevel,
        automatic: PrivacyClassification
    ): ApiPrivacyLevel {
        if (!automatic.complete || automatic.level == ApiPrivacyLevel.UNKNOWN) return ApiPrivacyLevel.UNKNOWN
        if (declared == ApiPrivacyLevel.UNKNOWN) return automatic.level
        return strongestKnown(declared, automatic.level)
    }

    fun strongest(explicit: ApiPrivacyLevel, detected: ApiPrivacyLevel): ApiPrivacyLevel = when {
        detected == ApiPrivacyLevel.UNKNOWN -> ApiPrivacyLevel.UNKNOWN
        explicit == ApiPrivacyLevel.UNKNOWN -> detected
        else -> strongestKnown(explicit, detected)
    }

    private fun strongestKnown(first: ApiPrivacyLevel, second: ApiPrivacyLevel): ApiPrivacyLevel {
        fun rank(level: ApiPrivacyLevel) = when (level) {
            ApiPrivacyLevel.UNKNOWN -> -1
            ApiPrivacyLevel.NORMAL -> 0
            ApiPrivacyLevel.PRIVATE -> 1
            ApiPrivacyLevel.SENSITIVE -> 2
        }
        return if (rank(first) >= rank(second)) first else second
    }

    private fun MutableSet<String>.addVariants(raw: String) {
        val normalized = normalize(raw)
        add(normalized)
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }
            .getOrNull()?.let { add(normalize(it)) }
        add(
            normalize(raw)
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\u003d", "=", ignoreCase = true)
                .replace("\\u003a", ":", ignoreCase = true)
        )
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\u200B-\\u200D\\u2060\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)

    private fun isMalformed(value: String): Boolean =
        value.any { it == '\u0000' } || value.count { it.isISOControl() && !it.isWhitespace() } > 4

    private fun containsPaymentNumber(value: String): Boolean =
        Regex("""(?:\d[ -]?){13,19}""").findAll(value).any { match ->
            val digits = match.value.filter(Char::isDigit)
            digits.length in 13..19 && luhnValid(digits)
        }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var doubleDigit = false
        for (index in digits.indices.reversed()) {
            var value = digits[index].digitToInt()
            if (doubleDigit) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }
}
