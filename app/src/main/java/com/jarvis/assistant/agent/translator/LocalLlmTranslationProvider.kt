package com.jarvis.assistant.agent.translator

import android.util.Log
import com.jarvis.assistant.agent.localai.GenerationConfig
import com.jarvis.assistant.agent.localai.LocalModelManager
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first перевод (полоса LOCAL AI в ExecutionRouter).
 *
 * Цель — НЕ «локальная LLM решает всё», а «локальная обработка там, где cloud
 * не нужен»: короткие реплики живого переводчика (Ear Interpreter) не обязаны
 * покидать устройство, когда пользователь установил модель (~529 МБ, НЕ в
 * APK — standing constraint проекта).
 *
 * Роль в цепочке провайдеров: `isOffline = true` → LiveTranslatorEngine
 * сортирует `sortedByDescending { isOffline }` и пробует ЭТОТ провайдер
 * первым; любые не-Success результаты честно отдаются движку, и он переходит
 * к облачному [LlmTranslationProvider] (fallback без обходных путей).
 *
 * Приватность — обратная сторона C-02: облачный провайдер БЛОКИРУЕТ
 * PRIVATE/SENSITIVE тексты; локальный обрабатывает их на устройстве, поэтому
 * отдельный privacy-гейт здесь не нужен — данные не покидают процесс.
 *
 * Длинные документы (длиннее [MAX_LOCAL_CHARS]) — полоса CLOUD: маленькая
 * on-device модель не должна изображать перевод документа; провайдер честно
 * возвращает [TranslationResult.ModelUnavailable], и движок уходит в облако.
 */
@Singleton
class LocalLlmTranslationProvider @Inject constructor(
    private val modelManager: LocalModelManager,
    private val dispatchers: CoroutineDispatchers
) : TranslationProvider {

    override val providerId: String = "local_llm"

    /** Локальный → движок предпочитает его облачному (Local-first AI). */
    override val isOffline: Boolean = true

    override fun supports(sourceLang: String, targetLang: String): Boolean =
        TranslationLanguages.isSupported(targetLang) &&
            (sourceLang == "auto" || TranslationLanguages.isSupported(sourceLang))

    override suspend fun isAvailable(): Boolean = modelManager.isReady()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): TranslationResult = withContext(dispatchers.default) {
        if (text.length > MAX_LOCAL_CHARS) {
            // Длинный документ — не работа on-device модели: честная уступка
            // полосе CLOUD (движок переберёт следующий провайдер).
            return@withContext TranslationResult.ModelUnavailable(
                "Текст длиннее $MAX_LOCAL_CHARS символов — перевод уйдёт в облако"
            )
        }
        val runtime = modelManager.runtimeOrNull()
            ?: return@withContext TranslationResult.ModelUnavailable(
                "Локальная модель перевода не готова"
            )

        val targetName = TranslationLanguages.displayName(targetLang)
        val sourceName = if (sourceLang == "auto") "исходного языка" else TranslationLanguages.displayName(sourceLang)
        val prompt = buildString {
            append("Переведи текст с $sourceName на язык $targetName. ")
            append("Верни ТОЛЬКО готовый перевод, без пояснений, комментариев, ")
            append("вводных слов, кавычек и мета-информации. Сохрани разговорный тон.\nТекст:\n")
            append(text)
        }

        try {
            val generation = runtime.generate(prompt, TRANSLATION_CONFIG)
            val cleaned = generation.text.trim().replace(Regex("^[\"']|[\"']$"), "")
            if (cleaned.isBlank()) {
                Log.w(TAG, "local translation returned empty response")
                TranslationResult.Error("Пустой ответ локальной модели")
            } else {
                Log.d(TAG, "local translation ok | ${generation.metrics.toLogString()}")
                TranslationResult.Success(cleaned, sourceLang, targetLang, providerId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "local translation failed | type=${e.javaClass.simpleName}")
            TranslationResult.Error("Локальный перевод не удался: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "LocalLlmTranslator"

        /**
         * Порог «короткого перевода» (живые реплики). Длиннее — полоса CLOUD
         * (large context / long documents по таблице ExecutionRouter).
         */
        const val MAX_LOCAL_CHARS = 500

        /**
         * Низкая temperature: перевод — задача с единственно верным ответом,
         * а не генерация. 256 токенов достаточно для реплики до 500 символов.
         */
        private val TRANSLATION_CONFIG = GenerationConfig(maxTokens = 256, temperature = 0.3f)
    }
}
