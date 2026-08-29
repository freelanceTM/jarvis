package com.jarvis.assistant.ai

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
 * Параметры [source], [effectivePrivacyLevel] и [requiresWeb] прокидываются в
 * перегрузке [completeWithContext]; базовый [complete] сохраняет прежнюю
 * сигнатуру, чтобы существующие вызывающие (LiveTranslatorEngine) не ломались.
 *
 * H-02 / Refactor #3: классификация приватности делается ОДИН раз в
 * [com.jarvis.assistant.domain.usecases.SendPromptUseCase] (с полным контекстом
 * prompt+systemPrompt+history). [completeWithContext] доверяет пришедшему
 * [effectivePrivacyLevel] и только проверяет invariant (effective не должен
 * быть NORMAL при отключённом consent) как defense-in-depth на случай бага
 * в вызывающем коде. Повторный вызов PrivacyClassifier здесь и в репозитории
 * убран — он порождал дублирование и drift.
 */
class PrivacyCloudBlockedException(level: PrivacyLevel) :
    IllegalStateException("Cloud blocked by privacy classification: ${level.name}")

/** A client that preserves classified privacy metadata and per-request consent. */
interface ContextualCloudAIClient {
    suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        effectivePrivacyLevel: PrivacyLevel,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean = false,
        history: List<Message> = emptyList()
    ): Resource<String>
}

@Singleton
class JarvisApiAiClient @Inject constructor(
    private val apiClient: JarvisApiClient
) : AIClient, ContextualCloudAIClient {

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> = completeWithContext(
        prompt = prompt,
        systemPrompt = systemPrompt,
        source = "CHAT",
        // Legacy-вызов (в т.ч. переводчик) — пользователь явно инициировал
        // функцию в UI; считаем это NORMAL, пока вызывающий не передал
        // контекстный effective-уровень.
        effectivePrivacyLevel = PrivacyLevel.NORMAL,
        requiresWeb = false,
        cloudExplicitlyAllowed = true,
        history = history
    )

    /**
     * Полный вызов с контекстом решения.
     *
     * P2-cleanup (Этап 5): параметр выбора модели был удалён из контракта [AIClient],
     * т.к. выбор модели — исключительно server-side (пункт 29 ТЗ); клиент не
     * имеет права его навязывать.
     */
    override suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        effectivePrivacyLevel: PrivacyLevel,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean,
        history: List<Message>
    ): Resource<String> {
        // Defense-in-depth invariant: к нам не должен прийти запрос на облако,
        // где effective в {PRIVATE,SENSITIVE} без явного consent. Отправка
        // такого означала бы обход C-02 gate в вызывающем коде. Классификатор
        // здесь НЕ перезапускаем.
        val permitted = effectivePrivacyLevel == PrivacyLevel.NORMAL ||
            (effectivePrivacyLevel in setOf(PrivacyLevel.PRIVATE, PrivacyLevel.SENSITIVE) && cloudExplicitlyAllowed)
        if (!permitted) {
            return Resource.Error(
                PrivacyCloudBlockedException(effectivePrivacyLevel),
                "Облачная обработка запрещена политикой приватности"
            )
        }
        return apiClient.execute(
            text = prompt,
            source = source,
            privacyLevel = effectivePrivacyLevel.name,
            requiresWeb = requiresWeb,
            // Пустой systemPrompt нормализуем в null: отсутствие контекста —
            // это не «пустая строка» в контракте запроса.
            systemContext = systemPrompt.ifBlank { null },
            cloudExplicitlyAllowed = cloudExplicitlyAllowed,
            // CR-03: преобразуем доменные Message → DTO, исключаем SYSTEM
            // (systemPrompt отправляется отдельно и серверный базовый промпт
            // не должен дублироваться через историю).
            history = history
                .filter { it.role.value != "system" && it.text.isNotBlank() }
                .map {
                    com.jarvis.assistant.data.remote.MessageDto(
                        role = it.role.value,
                        content = it.text
                    )
                }
        )
    }
}
