package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.VoiceSettings
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class GetChatHistoryUseCase @Inject constructor(
    private val repository: MessageRepository
) {
    operator fun invoke(): Flow<List<Message>> = repository.getMessagesStream()
}

class ClearChatHistoryUseCase @Inject constructor(
    private val repository: MessageRepository
) {
    suspend operator fun invoke() = repository.clearHistory()
}

class GetSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<VoiceSettings> {
        return combine(
            settingsRepository.userNameFlow,
            settingsRepository.systemPromptFlow,
            settingsRepository.speechRateFlow,
            settingsRepository.speechPitchFlow,
            settingsRepository.selectedModelFlow,
            settingsRepository.isHeadsetOnlyModeFlow
        ) { userName, systemPrompt, rate, pitch, model, headsetOnly ->
            VoiceSettings(
                userName = userName,
                systemPrompt = systemPrompt,
                speechRate = rate,
                speechPitch = pitch,
                selectedModel = model,
                isHeadsetOnlyMode = headsetOnly
            )
        }
    }
}

class SaveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend fun saveUserName(name: String) = settingsRepository.setUserName(name)
    suspend fun saveSystemPrompt(prompt: String) = settingsRepository.setSystemPrompt(prompt)
    suspend fun saveSpeechRate(rate: Float) = settingsRepository.setSpeechRate(rate)
    suspend fun saveSpeechPitch(pitch: Float) = settingsRepository.setSpeechPitch(pitch)
    suspend fun saveModel(model: String) = settingsRepository.setSelectedModel(model)
    suspend fun saveHeadsetOnlyMode(enabled: Boolean) = settingsRepository.setHeadsetOnlyMode(enabled)
}

class ObserveNetworkStateUseCase @Inject constructor(
    private val networkMonitor: NetworkMonitor
) {
    operator fun invoke(): Flow<Boolean> = networkMonitor.isOnline
    fun isOnline(): Boolean = networkMonitor.isCurrentlyOnline()
}
