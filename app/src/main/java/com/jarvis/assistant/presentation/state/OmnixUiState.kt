package com.jarvis.assistant.presentation.state

import androidx.compose.runtime.Immutable
import com.jarvis.assistant.presentation.core.CoreState

/**
 * The single UI state of OMNIX (§14, §60, §63).
 *
 * Everything visible — Core, labels, guidance, buttons, sheets — derives from
 * one instance of this class. The forbidden situation is:
 *
 * ```
 * voice layer = LISTENING
 * UI          = THINKING
 * Core        = IDLE
 * ```
 *
 * It is prevented structurally: [OmnixStateMapper] is the only producer, and
 * it derives every field from the orchestrator's own flows.
 */
@Immutable
data class OmnixUiState(
    /** Current interaction phase. Drives Core, copy and motion. */
    val phase: OmnixPhase = OmnixPhase.Idle,

    /** Live Clip / headset state (§13, §23). */
    val clip: ClipState = ClipState.Unknown,

    /** Network availability (§20, §53). */
    val isOnline: Boolean = true,

    /** Whether the voice pipeline is actually running in the foreground service. */
    val isListeningServiceActive: Boolean = false,

    /** Normalised live microphone amplitude 0..1 from the real audio source. */
    val audioLevel: Float = 0f,

    /** The action OMNIX is performing or has just finished (§16, §61). */
    val action: ActionSnapshot? = null,

    /** A pending confirmation the user must resolve (§17, §62). */
    val confirmation: ConfirmationRequest? = null,

    /** Last completed interaction, for optional Home context (§27 of the spec). */
    val lastInteraction: LastInteraction? = null,

    /** A system-level condition that must be surfaced (§18, §48). */
    val systemState: SystemStateType? = null,

    /** How much guidance the user still needs (§10, §82). */
    val guidance: GuidanceLevel = GuidanceLevel.New
) {
    /** The Core's visual state — derived, never stored separately (§91). */
    val coreState: CoreState
        get() = when (phase) {
            is OmnixPhase.Idle -> CoreState.IDLE
            is OmnixPhase.Listening -> CoreState.LISTENING
            is OmnixPhase.Recognizing -> CoreState.RECOGNIZING
            is OmnixPhase.Thinking -> CoreState.THINKING
            is OmnixPhase.Executing -> CoreState.EXECUTING
            is OmnixPhase.Speaking -> CoreState.SPEAKING
            is OmnixPhase.Success -> CoreState.SUCCESS
            is OmnixPhase.Error -> CoreState.ERROR
        }

    /** True while OMNIX is busy and the user should wait. */
    val isBusy: Boolean get() = coreState.isBusy

    /** True when the Clip is connected and voice can be used hands-free. */
    val isClipReady: Boolean get() = clip is ClipState.Connected
}

/**
 * The interaction phase. Exactly the eight states of the specification —
 * payload lives inside the phase so the UI never has to guess.
 */
@Immutable
sealed interface OmnixPhase {
    /** Waiting for the wake word. */
    data object Idle : OmnixPhase

    /** Microphone open, no words yet. */
    data object Listening : OmnixPhase

    /** Partial transcript is arriving (§17 of the spec). */
    data class Recognizing(val partialTranscript: String) : OmnixPhase

    /** Understanding the request. No spinner (§18 of the spec). */
    data object Thinking : OmnixPhase

    /** Performing a named action: "Calling Alex…" (§19 of the spec). */
    data class Executing(val action: ActionSnapshot) : OmnixPhase

    /** Speaking a result aloud. */
    data class Speaking(val text: String) : OmnixPhase

    /** Short confirmation before settling back to Idle. */
    data class Success(val message: String) : OmnixPhase

    /** A problem the user can recover from. */
    data class Error(val systemState: SystemStateType) : OmnixPhase
}

/**
 * How experienced the user is (§10, §82).
 *
 * Thresholds are configuration, not UI constants — see
 * `GuidanceThresholds`, which is supplied by the data layer.
 */
enum class GuidanceLevel {
    /** No successful command yet: show the full example. */
    New,

    /** A few successful commands: keep "Say Omni", drop the example. */
    Familiar,

    /** Experienced: presence only. */
    Minimal
}

/** Configurable thresholds for [GuidanceLevel] (§10: never hard-coded in UI). */
@Immutable
data class GuidanceThresholds(
    val familiarAfterCommands: Int = 1,
    val minimalAfterCommands: Int = 5
) {
    fun levelFor(successfulCommands: Int, firstCommandCompleted: Boolean): GuidanceLevel = when {
        !firstCommandCompleted || successfulCommands < familiarAfterCommands -> GuidanceLevel.New
        successfulCommands < minimalAfterCommands -> GuidanceLevel.Familiar
        else -> GuidanceLevel.Minimal
    }
}

/** The last completed interaction, shown on Home only when it is useful. */
@Immutable
data class LastInteraction(
    val query: String,
    val response: String,
    val timestampMillis: Long
)
