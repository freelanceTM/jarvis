package com.jarvis.assistant.data.repository

import android.util.Log
import com.jarvis.assistant.agent.router.TaskRouter
import com.jarvis.assistant.agent.tools.intelligence.WebSearchTool
import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import com.jarvis.assistant.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIRepositoryImpl @Inject constructor(
    private val aiClient: AIClient,
    private val taskRouter: TaskRouter,
    private val webSearchTool: WebSearchTool,
    private val dispatchers: CoroutineDispatchers
) : AIRepository {

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> {
        return withContext(dispatchers.io) {
            val routingDecision = taskRouter.routeTask(prompt)
            Log.d("AIRepository", "TaskRouter: tier=${routingDecision.tier}, model=${routingDecision.targetModelId}, requiresWebSearch=${routingDecision.requiresWebSearch}")

            var enrichedSystemPrompt = systemPrompt
            if (routingDecision.requiresWebSearch) {
                try {
                    val searchResult = webSearchTool.execute(
                        buildJsonObject { put("query", prompt) }
                    )
                    if (searchResult.isSuccess && searchResult.summary.isNotBlank()) {
                        enrichedSystemPrompt += "\n\nРезультаты поиска в интернете:\n${searchResult.summary}\n\nИспользуй эту информацию для точного и актуального ответа."
                    }
                } catch (e: Exception) {
                    Log.w("AIRepository", "Web search failed or timed out: ${e.localizedMessage}")
                }
            }

            aiClient.complete(
                prompt = prompt,
                systemPrompt = enrichedSystemPrompt,
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
