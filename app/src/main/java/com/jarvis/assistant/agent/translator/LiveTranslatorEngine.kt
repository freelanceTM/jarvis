package com.jarvis.assistant.agent.translator

import android.util.Log
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.repository.AIRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class InterpreterMode {
    EAR_ONLY,     // Собеседник говорит на иностранном ➔ перевод идёт мне в наушник
    TWO_WAY       // Двусторонний диалог
}

/**
 * Провайдер перевода поверх основной LLM проекта.
 *
 * Это единственный реально работающий бэкенд перевода в v0.2 — и он честно
 * помечен как онлайновый. Офлайн-модели перевода в проекте нет, поэтому
 * офлайн-провайдер не объявляется «на всякий случай»: движок вернёт
 * NETWORK_REQUIRED вместо тихой подмены результата.
 */
@Singleton
class LlmTranslationProvider @Inject constructor(
    private val aiRepository: AIRepository,
    private val networkMonitor: NetworkMonitor
) : TranslationProvider {

    override val providerId: String = "llm"
    override val isOffline: Boolean = false

    override fun supports(sourceLang: String, targetLang: String): Boolean =
        TranslationLanguages.isSupported(targetLang) &&
            (sourceLang == "auto" || TranslationLanguages.isSupported(sourceLang))

    override suspend fun isAvailable(): Boolean = networkMonitor.isCurrentlyOnline()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (!supports(sourceLang, targetLang)) {
            return@withContext TranslationResult.Unsupported(sourceLang, targetLang)
        }
        if (!networkMonitor.isCurrentlyOnline()) {
            return@withContext TranslationResult.NetworkRequired("Нет подключения к интернету")
        }

        val targetName = TranslationLanguages.displayName(targetLang)
        val sourceName = if (sourceLang == "auto") "исходного языка" else TranslationLanguages.displayName(sourceLang)

        val systemPrompt = """
            Ты — высокоскоростной синхронный переводчик JARVIS Earclip.
            Твоя единственная задача: перевести следующий текст с $sourceName на язык $targetName.

            Строгие правила:
            1. Верни ТОЛЬКО готовый перевод текста.
            2. Запрещено добавлять любые пояснения, комментарии, вводные слова, кавычки и мета-информацию.
            3. Сохраняй исходный разговорный тон и смысл для мгновенного озвучивания в наушник.
        """.trimIndent()

        when (val result = aiRepository.generateResponse(text, systemPrompt, emptyList())) {
            is Resource.Success -> {
                val cleaned = result.data.trim().replace(Regex("^[\"']|[\"']$"), "")
                if (cleaned.isBlank()) {
                    TranslationResult.Error("Пустой ответ переводчика")
                } else {
                    TranslationResult.Success(cleaned, sourceLang, targetLang, providerId)
                }
            }
            is Resource.Error -> TranslationResult.Error(result.message ?: "Ошибка перевода")
            is Resource.Loading -> TranslationResult.Error("Перевод не завершён")
        }
    }
}

/**
 * Live Translator Engine v0.2.
 *
 *   TranslationEngine → TranslationProvider(s)
 *
 * Движок сам не переводит: он выбирает первый доступный провайдер,
 * поддерживающий пару языков, и возвращает структурированный результат.
 * Никакого «fake translator», возвращающего исходную строку.
 */
@Singleton
class LiveTranslatorEngine @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards TranslationProvider>
) {
    companion object {
        private const val TAG = "LiveTranslatorEngine"

        /** Сохранено для совместимости с UI. */
        val SUPPORTED_LANGUAGES = TranslationLanguages.SUPPORTED
    }

    /**
     * Основной API: структурированный результат перевода.
     */
    suspend fun translateStructured(
        text: String,
        sourceLang: String = "auto",
        targetLang: String = "ru"
    ): TranslationResult {
        val clean = text.trim()
        if (clean.isEmpty()) return TranslationResult.Error("Пустой текст")

        val capable = providers.filter { it.supports(sourceLang, targetLang) }
        if (capable.isEmpty()) {
            return TranslationResult.Unsupported(sourceLang, targetLang)
        }

        // Офлайн-провайдеры приоритетнее: быстрее и работают без сети.
        val ordered = capable.sortedByDescending { it.isOffline }

        var lastFailure: TranslationResult = TranslationResult.ModelUnavailable(
            "Ни один переводчик недоступен"
        )

        for (provider in ordered) {
            if (!provider.isAvailable()) {
                Log.d(TAG, "Provider ${provider.providerId} unavailable, trying next")
                lastFailure = if (provider.isOffline) {
                    TranslationResult.ModelUnavailable("Офлайн-модель перевода не готова")
                } else {
                    TranslationResult.NetworkRequired("Онлайн-переводчик недоступен без сети")
                }
                continue
            }

            when (val result = provider.translate(clean, sourceLang, targetLang)) {
                is TranslationResult.Success -> return result
                else -> {
                    Log.w(TAG, "Provider ${provider.providerId} failed: $result")
                    lastFailure = result
                }
            }
        }

        return lastFailure
    }

    /**
     * Упрощённый API для голосового контура.
     *
     * @return переведённый текст либо null, если перевод НЕ выполнен.
     *         null здесь означает именно «перевода нет» — вызывающий обязан
     *         сообщить об этом пользователю, а не озвучивать исходную фразу.
     */
    suspend fun translateOrNull(
        text: String,
        sourceLang: String = "auto",
        targetLang: String = "ru"
    ): String? = (translateStructured(text, sourceLang, targetLang) as? TranslationResult.Success)?.translatedText

    /**
     * Человекочитаемое объяснение неудачи для озвучивания пользователю.
     */
    fun describeFailure(result: TranslationResult): String = when (result) {
        is TranslationResult.Success -> result.translatedText
        is TranslationResult.Unsupported ->
            "Пара языков ${result.sourceLang} → ${result.targetLang} не поддерживается, сэр."
        is TranslationResult.NetworkRequired ->
            "Для перевода нужен интернет: ${result.reason}."
        is TranslationResult.ModelUnavailable ->
            "Офлайн-переводчик недоступен: ${result.reason}."
        is TranslationResult.Error ->
            "Не удалось перевести: ${result.reason}."
    }
}
