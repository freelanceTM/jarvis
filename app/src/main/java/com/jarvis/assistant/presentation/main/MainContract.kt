package com.jarvis.assistant.presentation.main

import com.jarvis.assistant.domain.models.VoiceAssistantState

data class MainUiState(
    val assistantState: VoiceAssistantState = VoiceAssistantState.Idle,
    val isMicActive: Boolean = false,
    val isOnline: Boolean = true,
    val isBluetoothConnected: Boolean = false,
    val lastUserQuery: String = "",
    val lastAssistantResponse: String = "",
    val liveRmsDb: Float = 0f,
    val userName: String = "Сэр",
    val isApiKeyConfigured: Boolean = false
)

sealed interface MainUiEvent {
    data object ToggleVoiceInteraction : MainUiEvent
    data object StopVoiceInteraction : MainUiEvent
    data class SubmitTextPrompt(val text: String) : MainUiEvent
    data class ReplayAudio(val text: String) : MainUiEvent
    data object DismissError : MainUiEvent
}

sealed interface MainUiEffect {
    data class ShowToast(val message: String) : MainUiEffect
    data object RequestAudioPermission : MainUiEffect
    data object NavigateToSettings : MainUiEffect
}
