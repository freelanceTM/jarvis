package com.jarvis.assistant.agent.translator

/**
 * Результат перевода. Состояния соответствуют требованию «инструмент не должен
 * выглядеть работающим, если перевод фактически не выполнен».
 *
 * Отдельно подчёркнуто: возврат исходной строки НИКОГДА не считается успехом —
 * для этого есть [Unsupported] и [ModelUnavailable].
 */
sealed interface TranslationResult {
    data class Success(
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String,
        val providerId: String
    ) : TranslationResult

    /** Языковая пара не поддерживается данным провайдером. */
    data class Unsupported(val sourceLang: String, val targetLang: String) : TranslationResult

    /** Нужен интернет, а его нет. */
    data class NetworkRequired(val reason: String) : TranslationResult

    /** Офлайн-модель не установлена/не загружена. */
    data class ModelUnavailable(val reason: String) : TranslationResult

    /** Прочая ошибка перевода. */
    data class Error(val reason: String) : TranslationResult
}

/**
 * Поставщик перевода.
 *
 *   TranslationEngine → TranslationProvider
 *
 * Провайдеры упорядочены по приоритету: движок опрашивает их по очереди и
 * использует первый, который реально может выполнить перевод пары языков.
 */
interface TranslationProvider {
    val providerId: String

    /** Работает ли провайдер без сети. */
    val isOffline: Boolean

    /** Поддерживает ли пару языков (target обязателен, source может быть "auto"). */
    fun supports(sourceLang: String, targetLang: String): Boolean

    /** Готов ли провайдер прямо сейчас (есть модель / есть сеть / есть ключ). */
    suspend fun isAvailable(): Boolean

    suspend fun translate(text: String, sourceLang: String, targetLang: String): TranslationResult
}

data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val localeTag: String
)

object TranslationLanguages {
    val SUPPORTED = listOf(
        SupportedLanguage("ru", "Русский", "ru-RU"),
        SupportedLanguage("en", "English", "en-US"),
        SupportedLanguage("tk", "Türkmençe", "tk-TM"),
        SupportedLanguage("tr", "Türkçe", "tr-TR"),
        SupportedLanguage("de", "Deutsch", "de-DE"),
        SupportedLanguage("zh", "中文 (Chinese)", "zh-CN"),
        SupportedLanguage("ar", "العربية (Arabic)", "ar-SA")
    )

    fun displayName(code: String): String =
        SUPPORTED.firstOrNull { it.code == code }?.displayName ?: code

    fun isSupported(code: String): Boolean = SUPPORTED.any { it.code == code }
}
