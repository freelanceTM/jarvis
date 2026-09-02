package com.jarvis.assistant.presentation.core

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.jarvis.assistant.presentation.design.OmnixTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * **OmnixCore** — brand, state indicator and interaction feedback in one
 * geometry (§7, §10, §91).
 *
 * One component. Eight states. Four scales. The Core transforms between
 * states; it is never unmounted and remounted, and there is never a separate
 * "listening core" or "thinking core" component.
 *
 * The visual is a segmented ring — three arcs, three breaks — sitting in a
 * soft halo of its own colour. The halo is what makes the Core read as light
 * rather than as a drawn outline, and it is the reason the product looks
 * like an object rather than a progress indicator.
 *
 * The Core is **not a button** (§8). It exposes no click handling; a caller
 * that genuinely needs a tap target wraps it explicitly and must also provide
 * a non-visual affordance.
 *
 * Accessibility: the Core is decorative for screen readers by default — the
 * state is always announced by the accompanying label (§55). Pass
 * [contentDescription] only where no label exists (for example the navigation
 * bar item).
 *
 * @param state       current state, the single source of the visual form
 * @param size        rendering size; use `OmnixTheme.coreSizes.*` (§14)
 * @param audioLevel  normalised live amplitude 0..1, used only by
 *                    LISTENING and SPEAKING; must come from a real audio
 *                    source, never from a timer (§33)
 * @param intensity   global damping of the visual weight, 0..1
 */
