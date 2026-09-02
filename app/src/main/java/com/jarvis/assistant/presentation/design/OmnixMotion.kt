package com.jarvis.assistant.presentation.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable

/**
 * Motion tokens (§28, §29, §58, §59).
 *
 * Every animation must answer "what changed?". There are no decorative
 * particles, no spinning loaders, no giant waveforms. Durations live here so
 * that reduced motion can scale them globally.
 */
@Immutable
data class OmnixMotionTokens(
    /** Idle breathing cycle, one full in-out (§58: 4–6 s). */
    val breathingCycleMs: Int = 5200,
    /** Peak breathing scale — deliberately almost imperceptible (§15). */
    val breathingAmplitude: Float = 0.015f,

    /** State-to-state Core transformation: the object morphs, never remounts. */
    val stateTransitionMs: Int = 420,
    /** Colour cross-fade between states. */
    val colorTransitionMs: Int = 320,

    /** Audio-reactive follow: fast enough to feel live, slow enough to be calm. */
    val audioAttackMs: Int = 90,
    val audioReleaseMs: Int = 220,

    /** Thinking: slow internal progression, one revolution. */
    val thinkingCycleMs: Int = 2600,
    /** Executing: controlled continuous motion. */
    val executingCycleMs: Int = 1600,
    /** Recognizing: subtle directional drift. */
    val recognizingCycleMs: Int = 2000,

    /** Success confirmation, then settle back to idle. */
    val successHoldMs: Int = 900,
    /** Error interruption — short, not an alarm. */
    val errorInterruptMs: Int = 260,

    /** Screen / content transitions. */
    val screenEnterMs: Int = 260,
    val screenExitMs: Int = 200,
    val contentFadeMs: Int = 180,

    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val gentle: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
    val linear: Easing = LinearEasing
) {
    /**
     * Reduced-motion variant (§29): breathing and deformation are removed,
     * transitions are shortened. State remains legible through typography,
     * stroke and colour — never through motion alone.
     */
    fun reduced(): OmnixMotionTokens = copy(
        breathingAmplitude = 0f,
        stateTransitionMs = 120,
        colorTransitionMs = 120,
        audioAttackMs = 0,
        audioReleaseMs = 0,
        thinkingCycleMs = 0,
        executingCycleMs = 0,
        recognizingCycleMs = 0,
        successHoldMs = 700,
        errorInterruptMs = 0,
        screenEnterMs = 100,
        screenExitMs = 80,
        contentFadeMs = 90
    )
}

val OmnixMotion = OmnixMotionTokens()
