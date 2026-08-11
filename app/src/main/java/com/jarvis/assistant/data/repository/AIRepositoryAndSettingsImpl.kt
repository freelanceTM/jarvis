package com.jarvis.assistant.data.repository

import android.util.Log
import com.jarvis.assistant.agent.router.TaskRouter
import com.jarvis.assistant.ai.AIClient
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

@Singleton
class AIRepositoryImpl @Inject constructor(
    private val aiClient: AIClient,
    private val taskRouter: TaskRouter,
    private val dispatchers: CoroutineDispatchers
) : AIRepository {

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> {
        return withContext(dispatchers.io) {
            // Определяем оптимальный уровень модели (Tier 1 Fast / Tier 2 Reasoning / Tier 3 Search)
            val routingDecision = taskRouter.routeTask(prompt)
            Log.d("AIRepository", "TaskRouter: tier=${routingDecision.tier}, model=${routingDecision.targetModelId}, reason=${routingDecision.reason}")

            aiClient.complete(
                prompt = prompt,
                systemPrompt = systemPrompt,
                history = history,
                modelOverride = routingDecision.targetModelId
            )
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

    override suspend fun resetDefaults() {
        withContext(dispatchers.io) {
            settingsDataStore.resetDefaults()
        }
    }
}