@Composable
fun OmnixCore(
    state: CoreState,
    modifier: Modifier = Modifier,
    size: Dp = OmnixTheme.coreSizes.home,
    audioLevel: Float = 0f,
    intensity: Float = 1f,
    contentDescription: String? = null
) {
    val colors = OmnixTheme.colors
    val motion = OmnixTheme.motion
    val reduced = OmnixTheme.reducedMotion

    val targetColor = when (state) {
        CoreState.IDLE -> colors.stateIdle
        CoreState.LISTENING -> colors.stateListening
        CoreState.RECOGNIZING -> colors.stateRecognizing
        CoreState.THINKING -> colors.stateThinking
        CoreState.EXECUTING -> colors.stateExecuting
        CoreState.SPEAKING -> colors.stateSpeaking
        CoreState.SUCCESS -> colors.stateSuccess
        CoreState.ERROR -> colors.stateError
    }

    // The colour cross-fades; the object itself persists (§59).
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(motion.colorTransitionMs, easing = motion.standard),
        label = "core_color"
    )

    val base = CoreMotion.baseShape(state)
    val drivers = CoreMotion.rememberDrivers(state, motion)

    // Every scalar is animated, so a state change is a transformation of one
    // object rather than a swap between two.
    val stroke by animateFloatAsState(
        targetValue = base.stroke,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_stroke"
    )
    val pressure by animateFloatAsState(
        targetValue = base.pressure,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_pressure"
    )
    val h1 by animateFloatAsState(
        targetValue = base.h1,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_h1"
    )
    val h2 by animateFloatAsState(
        targetValue = base.h2,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_h2"
    )
    val opacity by animateFloatAsState(
        targetValue = base.opacity * intensity.coerceIn(0f, 1f),
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_opacity"
    )

    /**
     * How closed the ring is: 0 keeps the three signature breaks, 1 seals it
     * into a continuous circle. Only the terminal states seal it, and because
     * the value is animated the gaps visibly close rather than disappearing.
     */
    val closure by animateFloatAsState(
        targetValue = if (base.gaps.isEmpty()) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_closure"
    )

    // Live amplitude, smoothed. Zero unless the state is audio reactive, so a
    // stale RMS value can never animate an idle Core.
    val reactiveTarget = if (state.isAudioReactive && !reduced) {
        audioLevel.coerceIn(0f, 1f) * if (state == CoreState.LISTENING) 0.030f else 0.022f
    } else {
        0f
    }
    val reactive by animateFloatAsState(
        targetValue = reactiveTarget,
        animationSpec = tween(
            durationMillis = if (reactiveTarget > 0f) {
                motion.audioAttackMs.coerceAtLeast(1)
            } else {
                motion.audioReleaseMs.coerceAtLeast(1)
            },
            easing = motion.gentle
        ),
        label = "core_reactive"
    )

    val glyphProgress by animateFloatAsState(
        targetValue = if (state.isTerminal) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_glyph"
    )

    // Dots fade in and out rather than appearing abruptly, and stay hidden
    // entirely when motion is reduced — a rotating element is exactly what
    // that setting exists to suppress.
    val orbitAlpha by animateFloatAsState(
        targetValue = if (
            !reduced && (state == CoreState.RECOGNIZING || state == CoreState.THINKING)
        ) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_orbit"
    )

    val innerSweep by animateFloatAsState(
        targetValue = CoreMotion.innerArcSweep(state),
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_inner"
    )

    val breathScale = 1f + motion.breathingAmplitude * drivers.breathing

    // The gaps narrow towards zero as the ring closes.
    val gaps = if (closure >= 0.999f) {
        emptyList()
    } else {
        CoreGeometry.DEFAULT_GAPS.map { it.copy(sweep = it.sweep * (1f - closure)) }
    }

    val shape = base.copy(
        gaps = gaps,
        h1 = h1,
        h2 = h2,
        stroke = stroke,
        pressure = pressure,
        reactive = reactive,
        phase = drivers.recognizingDrift,
        scale = breathScale,
        opacity = opacity
    )

    val innerRotation = when (state) {
        CoreState.THINKING -> drivers.thinkingAngle
        CoreState.EXECUTING -> drivers.executingAngle
        CoreState.RECOGNIZING -> drivers.recognizingDrift
        else -> 0f
    }

    val glyph = CoreMotion.glyphOf(state)
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription?.let { this.contentDescription = it }
    }

    Box(
        modifier = modifier.size(size).then(semantics),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val sizePx = this.size.minDimension
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = sizePx * CoreGeometry.RADIUS_RATIO

            drawHalo(center, baseRadius, color, shape.opacity)
            drawRing(shape, center, baseRadius, color, shape.opacity)

            if (innerSweep > 0.01f) {
                drawInnerArc(
                    center = center,
                    radius = baseRadius * 0.58f,
                    startAngle = innerRotation - 1.571f,
                    sweep = innerSweep,
                    color = color,
                    widthPx = CoreGeometry.STROKE_RATIO * baseRadius * 0.45f,
                    alpha = shape.opacity * 0.45f
                )
            }

            // Orbiting dots mark the two states where OMNIX is working on
            // something the user cannot see: interpreting speech, and
            // reasoning. They are the poster's substitute for a spinner —
            // three small points on the ring, not a rotating arc (§29).
            if (orbitAlpha > 0.01f) {
                drawOrbitDots(
                    center = center,
                    radius = baseRadius,
                    angle = innerRotation,
                    color = color,
                    dotRadius = CoreGeometry.STROKE_RATIO * baseRadius * 0.62f,
                    alpha = shape.opacity * orbitAlpha
                )
            }

            if (glyphProgress > 0.01f) {
                when (glyph) {
                    CoreGlyph.CHECK -> drawCheckMark(
                        center = center,
                        unit = baseRadius * 0.42f,
                        color = color,
                        widthPx = CoreGeometry.STROKE_RATIO * baseRadius * 0.85f,
                        progress = glyphProgress
                    )

                    CoreGlyph.ALERT -> drawAlertMark(
                        center = center,
                        unit = baseRadius * 0.46f,
                        color = color,
                        widthPx = CoreGeometry.STROKE_RATIO * baseRadius * 0.85f,
                        progress = glyphProgress
                    )

                    CoreGlyph.NONE -> Unit
                }
            }
        }
    }
}

/**
 * The halo. A radial gradient centred on the ring, fading to transparent just
 * outside it — this is what gives the Core its sense of emitted light (§11).
 *
 * It is drawn first so the ring always sits crisply on top of it.
 */
private fun DrawScope.drawHalo(
    center: Offset,
    baseRadius: Float,
    color: Color,
    alpha: Float
) {
    val haloRadius = baseRadius * 2.1f
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to color.copy(alpha = 0f),
                0.42f to color.copy(alpha = 0.10f * alpha),
                0.52f to color.copy(alpha = 0.20f * alpha),
                0.62f to color.copy(alpha = 0.07f * alpha),
                1.00f to color.copy(alpha = 0f)
            ),
            center = center,
            radius = haloRadius
        ),
        radius = haloRadius,
        center = center
    )
}

/**
 * Draws the ring as a chain of pressure-varying segments.
 *
 * Segments rather than `drawCircle` are what allow the stroke pressure and the
 * organic radius to exist at all. Each drawn arc gets rounded caps at its two
 * ends, which is what makes the breaks look cut rather than erased.
 */
