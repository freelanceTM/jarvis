package com.jarvis.assistant.presentation.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.data.preferences.OmnixExperienceStore
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode
import com.jarvis.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single producer of [OmnixUiState] (§14, §60, §63).
 *
 * Every screen observes this one state object: Home, the Core, the status
 * line, the confirmation sheet and the voice feedback all read the same
 * fields, so they physically cannot disagree with each other or with the
 * voice pipeline.
 *
 * The view model owns no business logic. It combines flows that already exist
 * — the orchestrator, the audio router, connectivity and stored experience —
 * and translates them through [OmnixStateMapper] and [ActionMapper].
 */
@HiltViewModel
class OmnixViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VoiceInteractionOrchestrator,
    private val clipRepository: ClipRepository,
    private val experienceStore: OmnixExperienceStore,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    /** Set by the pairing screen while it actively searches for a Clip. */
    private val searching = MutableStateFlow(false)

    /** Re-read after every permission result so the UI reflects reality. */
    private val microphoneGranted = MutableStateFlow(hasMicrophonePermission())

    private val thresholds = MutableStateFlow(GuidanceThresholds())

    private val clip: StateFlow<ClipState> =
        clipRepository.clipStateIn(viewModelScope, searching)

    private val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Voice-layer inputs, grouped so the final `combine` stays readable.
     * Kotlin's `combine` tops out at five flows before it needs an array.
     */
    private data class VoiceSignals(
        val phase: OmnixPhase,
        val audioLevel: Float,
        val action: ActionSnapshot?,
        val confirmationPrompt: String?,
        val lastInteraction: LastInteraction?
    )

    private val voiceSignals: StateFlow<VoiceSignals> = combine(
        orchestrator.assistantState,
        orchestrator.currentMode,
        orchestrator.currentToolCall,
        orchestrator.lastToolResult,
        orchestrator.audioLevel
    ) { assistantState, mode, toolCall, toolResult, level ->
        val online = isOnline.value
        val phase = OmnixStateMapper.phaseOf(assistantState, mode, toolCall, online)
        VoiceSignals(
            phase = phase,
            audioLevel = level,
            action = snapshotOf(mode, toolCall, toolResult),
            confirmationPrompt = orchestrator.confirmationPrompt.value,
            lastInteraction = lastInteractionOf()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VoiceSignals(OmnixPhase.Idle, 0f, null, null, null)
    )

    /** Stored experience signals that drive progressive disclosure (§10, §82). */
    private val guidance: StateFlow<GuidanceLevel> = combine(
        experienceStore.successfulCommands,
        experienceStore.firstCommandCompleted,
        thresholds
    ) { commands, firstDone, limits ->
        limits.levelFor(commands, firstDone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuidanceLevel.New)

    /** The one state the whole frontend renders. */
    val uiState: StateFlow<OmnixUiState> = combine(
        voiceSignals,
        clip,
        isOnline,
        guidance,
        microphoneGranted
    ) { voice, clipState, online, guidanceLevel, micGranted ->
        val systemState = OmnixStateMapper.systemStateOf(
            clip = clipState,
            isOnline = online,
            microphoneGranted = micGranted,
            accessExpired = false,
            // The Clip is only *required* when the user chose headset-only
            // mode; otherwise the phone microphone is a valid path (§20).
            requireClip = false
        )
        OmnixUiState(
            phase = voice.phase,
            clip = clipState,
            isOnline = online,
            isListeningServiceActive = orchestrator.currentMode.value != OrchestratorMode.PAUSED_CALL_OR_SLEEP,
            audioLevel = voice.audioLevel,
            action = voice.action,
            confirmation = confirmationOf(voice),
            lastInteraction = voice.lastInteraction,
            systemState = systemState,
            guidance = guidanceLevel
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OmnixUiState())

    init {
        // A real, persisted signal for progressive disclosure: one increment
        // per successfully completed action, never a fabricated counter (§10).
        orchestrator.lastToolResult
            .onEach { result ->
                if (result != null && result.isSuccess) {
                    experienceStore.recordSuccessfulCommand()
                }
            }
            .launchIn(viewModelScope)

        // "Last seen" for the Clip must come from an observed connection.
        clip.onEach { state ->
            if (state is ClipState.Connected && state.deviceName.isNotBlank()) {
                clipRepository.onClipConnected(state.deviceName)
            }
        }.launchIn(viewModelScope)
    }

    private fun snapshotOf(
        mode: OrchestratorMode,
        call: ToolCall?,
        result: ToolExecutionResult?
    ): ActionSnapshot? {
        if (call == null) return null
        return when {
            result != null -> ActionMapper.completed(call, result)
            mode == OrchestratorMode.AWAITING_CONFIRMATION -> ActionMapper.pendingConfirmation(call)
            else -> ActionMapper.executing(call)
        }
    }

    private fun confirmationOf(voice: VoiceSignals): ConfirmationRequest? {
        val prompt = voice.confirmationPrompt ?: return null
        return ConfirmationRequest(
            title = prompt,
            detail = voice.action?.target,
            confirmLabel = context.getString(com.jarvis.assistant.R.string.omnix_confirm_confirm),
            cancelLabel = context.getString(com.jarvis.assistant.R.string.omnix_cancel),
            voiceEnabled = true,
            action = voice.action
        )
    }

    private fun lastInteractionOf(): LastInteraction? {
        val query = orchestrator.lastQuery.value
        val answer = orchestrator.lastAnswer.value
        if (query.isBlank() || answer.isBlank()) return null
        return LastInteraction(query, answer, System.currentTimeMillis())
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Called after any permission dialog so the UI reflects the real grant. */
    fun refreshPermissions() {
        microphoneGranted.value = hasMicrophonePermission()
    }

    /** Driven by the pairing screen (§40). */
    fun setSearching(active: Boolean) {
        searching.value = active
        if (active) clipRepository.refresh()
    }

    fun confirmPendingAction() {
        viewModelScope.launch { orchestrator.confirmPendingToolCallFromUi() }
    }

    fun cancelPendingAction() {
        viewModelScope.launch { orchestrator.cancelPendingToolCallFromUi() }
    }
}
