package com.jarvis.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.data.preferences.OmnixExperienceStore
import com.jarvis.assistant.presentation.design.OmnixAppearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Presentation preferences that shape the app itself (§29, §47).
 *
 * These are read at the very top of the tree — above the theme — because
 * appearance and reduced motion must apply to every screen at once, not to
 * whichever screen happens to observe them.
 */
data class AppearanceUiState(
    val appearance: OmnixAppearance = OmnixAppearance.System,
    val nightDimming: Boolean = false,
    val reduceMotionOverride: String = REDUCE_MOTION_SYSTEM,
    val voiceFeedback: Boolean = true,
    val notifyAssistant: Boolean = true,
    val notifyDevice: Boolean = true,
    val notifyRoutines: Boolean = true,
    /**
     * True once the stored values have actually been read. Until then the
     * app must not commit to a theme, or a dark-mode user would see one
     * light frame on every cold start.
     */
    val loaded: Boolean = false
) {
    /**
     * Resolves the stored override into the tri-state the theme expects:
     * `null` means "defer to the operating system's accessibility setting",
     * which is the correct default — the OS preference already reflects a
     * decision the user made once, for every app.
     */
    val reducedMotion: Boolean?
        get() = when (reduceMotionOverride) {
            REDUCE_MOTION_ON -> true
            REDUCE_MOTION_OFF -> false
            else -> null
        }
}

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: OmnixExperienceStore
) : ViewModel() {

    private val presentation = combine(
        store.appearance,
        store.nightDimming,
        store.reduceMotionOverride,
        store.voiceFeedback
    ) { appearance, nightDimming, reduceMotion, voiceFeedback ->
        AppearanceUiState(
            appearance = appearanceOf(appearance),
            nightDimming = nightDimming,
            reduceMotionOverride = reduceMotion,
            voiceFeedback = voiceFeedback,
            loaded = true
        )
    }

    val uiState: StateFlow<AppearanceUiState> = combine(
        presentation,
        store.notifyAssistant,
        store.notifyDevice,
        store.notifyRoutines
    ) { base, assistant, device, routines ->
        base.copy(
            notifyAssistant = assistant,
            notifyDevice = device,
            notifyRoutines = routines
        )
    }.stateIn(
        scope = viewModelScope,
        // Kept alive across configuration changes so rotating the device
        // does not momentarily drop back to the default theme.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppearanceUiState()
    )

    fun setAppearance(value: OmnixAppearance) {
        viewModelScope.launch { store.setAppearance(value.name) }
    }

    fun setNightDimming(enabled: Boolean) {
        viewModelScope.launch { store.setNightDimming(enabled) }
    }

    fun setReduceMotionOverride(value: String) {
        viewModelScope.launch { store.setReduceMotionOverride(value) }
    }

    fun setVoiceFeedback(enabled: Boolean) {
        viewModelScope.launch { store.setVoiceFeedback(enabled) }
    }

    fun setNotifyAssistant(enabled: Boolean) {
        viewModelScope.launch { store.setNotifyAssistant(enabled) }
    }

    fun setNotifyDevice(enabled: Boolean) {
        viewModelScope.launch { store.setNotifyDevice(enabled) }
    }

    fun setNotifyRoutines(enabled: Boolean) {
        viewModelScope.launch { store.setNotifyRoutines(enabled) }
    }

    private companion object {
        /** Tolerates an unknown stored value rather than crashing on it. */
        fun appearanceOf(stored: String): OmnixAppearance =
            OmnixAppearance.entries.firstOrNull { it.name == stored }
                ?: OmnixAppearance.System
    }
}