private fun DrawScope.drawRing(
    shape: CoreGeometry.Shape,
    center: Offset,
    baseRadius: Float,
    color: Color,
    alpha: Float
) {
    val segments = CoreGeometry.SEGMENTS
    for (i in 0 until segments) {
        val t0 = CoreGeometry.TAU * i / segments
        val t1 = CoreGeometry.TAU * (i + 1) / segments
        if (CoreGeometry.isHidden(shape, t0) || CoreGeometry.isHidden(shape, t1)) continue

        val p0 = CoreGeometry.pointAt(shape, center, baseRadius, t0)
        val p1 = CoreGeometry.pointAt(shape, center, baseRadius, t1)
        val width = CoreGeometry.strokeAt(shape, (t0 + t1) / 2f, baseRadius)

        drawLine(
            color = color,
            start = p0,
            end = p1,
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = alpha
        )
    }
}

private fun DrawScope.drawInnerArc(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweep: Float,
    color: Color,
    widthPx: Float,
    alpha: Float
) {
    val steps = 48
    var previous = Offset(
        center.x + radius * cos(startAngle),
        center.y + radius * sin(startAngle)
    )
    for (i in 1..steps) {
        val t = startAngle + sweep * i / steps
        val point = Offset(center.x + radius * cos(t), center.y + radius * sin(t))
        drawLine(
            color = color,
            start = previous,
            end = point,
            strokeWidth = widthPx,
            cap = StrokeCap.Round,
            alpha = alpha
        )
        previous = point
    }
}

/** SUCCESS: a check mark, drawn stroke by stroke (§22). */
private fun DrawScope.drawCheckMark(
    center: Offset,
    unit: Float,
    color: Color,
    widthPx: Float,
    progress: Float
) {
    val a = Offset(center.x - unit, center.y + unit * 0.05f)
    val b = Offset(center.x - unit * 0.22f, center.y + unit * 0.68f)
    val c = Offset(center.x + unit, center.y - unit * 0.62f)

    val first = (progress / 0.45f).coerceIn(0f, 1f)
    val second = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)

    drawLine(
        color = color,
        start = a,
        end = Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first),
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
        alpha = progress
    )
    if (second > 0f) {
        drawLine(
            color = color,
            start = b,
            end = Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second),
            strokeWidth = widthPx,
            cap = StrokeCap.Round,
            alpha = progress
        )
    }
}

/**
 * ERROR: an exclamation mark — a stem and a dot.
 *
 * It is deliberately calm: no shake, no flash. The specification asks for
 * errors that are clear, not alarming (§18, §50).
 */
private fun DrawScope.drawAlertMark(
    center: Offset,
    unit: Float,
    color: Color,
    widthPx: Float,
    progress: Float
) {
    val top = Offset(center.x, center.y - unit * 0.70f)
    val bottom = Offset(center.x, center.y + unit * 0.18f)

    drawLine(
        color = color,
        start = top,
        end = Offset(top.x, top.y + (bottom.y - top.y) * progress),
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
        alpha = progress
    )
    drawCircle(
        color = color,
        radius = widthPx * 0.55f,
        center = Offset(center.x, center.y + unit * 0.62f),
        alpha = progress
    )
}

/**
 * Three dots riding the ring, 120° apart.
 *
 * They sit exactly on the ring's radius so they read as part of the Core
 * rather than as orbiting satellites, and they are small enough that at the
 * navigation-bar size they simply disappear instead of turning into noise.
 */
private fun DrawScope.drawOrbitDots(
    center: Offset,
    radius: Float,
    angle: Float,
    color: Color,
    dotRadius: Float,
    alpha: Float
) {
    if (dotRadius <= 0.35f) return

    repeat(ORBIT_DOT_COUNT) { index ->
        val theta = angle + index * ORBIT_STEP_RADIANS
        // Trailing dots are dimmer, which is what gives the group a
        // direction of travel without any motion blur.
        val falloff = 1f - index * 0.28f
        drawCircle(
            color = color,
            radius = dotRadius,
            center = Offset(
                x = center.x + radius * cos(theta),
                y = center.y + radius * sin(theta)
            ),
            alpha = (alpha * falloff).coerceIn(0f, 1f)
        )
    }
}

private const val ORBIT_DOT_COUNT = 3

/** 120° in radians. */
private const val ORBIT_STEP_RADIANS = 2.0944f
