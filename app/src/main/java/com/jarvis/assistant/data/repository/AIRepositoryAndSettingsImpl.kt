package com.jarvis.assistant.data.repository

import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.ai.ContextualCloudAIClient
import com.jarvis.assistant.ai.PrivacyCloudBlockedException
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import com.jarvis.assistant.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Облачный AI на Android (Этап 3).
 *
 * Маршрутизация и выбор модели — ответственность сервера (AI Router + Provider
 * Manager); клиент только передаёт запрос и контекст инструментов. Локальный
 * TaskRouter убран (AR-01): его keyword-based эвристики дублировали
 * ExecutionDecisionEngine и не были подключены в production path.
 *
 * H-02 / Refactor #3: репозиторий НЕ переклассифицирует запрос — он доверяет
 * пришедшему effective PrivacyLevel от [SendPromptUseCase] (который один раз
 * вычислил его с полным контекстом). Мы только парсим enum, валидируем invariant
 * (effective=PRIVATE/SENSITIVE без consent не проходит) и проксируем вызов
 * сетевому клиенту. Это defence-in-depth на случай бага в вызывающем коде,
 * а не повторная классификация.
 */
@Singleton
class AIRepositoryImpl @Inject constructor(
    private val aiClient: AIClient,
    private val dispatchers: CoroutineDispatchers
) : AIRepository {

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> = withContext(dispatchers.io) {
        // Упрощённый вход без контекста — используется LiveTranslatorEngine.
        // Политика здесь максимально жёсткая: без явного privacy-контекста
        // мы НЕ лезем в классификатор повторно; по умолчанию — NORMAL,
        // потому что переводчик — пользователь-инициированная облачная
        // функция с явной UI-точкой входа. Переход на контекстный вход
        // для переводчика — отдельная задача.
        aiClient.complete(
            prompt = prompt,
            systemPrompt = systemPrompt,
            history = history
        )
    }

    /**
     * Расширенный вызов с контекстом решения: источник, приватность, web.
     * Используется ExecutionDecisionEngine через CloudAiExecutor.
     *
     * H-02: [privacyLevel] — УЖЕ effective (SendPromptUseCase посчитал один
     * раз); здесь только парсинг и invariant check.
     */
    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean,
        history: List<Message>,
        requestId: String
    ): Resource<String> = withContext(dispatchers.io) {
        val effective = PrivacyLevel.entries.firstOrNull { it.name == privacyLevel.uppercase() }
            ?: return@withContext privacyBlocked(PrivacyLevel.UNKNOWN)
        // Invariant: PRIVATE/SENSITIVE без согласия не должны дойти до сети.
        val permitted = effective == PrivacyLevel.NORMAL ||
            (effective in setOf(PrivacyLevel.PRIVATE, PrivacyLevel.SENSITIVE) && cloudExplicitlyAllowed)
        if (!permitted) {
            return@withContext privacyBlocked(effective)
        }
        when (val client = aiClient) {
            is ContextualCloudAIClient -> client.completeWithContext(
                prompt = prompt,
                systemPrompt = systemPrompt,
                source = source,
                effectivePrivacyLevel = effective,
                requiresWeb = requiresWeb,
                cloudExplicitlyAllowed = cloudExplicitlyAllowed,
                history = history,
                requestId = requestId
            )
            else -> aiClient.complete(
                prompt = prompt,
                systemPrompt = systemPrompt,
                history = history
            )
        }
    }

    private fun privacyBlocked(level: PrivacyLevel): Resource.Error = Resource.Error(
        PrivacyCloudBlockedException(level),
        "Облачная обработка запрещена политикой приватности"
    )
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val dispatchers: CoroutineDispatchers
) : SettingsRepository {

    override val systemPromptFlow: Flow<String> = settingsDataStore.systemPromptFlow
    override val speechRateFlow: Flow<Float> = settingsDataStore.speechRateFlow
    override val speechPitchFlow: Flow<Float> = settingsDataStore.speechPitchFlow
    override val userNameFlow: Flow<String> = settingsDataStore.userNameFlow
    override val selectedModelFlow: Flow<String> = settingsDataStore.selectedModelFlow
    override val isHeadsetOnlyModeFlow: Flow<Boolean> = settingsDataStore.isHeadsetOnlyModeFlow
    override val wakeWordSensitivityFlow: Flow<Float> = settingsDataStore.wakeWordSensitivityFlow

    override suspend fun setSystemPrompt(prompt: String) {
        withContext(dispatchers.io) {
            settingsDataStore.setSystemPrompt(prompt)
        }
    }

    override suspend fun setSpeechRate(rate: Float) {
        withContext(dispatchers.io) {
            settingsDataStore.setSpeechRate(rate)
        }
    }

    override suspend fun setSpeechPitch(pitch: Float) {
        withContext(dispatchers.io) {
            settingsDataStore.setSpeechPitch(pitch)
        }
    }

    override suspend fun setUserName(name: String) {
        withContext(dispatchers.io) {
            settingsDataStore.setUserName(name)
        }
    }

    override suspend fun setSelectedModel(model: String) {
        withContext(dispatchers.io) {
            settingsDataStore.setSelectedModel(model)
        }
    }

    override suspend fun setHeadsetOnlyMode(enabled: Boolean) {
        withContext(dispatchers.io) {
            settingsDataStore.setHeadsetOnlyMode(enabled)
        }
    }

    override suspend fun setWakeWordSensitivity(sensitivity: Float) {
        withContext(dispatchers.io) {
            settingsDataStore.setWakeWordSensitivity(sensitivity)
        }
    }

    override suspend fun resetDefaults() {
        withContext(dispatchers.io) {
            settingsDataStore.resetDefaults()
        }
    }
}
