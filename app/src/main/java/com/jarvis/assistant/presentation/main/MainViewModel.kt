package com.jarvis.assistant.presentation.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.audio.BluetoothAudioState
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode
import com.jarvis.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import com.jarvis.assistant.voice.service.JarvisVoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VoiceInteractionOrchestrator,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val securityManager: SecurityManager,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _effectChannel = Channel<MainUiEffect>(Channel.BUFFERED)
    val effect = _effectChannel.receiveAsFlow()

    init {
        observeNetwork()
        observeBluetooth()
        observeOrchestrator()
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            networkMonitor.isOnline.collectLatest { isOnline ->
                _uiState.update { it.copy(isOnline = isOnline) }
            }
        }
    }

    private fun observeBluetooth() {
        viewModelScope.launch {
            bluetoothAudioRouter.audioState.collectLatest { btState ->
                when (btState) {
                    is BluetoothAudioState.Connected -> {
                        _uiState.update { 
                            it.copy(
                                isBluetoothConnected = true,
                                bluetoothDeviceName = btState.deviceName
                            )
                        }
                    }
                    else -> {
                        _uiState.update { 
                            it.copy(
                                isBluetoothConnected = false,
                                bluetoothDeviceName = ""
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeOrchestrator() {
        viewModelScope.launch {
            orchestrator.assistantState.collectLatest { state ->
                _uiState.update { 
                    it.copy(
                        assistantState = state,
                        isApiKeyConfigured = securityManager.hasValidAccessToken()
                    )
                }
            }
        }

        viewModelScope.launch {
            orchestrator.currentMode.collectLatest { mode ->
                _uiState.update { it.copy(orchestratorMode = mode) }
            }
        }

        viewModelScope.launch {
            orchestrator.lastQuery.collectLatest { query ->
                _uiState.update { it.copy(lastUserQuery = query) }
            }
        }

        viewModelScope.launch {
            orchestrator.lastAnswer.collectLatest { answer ->
                _uiState.update { it.copy(lastAssistantResponse = answer) }
            }
        }

        viewModelScope.launch {
            orchestrator.privacyClassification.collectLatest { classification ->
                _uiState.update { it.copy(privacyLevel = classification.level) }
            }
        }
    }

    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.ToggleBackgroundService -> toggleService()
            is MainUiEvent.ManualWakeWordTrigger -> {
                if (!securityManager.hasValidAccessToken()) {
                    viewModelScope.launch { _effectChannel.send(MainUiEffect.NavigateToSettings) }
                    return
                }
                orchestrator.startServicePipeline()
            }
            is MainUiEvent.StopCurrentAction -> {
                orchestrator.stopAll()
            }
            is MainUiEvent.DismissError -> {
                _uiState.update { it.copy(assistantState = VoiceAssistantState.Idle) }
            }
        }
    }

    private fun toggleService() {
        if (!_uiState.value.isBackgroundServiceActive) {
            if (!securityManager.hasValidAccessToken()) {
                viewModelScope.launch { _effectChannel.send(MainUiEffect.NavigateToSettings) }
                return
            }
            if (JarvisVoiceService.start(context)) {
                _uiState.update { it.copy(isBackgroundServiceActive = true) }
            } else {
                viewModelScope.launch {
                    _effectChannel.send(MainUiEffect.ShowToast("Не удалось запустить микрофонный сервис"))
                }
            }
        } else {
            JarvisVoiceService.stop(context)
            _uiState.update { it.copy(isBackgroundServiceActive = false) }
        }
    }
}
