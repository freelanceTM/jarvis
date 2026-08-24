package com.jarvis.assistant.ai

import com.jarvis.assistant.agent.decision.PrivacyClassifier
import com.jarvis.assistant.agent.decision.PrivacyContent
import com.jarvis.assistant.agent.decision.PrivacyLevel
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
class PrivacyCloudBlockedException(level: PrivacyLevel) :
    IllegalStateException("Cloud blocked by privacy classification: ${level.name}")

/** A client that preserves classified privacy metadata and per-request consent. */
interface ContextualCloudAIClient {
    suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean = false,
        relatedContent: List<String> = emptyList()
    ): Resource<String>
}

@Singleton
class JarvisApiAiClient @Inject constructor(
    private val apiClient: JarvisApiClient
) : AIClient, ContextualCloudAIClient {

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>,
        modelOverride: String?
    ): Resource<String> = completeWithContext(
        prompt = prompt,
        systemPrompt = systemPrompt,
        source = "CHAT",
        privacyLevel = PrivacyLevel.UNKNOWN.name,
        requiresWeb = false,
        cloudExplicitlyAllowed = false,
        relatedContent = history.map(Message::text)
    )

    /**
     * Полный вызов с контекстом решения.
     *
     * `modelOverride` намеренно игнорируется: выбор модели — server-side
     * (пункт 29 ТЗ), клиент не имеет права его навязывать.
     */
    override suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean,
        relatedContent: List<String>
    ): Resource<String> {
        val declared = PrivacyLevel.entries.firstOrNull { it.name == privacyLevel.uppercase() }
            ?: return Resource.Error(
                PrivacyCloudBlockedException(PrivacyLevel.UNKNOWN),
                "Облачная обработка запрещена политикой приватности"
            )
        val automatic = PrivacyClassifier.classifySafely(
            PrivacyContent(prompt, listOf(systemPrompt) + relatedContent)
        )
        val effective = PrivacyClassifier.effective(declared, automatic)
        val permitted = effective == PrivacyLevel.NORMAL ||
            (effective in setOf(PrivacyLevel.PRIVATE, PrivacyLevel.SENSITIVE) && cloudExplicitlyAllowed)
        if (!permitted) {
            return Resource.Error(
                PrivacyCloudBlockedException(effective),
                "Облачная обработка запрещена политикой приватности"
            )
        }
        return apiClient.execute(
            text = prompt,
            source = source,
            privacyLevel = effective.name,
            requiresWeb = requiresWeb,
            systemContext = systemPrompt,
            cloudExplicitlyAllowed = cloudExplicitlyAllowed
        )
    }
}
