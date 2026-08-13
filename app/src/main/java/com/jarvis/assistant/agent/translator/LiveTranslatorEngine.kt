package com.jarvis.assistant.agent.translator

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.repository.AIRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class InterpreterMode {
    EAR_ONLY,     // Собеседник говорит на иностранном ➔ перевод идёт мне в наушник
    TWO_WAY       // Двусторонний диалог (Собеседник ➔ мне в наушник, Я ➔ собеседнику через динамик телефона)
}

data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val localeTag: String
)

@Singleton
class LiveTranslatorEngine @Inject constructor(
    private val aiRepository: AIRepository
) {
    companion object {
        val SUPPORTED_LANGUAGES = listOf(
            SupportedLanguage("ru", "Русский", "ru-RU"),
            SupportedLanguage("en", "English", "en-US"),
            SupportedLanguage("tk", "Türkmençe", "tk-TM"),
            SupportedLanguage("tr", "Türkçe", "tr-TR"),
            SupportedLanguage("de", "Deutsch", "de-DE"),
            SupportedLanguage("zh", "中文 (Chinese)", "zh-CN"),
            SupportedLanguage("ar", "العربية (Arabic)", "ar-SA")
        )
    }

    /**
     * Выполняет молниеносный синхронный перевод фразы через высокоскоростную модель (150 мс)
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "auto",
        targetLang: String = "ru"
    ): String = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext ""

        val targetName = SUPPORTED_LANGUAGES.firstOrNull { it.code == targetLang }?.displayName ?: targetLang
        val sourceName = if (sourceLang == "auto") "исходного языка" else (SUPPORTED_LANGUAGES.firstOrNull { it.code == sourceLang }?.displayName ?: sourceLang)

        val translationSystemPrompt = """
            Ты — высокоскоростной синхронный переводчик JARVIS Earclip.
            Твоя единственная задача: перевести следующий текст с $sourceName на язык $targetName.
            
            Строгие правила:
            1. Верни ТОЛЬКО готовый перевод текста.
            2. Запрещено добавлять любые пояснения, комментарии, вводные слова, кавычки и мета-информацию.
            3. Сохраняй исходный разговорный тон и смысл для мгновенного озвучивания в наушник.
        """.trimIndent()

        val result = aiRepository.generateResponse(
            prompt = cleanText,
            systemPrompt = translationSystemPrompt,
            history = emptyList()
        )

        return@withContext when (result) {
            is Resource.Success -> result.data.trim().replace(Regex("^[\"']|[\"']$"), "")
            is Resource.Error -> "Ошибка перевода: ${result.message}"
            is Resource.Loading -> ""
        }
    }
}
