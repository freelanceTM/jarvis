package com.jarvis.assistant.presentation.core

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Core is the identity of the product, so its geometry is pure, testable
 * maths rather than something hidden inside a draw call (§7, §11).
 *
 * The expected angles below come from the approved visual reference.
 */
class CoreGeometryTest {

    private val base = CoreGeometry.Shape()

    @Test
    fun `radius stays within the organic tolerance of the base radius`() {
        // The harmonics must be felt, not seen: the ring may never wobble by
        // more than a few percent or it stops reading as a single object.
        val baseRadius = 100f
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (i in 0 until CoreGeometry.SEGMENTS) {
            val theta = CoreGeometry.TAU * i / CoreGeometry.SEGMENTS
            val r = CoreGeometry.radiusAt(base, baseRadius, theta)
            min = minOf(min, r)
            max = maxOf(max, r)
        }
        assertTrue("radius collapsed: $min", min > baseRadius * 0.95f)
        assertTrue("radius ballooned: $max", max < baseRadius * 1.05f)
    }

    @Test
    fun `scale applies uniformly so breathing cannot distort the shape`() {
        val breathing = base.copy(scale = 1.015f)
        val theta = 1.3f
        val plain = CoreGeometry.radiusAt(base, 100f, theta)
        val scaled = CoreGeometry.radiusAt(breathing, 100f, theta)
        assertEquals(plain * 1.015f, scaled, 0.001f)
    }

    @Test
    fun `audio reactivity only deforms outward and only when requested`() {
        val theta = 0.9f
        val quiet = CoreGeometry.radiusAt(base, 100f, theta)
        val loud = CoreGeometry.radiusAt(base.copy(reactive = 0.03f), 100f, theta)
        assertTrue("reactive deformation must never shrink the ring", loud >= quiet)
    }

    @Test
    fun `reactive amplitude of zero reproduces the idle ring exactly`() {
        // Reduced motion and every non-audio state pass reactive = 0; the
        // result must be bit-for-bit the calm shape (§56).
        for (i in 0 until 60) {
            val theta = CoreGeometry.TAU * i / 60
            assertEquals(
                CoreGeometry.radiusAt(base, 80f, theta),
                CoreGeometry.radiusAt(base.copy(reactive = 0f), 80f, theta),
                0f
            )
        }
    }

    @Test
    fun `all three signature breaks are hidden and the long arc is drawn`() {
        // The three-break pattern is the identity of the Core: if a refactor
        // silently drops one, the product stops looking like itself.
        assertEquals(3, base.gaps.size)
        base.gaps.forEach { gap ->
            assertTrue(
                "break at ${gap.center} must be hidden",
                CoreGeometry.isHidden(base, gap.center)
            )
        }
        assertFalse(CoreGeometry.isHidden(base, Math.toRadians(180.0).toFloat()))
    }

    @Test
    fun `gap detection is stable across wrapped angles`() {
        // The break centred on 0 straddles the wrap point, which is the branch
        // most likely to break.
        val zeroGap = base.gaps.first().center
        assertTrue(CoreGeometry.isHidden(base, zeroGap))
        assertTrue(CoreGeometry.isHidden(base, zeroGap + CoreGeometry.TAU))
        assertTrue(CoreGeometry.isHidden(base, zeroGap - CoreGeometry.TAU))
    }

    @Test
    fun `arcs are the exact complement of the gaps`() {
        val arcs = CoreGeometry.arcsOf(base)
        assertEquals(3, arcs.size)

        val totalArc = arcs.sumOf { it.second.toDouble() }
        val totalGap = base.gaps.sumOf { it.sweep.toDouble() }
        assertEquals(CoreGeometry.TAU.toDouble(), totalArc + totalGap, 0.001)

        // The reference proportions: one long arc anchoring two shorter ones.
        val degrees = arcs.map { Math.toDegrees(it.second.toDouble()) }.sorted()
        assertEquals(80.0, degrees[0], 1.5)
        assertEquals(80.0, degrees[1], 1.5)
        assertEquals(175.0, degrees[2], 1.5)
    }

