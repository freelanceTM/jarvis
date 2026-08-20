package com.jarvis.assistant.data.repository

import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.ai.JarvisApiAiClient
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
 * Что изменилось: раньше репозиторий сам выбирал модель через TaskRouter
 * и сам обогащал промпт результатами web-поиска. Теперь это ответственность
 * сервера (AI Router + Provider Manager), поэтому клиент только передаёт
 * запрос и контекст инструментов.
 *
 * TaskRouter и WebSearchTool намеренно НЕ удалены из проекта: роутер
 * по-прежнему используется в других местах, а WebSearchTool остаётся обычным
 * JarvisTool, который агент может вызвать явно.
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
        aiClient.complete(
            prompt = prompt,
            systemPrompt = systemPrompt,
            history = history,
            // Модель выбирает сервер — клиент её не навязывает.
            modelOverride = null
        )
    }

    /**
     * Расширенный вызов с контекстом решения: источник, приватность, web.
     * Используется ExecutionDecisionEngine через CloudAiExecutor.
     */
    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean
    ): Resource<String> = withContext(dispatchers.io) {
        when (val client = aiClient) {
            is JarvisApiAiClient -> client.completeWithContext(
                prompt = prompt,
                systemPrompt = systemPrompt,
                source = source,
                privacyLevel = privacyLevel,
                requiresWeb = requiresWeb
            )
            else -> client.complete(prompt, systemPrompt, emptyList(), null)
        }
    }
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
