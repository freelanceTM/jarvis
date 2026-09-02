package com.jarvis.assistant.presentation.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.jarvis.assistant.presentation.core.CoreGeometry.Shape
import com.jarvis.assistant.presentation.design.OmnixMotionTokens

/**
 * Per-state Core motion (§28, §58).
 *
 * Each animation answers "what changed?":
 *  - IDLE        slow breathing;
 *  - LISTENING   audio reactive;
 *  - RECOGNIZING subtle directional drift;
 *  - THINKING    slow internal progression;
 *  - EXECUTING   controlled continuous motion;
 *  - SPEAKING    speech reactive;
 *  - SUCCESS     one confirmation, then settle;
 *  - ERROR       short interruption of the ring.
 *
 * There are no spinners, no particles, no glow and no giant waveform.
 * With reduced motion every cycle is disabled and the state stays legible
 * through stroke, aperture and colour (§29).
 */
internal object CoreMotion {

    /**
     * Base shape per state — the values that do not depend on the clock.
     *
     * Every state keeps the same three-break ring: the identity of the object
     * must survive the transition (§7). What changes is stroke weight,
     * harmonic character and opacity.
     */
    fun baseShape(state: CoreState): Shape = when (state) {
        CoreState.IDLE -> Shape(
            stroke = CoreGeometry.STROKE_RATIO,
            pressure = 0.35f,
            opacity = 0.88f
        )

        CoreState.LISTENING -> Shape(
            stroke = CoreGeometry.STROKE_RATIO * 0.95f,
            pressure = 0.45f,
            reactive = 0f,              // driven by live amplitude
            reactiveLobes = 6,
            opacity = 1.0f
        )

        CoreState.RECOGNIZING -> Shape(
            h1 = 0.014f,
            h1Phase = 1.4f,
            stroke = CoreGeometry.STROKE_RATIO * 0.95f,
            pressure = 0.55f,
            opacity = 1.0f
        )

        CoreState.THINKING -> Shape(
            h2 = 0.016f,
            h2Phase = 0.4f,
            stroke = CoreGeometry.STROKE_RATIO * 0.9f,
            pressure = 0.40f,
            opacity = 0.95f
        )

        CoreState.EXECUTING -> Shape(
            stroke = CoreGeometry.STROKE_RATIO,
            pressure = 0.40f,
            opacity = 1.0f
        )

        CoreState.SPEAKING -> Shape(
            stroke = CoreGeometry.STROKE_RATIO * 0.95f,
            pressure = 0.60f,
            reactive = 0f,              // driven by live amplitude
            reactiveLobes = 11,
            opacity = 1.0f
        )

        // Confirmation: the ring closes into a single continuous circle and
        // carries a check mark. Closure is the message (§22).
        CoreState.SUCCESS -> Shape(
            gaps = emptyList(),
            stroke = CoreGeometry.STROKE_RATIO * 0.85f,
            pressure = 0.15f,
            opacity = 1.0f
        )

        // Error also closes the ring — a broken shape would read as damage.
        // The mark inside carries the meaning instead.
        CoreState.ERROR -> Shape(
            gaps = emptyList(),
            stroke = CoreGeometry.STROKE_RATIO * 0.85f,
            pressure = 0.15f,
            opacity = 1.0f
        )
    }

    /**
     * The glyph drawn inside the ring, if any (§22).
     *
     * Only the two terminal states carry one: a check for SUCCESS, an
     * exclamation for ERROR. No other state has an icon — the ring itself is
     * the indicator.
     */
    fun glyphOf(state: CoreState): CoreGlyph = when (state) {
        CoreState.SUCCESS -> CoreGlyph.CHECK
        CoreState.ERROR -> CoreGlyph.ALERT
        else -> CoreGlyph.NONE
    }

    /**
     * Inner arc sweep in radians — the only additional element the Core owns.
     * Used by THINKING (internal progression) and EXECUTING (controlled work).
     * Zero means "no inner arc".
     */
    fun innerArcSweep(state: CoreState): Float = when (state) {
        CoreState.THINKING -> 2.094f    // 120°
        CoreState.EXECUTING -> 3.665f   // 210°
        else -> 0f
    }

    /**
     * Continuous drivers for the current state.
     *
     * @return [Drivers] with values already at rest when motion is reduced.
     */
    @Composable
    fun rememberDrivers(state: CoreState, motion: OmnixMotionTokens): Drivers {
        val transition = rememberInfiniteTransition(label = "omnix_core")

        val breathing by transition.animateFloatOrStill(
            enabled = motion.breathingAmplitude > 0f,
            durationMs = motion.breathingCycleMs,
            initial = 0f,
            target = 1f,
            repeatMode = RepeatMode.Reverse,
            easing = motion.gentle,
            label = "breathing",
            still = 0f
        )

        val thinking by transition.animateFloatOrStill(
            enabled = state == CoreState.THINKING && motion.thinkingCycleMs > 0,
            durationMs = motion.thinkingCycleMs,
            initial = 0f,
            target = CoreGeometry.TAU,
            repeatMode = RepeatMode.Restart,
            easing = LinearEasing,
            label = "thinking",
            still = 0f
        )

        val executing by transition.animateFloatOrStill(
            enabled = state == CoreState.EXECUTING && motion.executingCycleMs > 0,
            durationMs = motion.executingCycleMs,
            initial = 0f,
            target = CoreGeometry.TAU,
            repeatMode = RepeatMode.Restart,
            easing = LinearEasing,
            label = "executing",
            still = 0f
        )

        val recognizing by transition.animateFloatOrStill(
            enabled = state == CoreState.RECOGNIZING && motion.recognizingCycleMs > 0,
            durationMs = motion.recognizingCycleMs,
            initial = -0.06f,
            target = 0.06f,
            repeatMode = RepeatMode.Reverse,
            easing = motion.gentle,
            label = "recognizing",
            still = 0f
        )

        return Drivers(
            breathing = breathing,
            thinkingAngle = thinking,
            executingAngle = executing,
            recognizingDrift = recognizing
        )
    }

    /** Snapshot of every continuous driver for the current frame. */
    data class Drivers(
        /** 0..1 breathing phase. */
        val breathing: Float,
        /** Radians, inner-arc rotation while thinking. */
        val thinkingAngle: Float,
        /** Radians, inner-arc rotation while executing. */
        val executingAngle: Float,
        /** Radians, subtle drift of the whole shape while recognizing. */
        val recognizingDrift: Float
    )
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatOrStill(
    enabled: Boolean,
    durationMs: Int,
    initial: Float,
    target: Float,
    repeatMode: RepeatMode,
    easing: androidx.compose.animation.core.Easing,
    label: String,
    still: Float
) = animateFloat(
    initialValue = if (enabled) initial else still,
    targetValue = if (enabled) target else still,
    animationSpec = infiniteRepeatable(
        animation = tween(
            durationMillis = if (enabled) durationMs.coerceAtLeast(1) else 1,
            easing = easing
        ),
        repeatMode = repeatMode
    ),
    label = label
)
