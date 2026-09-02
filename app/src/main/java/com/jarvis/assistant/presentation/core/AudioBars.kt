package com.jarvis.assistant.presentation.core

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.presentation.design.OmnixTheme
import kotlin.math.abs
import kotlin.math.sin

/**
 * The symmetrical audio bars that flank the Core while it listens or speaks
 * (reference poster, states 2, 3 and 6).
 *
 * This is **not** the "giant waveform" the specification forbids (§29): the
 * bars are small, quiet, sit outside the Core and never become the main
 * subject of the screen. They exist so that speech feels heard.
 *
 * The envelope is driven by the real microphone amplitude. When
 * [level] is zero — or motion is reduced — every bar collapses to its
 * resting height, so nothing ever animates without a genuine signal (§33).
 *
 * @param level     normalised live amplitude, 0..1
 * @param mirrored  draws the tall end towards the Core, for the left side
 */
@Composable
fun OmnixAudioBars(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    barCount: Int = BAR_COUNT,
    width: Dp = 34.dp,
    height: Dp = 34.dp
) {
    val motion = OmnixTheme.motion
    val reduced = OmnixTheme.reducedMotion

    val target = if (reduced) 0f else level.coerceIn(0f, 1f)
    val amplitude by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (target > 0f) {
                motion.audioAttackMs.coerceAtLeast(1)
            } else {
                motion.audioReleaseMs.coerceAtLeast(1)
            },
            easing = motion.gentle
        ),
        label = "audio_bars"
    )

    Canvas(modifier = modifier.size(width, height)) {
        val w = size.width
        val h = size.height
        val slot = w / barCount
        val barWidth = slot * 0.34f

        for (i in 0 until barCount) {
            // Position within the group, 0 at the outer edge, 1 next to the Core.
            val position = if (mirrored) (barCount - 1 - i) / (barCount - 1f) else i / (barCount - 1f)

            // Bars nearest the Core are tallest: the group reads as sound
            // radiating from the object rather than as a standalone chart.
            val envelope = 0.25f + 0.75f * position

            // A fixed interference pattern keeps neighbouring bars unequal,
            // which is what makes the group look like sound and not a bar
            // chart. It is a function of the index, never of a clock.
            val texture = 0.55f + 0.45f * abs(sin(i * 1.7f + 0.6f))

            val restHeight = h * 0.10f
            val barHeight = restHeight + (h * 0.92f - restHeight) * envelope * texture * amplitude

            val x = slot * i + slot / 2f
            val half = barHeight / 2f

            drawLine(
                color = color,
                start = Offset(x, h / 2f - half),
                end = Offset(x, h / 2f + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
                alpha = 0.35f + 0.65f * amplitude
            )
        }
    }
}

private const val BAR_COUNT = 9
