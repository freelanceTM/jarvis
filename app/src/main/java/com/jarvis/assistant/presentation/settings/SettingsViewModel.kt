package com.jarvis.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.core.license.LicenseInfo
import com.jarvis.assistant.core.license.LicenseManager
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
    val isHeadsetOnlyMode: Boolean = false,
    val wakeWordSensitivity: Float = 0.65f,
    val automations: List<AutomationEntity> = emptyList(),
    val licenseInfo: LicenseInfo? = null,
    val isSavedSuccess: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getSettingsUseCase: GetSettingsUseCase,
    private val saveSettingsUseCase: SaveSettingsUseCase,
    private val securityManager: SecurityManager,
    private val automationDao: AutomationDao,
    private val licenseManager: LicenseManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(licenseInfo = licenseManager.getLicenseInfo()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        loadAutomations()
        observeLicense()
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
                        selectedModel = settings.selectedModel,
                        isHeadsetOnlyMode = settings.isHeadsetOnlyMode,
                        wakeWordSensitivity = settings.wakeWordSensitivity
                    )
                }
            }
        }
    }

    private fun loadAutomations() {
        viewModelScope.launch {
            automationDao.getAllAutomationsStream().collectLatest { rules ->
                _uiState.update { it.copy(automations = rules) }
            }
        }
    }

    private fun observeLicense() {
        viewModelScope.launch {
            licenseManager.licenseFlow.collectLatest { info ->
                _uiState.update { it.copy(licenseInfo = info) }
            }
        }
    }

    fun extendSubscription(days: Int = 30) {
        val updated = licenseManager.extendSubscription(days)
        _uiState.update { it.copy(licenseInfo = updated) }
    }

    fun toggleAutomation(ruleId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            automationDao.toggleEnabled(ruleId, isEnabled)
        }
    }

    fun deleteAutomation(ruleId: String) {
        viewModelScope.launch {
            automationDao.deleteAutomation(ruleId)
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

    fun onHeadsetOnlyModeChanged(enabled: Boolean) {
        _uiState.update { it.copy(isHeadsetOnlyMode = enabled) }
        viewModelScope.launch {
            saveSettingsUseCase.saveHeadsetOnlyMode(enabled)
        }
    }

    fun onWakeWordSensitivityChanged(sensitivity: Float) {
        _uiState.update { it.copy(wakeWordSensitivity = sensitivity) }
        viewModelScope.launch {
            saveSettingsUseCase.saveWakeWordSensitivity(sensitivity)
        }
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
            saveSettingsUseCase.saveHeadsetOnlyMode(state.isHeadsetOnlyMode)
            saveSettingsUseCase.saveWakeWordSensitivity(state.wakeWordSensitivity)
            _uiState.update { it.copy(isSavedSuccess = true) }
        }
    }
}
