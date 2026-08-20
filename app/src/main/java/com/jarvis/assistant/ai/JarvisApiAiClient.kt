package com.jarvis.assistant.ai

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.data.remote.JarvisApiClient
import com.jarvis.assistant.domain.models.Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация [AIClient] поверх JARVIS API (Этап 3).
 *
 * Заменила прежний `UniversalAIClient`, который ходил напрямую в Groq/Gemini/
 * OpenRouter с пользовательским ключом (BYOK). Теперь:
 *
 *  - ключей провайдеров на устройстве НЕТ;
 *  - выбор провайдера, fallback, retry и rate limiting — на сервере;
 *  - клиент лишь передаёт запрос и получает нормализованный ответ.
 *
 * Параметры [source], [privacyLevel] и [requiresWeb] прокидываются в
 * перегрузке [completeWithContext]; базовый [complete] сохраняет прежнюю
 * сигнатуру, чтобы существующие вызывающие (LiveTranslatorEngine) не ломались.
 */
@Singleton
class JarvisApiAiClient @Inject constructor(
    private val apiClient: JarvisApiClient
) : AIClient {

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>,
        modelOverride: String?
    ): Resource<String> = completeWithContext(
        prompt = prompt,
        systemPrompt = systemPrompt,
        source = "CHAT",
        privacyLevel = "NORMAL",
        requiresWeb = false
    )

    /**
     * Полный вызов с контекстом решения.
     *
     * `modelOverride` намеренно игнорируется: выбор модели — server-side
     * (пункт 29 ТЗ), клиент не имеет права его навязывать.
     */
    suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean
    ): Resource<String> = apiClient.execute(
        text = prompt,
        source = source,
        privacyLevel = privacyLevel,
        requiresWeb = requiresWeb,
        systemContext = systemPrompt
    )
}
