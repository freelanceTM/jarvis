package com.jarvis.assistant.presentation.main

import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode

data class MainUiState(
    val assistantState: VoiceAssistantState = VoiceAssistantState.Idle,
    val orchestratorMode: OrchestratorMode = OrchestratorMode.STANDBY_WAKE_WORD,
    val isBackgroundServiceActive: Boolean = false,
    val isOnline: Boolean = true,
    val isBluetoothConnected: Boolean = false,
    val bluetoothDeviceName: String = "",
    val lastUserQuery: String = "",
    val lastAssistantResponse: String = "",
    val liveRmsDb: Float = 0f,
    val isApiKeyConfigured: Boolean = false
)

sealed interface MainUiEvent {
    data object ToggleBackgroundService : MainUiEvent
    data object ManualWakeWordTrigger : MainUiEvent
    data object StopCurrentAction : MainUiEvent
    data object DismissError : MainUiEvent
}

sealed interface MainUiEffect {
    data class ShowToast(val message: String) : MainUiEffect
    data object NavigateToSettings : MainUiEffect
    data object RequestServicePermissions : MainUiEffect
}
