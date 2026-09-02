package com.jarvis.assistant.presentation.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure geometry of the Core — no Android dependency, so it is unit-tested on
 * the JVM (§7, §11).
 *
 * The Core is a **segmented ring**: three arcs separated by three breaks, not
 * a circle with a single aperture. The measurements below were taken from the
 * approved visual reference:
 *
 * ```
 * break at   0°  (6° wide)   arc   3°..83°    80°
 * break at  87.5° (9° wide)  arc  92°..267°  175°
 * break at 272°  (10° wide)  arc 277°..357°   80°
 * ```
 *
 * The long 175° arc anchors the shape; the two 80° arcs balance it. The
 * asymmetry is what makes the ring read as a designed object rather than a
 * loading spinner.
 *
 * Angles follow the Canvas convention: radians, 0 = right, increasing
 * clockwise (because y grows downward).
 */
object CoreGeometry {

    const val TAU: Float = (2.0 * PI).toFloat()

    /** Fraction of the half-size occupied by the ring radius. */
    const val RADIUS_RATIO: Float = 0.36f

    /** Number of straight segments approximating the full ring. */
    const val SEGMENTS: Int = 180

    /**
     * Stroke width as a fraction of the radius, measured from the reference:
     * 6 px of stroke on a 53 px radius. Expressing it relative to the radius
     * keeps the Core visually identical at 30 dp and at 240 dp (§14).
     */
    const val STROKE_RATIO: Float = 0.11f

    /** One break in the ring. */
    @Immutable
    data class Gap(
        /** Centre of the break, radians. */
        val center: Float,
        /** Angular width of the break, radians. */
        val sweep: Float
    )

    /**
     * The signature break pattern. Values are the measured reference angles.
     */
    val DEFAULT_GAPS: List<Gap> = listOf(
        Gap(center = 0f, sweep = 0.105f),          // 0°, 6°
        Gap(center = 1.527f, sweep = 0.157f),      // 87.5°, 9°
        Gap(center = 4.747f, sweep = 0.175f)       // 272°, 10°
    )

    /**
     * Immutable description of one Core rendering. Every animated value is
     * passed in, so the same instance is reproducible from a state + a clock.
     */
    @Immutable
    data class Shape(
        /** The breaks in the ring. */
        val gaps: List<Gap> = DEFAULT_GAPS,
        /** First harmonic amplitude, as a fraction of the base radius. */
        val h1: Float = 0.010f,
        val h1Phase: Float = 0.6f,
        /** Second harmonic amplitude. */
        val h2: Float = 0.012f,
        val h2Phase: Float = 2.1f,
        /** Stroke width as a fraction of the radius. */
        val stroke: Float = STROKE_RATIO,
        /** How strongly the stroke varies along the ring, 0..1. */
        val pressure: Float = 0.35f,
        val pressurePhase: Float = 1.2f,
        /** Live-amplitude deformation, as a fraction of the radius. */
        val reactive: Float = 0f,
        /** Number of lobes of the reactive deformation. */
        val reactiveLobes: Int = 7,
        /** Rotational offset applied to harmonics, lobes and gaps. */
        val phase: Float = 0f,
        /** Uniform scale, used for breathing. */
        val scale: Float = 1f,
        /** Overall opacity of the ring. */
        val opacity: Float = 0.92f
    )

    /**
     * Radius at [theta], including the organic harmonics and — when the Core
     * is listening or speaking — the live reactive deformation.
     */
    fun radiusAt(shape: Shape, baseRadius: Float, theta: Float): Float {
        val t = theta + shape.phase
        var r = baseRadius * (
            1f +
                shape.h1 * cos(t + shape.h1Phase) +
                shape.h2 * cos(2f * t + shape.h2Phase)
            )
        if (shape.reactive > 0f) {
            val lobe = sin(shape.reactiveLobes * t + 0.7f)
            r += baseRadius * shape.reactive * max(0f, lobe) * max(0f, lobe)
        }
        return r * shape.scale
    }

    /**
     * Stroke width at [theta], in pixels.
     *
     * The width is derived from the radius rather than from the component
     * size, so harmonics and breathing never desynchronise it from the ring.
     */
    fun strokeAt(shape: Shape, theta: Float, baseRadius: Float): Float {
        val modulation = 0.5f * (1f + cos(theta + shape.phase + shape.pressurePhase))
        val factor = 1f - shape.pressure * 0.5f + shape.pressure * modulation
        return shape.stroke * baseRadius * factor
    }

    /** Point on the ring at [theta], relative to [center]. */
    fun pointAt(shape: Shape, center: Offset, baseRadius: Float, theta: Float): Offset {
        val r = radiusAt(shape, baseRadius, theta)
        return Offset(center.x + r * cos(theta), center.y + r * sin(theta))
    }

    /** True when [theta] falls inside any break in the ring. */
    fun isHidden(shape: Shape, theta: Float): Boolean {
        val t = theta - shape.phase
        return shape.gaps.any { gap ->
            inArc(t, gap.center - gap.sweep / 2f, gap.sweep)
        }
    }

    /**
     * The drawn arcs, as (startAngle, sweep) pairs in radians — the complement
     * of the gaps. Used for stroked-arc rendering and by the tests.
     */
    fun arcsOf(shape: Shape): List<Pair<Float, Float>> {
        if (shape.gaps.isEmpty()) return listOf(0f to TAU)
        val sorted = shape.gaps
            .map { norm(it.center - it.sweep / 2f) to it.sweep }
            .sortedBy { it.first }
        val arcs = ArrayList<Pair<Float, Float>>(sorted.size)
        for (i in sorted.indices) {
            val (start, sweep) = sorted[i]
            val arcStart = start + sweep
            val nextStart = sorted[(i + 1) % sorted.size].first
            var arcSweep = nextStart - arcStart
            if (arcSweep <= 0f) arcSweep += TAU
            arcs += norm(arcStart + shape.phase) to arcSweep
        }
        return arcs
    }

    private fun inArc(theta: Float, start: Float, sweep: Float): Boolean {
        val t = norm(theta)
        val a = norm(start)
        val b = norm(start + sweep)
        return if (a <= b) t in a..b else t >= a || t <= b
    }

    private fun norm(v: Float): Float {
        var x = v % TAU
        if (x < 0f) x += TAU
        return x
    }
}