    @Test
    fun `a sealed ring has no hidden angles`() {
        // SUCCESS and ERROR close the ring completely.
        val sealedShape = base.copy(gaps = emptyList())
        for (i in 0 until 90) {
            val theta = CoreGeometry.TAU * i / 90
            assertFalse(CoreGeometry.isHidden(sealedShape, theta))
        }
        assertEquals(1, CoreGeometry.arcsOf(sealedShape).size)
    }

    @Test
    fun `phase rotates the breaks with the shape`() {
        // Recognizing drifts the whole object; the gaps must travel with it,
        // otherwise the ring appears to slip inside its own outline.
        val rotated = base.copy(phase = 0.5f)
        val gapCenter = base.gaps[1].center
        assertTrue(CoreGeometry.isHidden(base, gapCenter))
        assertTrue(CoreGeometry.isHidden(rotated, gapCenter + 0.5f))
    }

    @Test
    fun `stroke scales linearly with the radius`() {
        // Deriving the stroke from the radius is what keeps the Core visually
        // identical at 30 dp in the nav bar and at 240 dp in focus.
        val theta = 2.0f
        val atR = CoreGeometry.strokeAt(base, theta, 50f)
        val atDoubleR = CoreGeometry.strokeAt(base, theta, 100f)
        assertEquals(atR * 2f, atDoubleR, 0.0001f)
    }

    @Test
    fun `stroke pressure never produces a zero or negative width`() {
        for (i in 0 until CoreGeometry.SEGMENTS) {
            val theta = CoreGeometry.TAU * i / CoreGeometry.SEGMENTS
            val w = CoreGeometry.strokeAt(base, theta, 60f)
            assertTrue("stroke vanished at $theta: $w", w > 0f)
        }
    }

    @Test
    fun `points lie on the computed radius around the given centre`() {
        val center = Offset(50f, 70f)
        val theta = 0.42f
        val p = CoreGeometry.pointAt(base, center, 60f, theta)
        val expected = CoreGeometry.radiusAt(base, 60f, theta)
        val actual = hypot(p.x - center.x, p.y - center.y)
        assertTrue(abs(expected - actual) < 0.01f)
    }

    @Test
    fun `every core state maps to exactly one audio and busy classification`() {
        // Guards the "one Core, eight states" rule: no state may be both busy
        // and audio reactive, because the motion systems would fight (§7).
        CoreState.values().forEach { state ->
            assertFalse(
                "$state cannot be busy and audio-reactive at once",
                state.isBusy && state.isAudioReactive
            )
        }
        assertTrue(CoreState.LISTENING.isAudioReactive)
        assertTrue(CoreState.SPEAKING.isAudioReactive)
        assertTrue(CoreState.THINKING.isBusy)
        assertTrue(CoreState.EXECUTING.isBusy)
        assertTrue(CoreState.SUCCESS.isTerminal)
        assertTrue(CoreState.ERROR.isTerminal)
        assertFalse(CoreState.IDLE.isBusy)
        assertFalse(CoreState.IDLE.isAudioReactive)
    }

    @Test
    fun `only the terminal states carry a glyph and the ring seals for them`() {
        CoreState.values().forEach { state ->
            val shape = CoreMotion.baseShape(state)
            val glyph = CoreMotion.glyphOf(state)
            if (state.isTerminal) {
                assertTrue("$state must seal the ring", shape.gaps.isEmpty())
                assertTrue("$state must carry a glyph", glyph != CoreGlyph.NONE)
            } else {
                assertEquals("$state must keep the three breaks", 3, shape.gaps.size)
                assertEquals("$state must have no glyph", CoreGlyph.NONE, glyph)
            }
        }
        assertEquals(CoreGlyph.CHECK, CoreMotion.glyphOf(CoreState.SUCCESS))
        assertEquals(CoreGlyph.ALERT, CoreMotion.glyphOf(CoreState.ERROR))
    }
}
