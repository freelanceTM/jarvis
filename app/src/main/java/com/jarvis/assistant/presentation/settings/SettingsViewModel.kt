package com.jarvis.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.domain.models.VoiceSettings
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SaveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val userName: String = "Сэр",
    val apiKey: String = "",
    val isApiKeyHidden: Boolean = true,
    val systemPrompt: String = "",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val selectedModel: String = "gpt-4o-mini",
    val isSavedSuccess: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getSettingsUseCase: GetSettingsUseCase,
    private val saveSettingsUseCase: SaveSettingsUseCase,
    private val securityManager: SecurityManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val currentKey = securityManager.getApiKey()
        _uiState.update { it.copy(apiKey = currentKey) }

        viewModelScope.launch {
            getSettingsUseCase().collectLatest { settings ->
                _uiState.update { 
                    it.copy(
                        userName = settings.userName,
                        systemPrompt = settings.systemPrompt,
                        speechRate = settings.speechRate,
                        speechPitch = settings.speechPitch,
                        selectedModel = settings.selectedModel
                    )
                }
            }
        }
    }

    fun onUserNameChanged(name: String) {
        _uiState.update { it.copy(userName = name) }
    }

    fun onApiKeyChanged(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(isApiKeyHidden = !it.isApiKeyHidden) }
    }

    fun onSystemPromptChanged(prompt: String) {
        _uiState.update { it.copy(systemPrompt = prompt) }
    }

    fun onSpeechRateChanged(rate: Float) {
        _uiState.update { it.copy(speechRate = rate) }
    }

    fun onSpeechPitchChanged(pitch: Float) {
        _uiState.update { it.copy(speechPitch = pitch) }
    }

    fun onModelSelected(modelId: String) {
        _uiState.update { it.copy(selectedModel = modelId) }
    }

    fun saveAllSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            securityManager.saveApiKey(state.apiKey)
            saveSettingsUseCase.saveUserName(state.userName)
            saveSettingsUseCase.saveSystemPrompt(state.systemPrompt)
            saveSettingsUseCase.saveSpeechRate(state.speechRate)
            saveSettingsUseCase.saveSpeechPitch(state.speechPitch)
            saveSettingsUseCase.saveModel(state.selectedModel)
            _uiState.update { it.copy(isSavedSuccess = true) }
        }
    }
}
