package com.jarvis.assistant.presentation.activation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.core.license.ActivationResult
import com.jarvis.assistant.core.license.LicenseInfo
import com.jarvis.assistant.core.license.LicenseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivationUiState(
    val inputCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val licenseInfo: LicenseInfo? = null,
    val isActivated: Boolean = false
)

@HiltViewModel
class ActivationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val licenseManager: LicenseManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivationUiState(
        isActivated = licenseManager.isActivatedAndValid(),
        licenseInfo = licenseManager.getLicenseInfo()
    ))
    val uiState: StateFlow<ActivationUiState> = _uiState.asStateFlow()

    init {
        observeLicense()
    }

    private fun observeLicense() {
        viewModelScope.launch {
            licenseManager.licenseFlow.collectLatest { info ->
                _uiState.update {
                    it.copy(
                        licenseInfo = info,
                        isActivated = info.isActivated && !info.isExpired
                    )
                }
            }
        }
    }

    fun onCodeChanged(newCode: String) {
        val clean = newCode.uppercase()
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(27)
        _uiState.update { it.copy(inputCode = clean, errorMessage = null) }
    }

    fun activate() {
        val code = _uiState.value.inputCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.pozhaluysta_vvedite_kod)) }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = licenseManager.activateWithCode(code)) {
                is ActivationResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = result.message,
                            isActivated = true,
                            licenseInfo = result.licenseInfo
                        )
                    }
                }
                is ActivationResult.InvalidCode -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.reason
                        )
                    }
                }
                is ActivationResult.ServiceUnavailable -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
                is ActivationResult.AlreadyExpired -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }
}
