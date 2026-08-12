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
            settingsRepository.isHeadsetOnlyModeFlow,
            settingsRepository.wakeWordSensitivityFlow
        ) { args: Array<Any?> ->
            VoiceSettings(
                userName = args[0] as String,
                systemPrompt = args[1] as String,
                speechRate = args[2] as Float,
                speechPitch = args[3] as Float,
                selectedModel = args[4] as String,
                isHeadsetOnlyMode = args[5] as Boolean,
                wakeWordSensitivity = args[6] as Float
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
    suspend fun saveWakeWordSensitivity(sensitivity: Float) = settingsRepository.setWakeWordSensitivity(sensitivity)
}

class ObserveNetworkStateUseCase @Inject constructor(
    private val networkMonitor: NetworkMonitor
) {
    operator fun invoke(): Flow<Boolean> = networkMonitor.isOnline
    fun isOnline(): Boolean = networkMonitor.isCurrentlyOnline()
}
