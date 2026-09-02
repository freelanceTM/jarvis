package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The OMNIX icon set (§8).
 *
 * Deliberately tiny: History and Me, drawn as thin outlines matching the
 * reference poster. They are geometry rather than a font or a drawable set,
 * so they inherit the Core's line weight exactly and cannot drift from it.
 *
 * New icons are not added casually — every one of them competes with the Core
 * for attention.
 */
object OmnixIcons {

    /** Default optical size for navigation icons. */
    val NavSize: Dp = 22.dp

    /** Line weight, matched to the Core's hairline family. */
    private const val STROKE_RATIO = 0.085f
}

/** A clock: the History destination. */
@Composable
fun OmnixHistoryIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val r = s * 0.42f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)

        drawCircle(color = color, radius = r, center = c, style = Stroke(width = w))

        // Hands at roughly 10:10, the conventional resting pose.
        drawLine(
            color = color,
            start = c,
            end = Offset(c.x, c.y - r * 0.52f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = c,
            end = Offset(c.x + r * 0.40f, c.y),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/** A person: the Me destination. */
@Composable
fun OmnixMeIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f

        drawCircle(
            color = color,
            radius = s * 0.19f,
            center = Offset(cx, s * 0.30f),
            style = Stroke(width = w)
        )

        // Shoulders: a half-round, open at the bottom.
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - s * 0.32f, s * 0.56f),
            size = Size(s * 0.64f, s * 0.56f),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
    }
}

/** A microphone: used by the permission step and the Chat dictation control. */
@Composable
fun OmnixMicIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f
        val capsuleW = s * 0.30f
        val capsuleTop = s * 0.14f
        val capsuleH = s * 0.44f

        drawRoundRectOutline(color, cx, capsuleTop, capsuleW, capsuleH, w)

        // The cradle arc plus the stem.
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - s * 0.26f, s * 0.40f),
            size = Size(s * 0.52f, s * 0.40f),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(cx, s * 0.60f + s * 0.20f),
            end = Offset(cx, s * 0.88f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawRoundRectOutline(
    color: Color,
    centerX: Float,
    top: Float,
    width: Float,
    height: Float,
    strokeWidth: Float
) {
    val radius = width / 2f
    val left = centerX - radius
    val bottom = top + height

    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(left, top),
        size = Size(width, width),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawArc(
        color = color,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(left, bottom - width),
        size = Size(width, width),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawLine(
        color = color,
        start = Offset(left, top + radius),
        end = Offset(left, bottom - radius),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = color,
        start = Offset(left + width, top + radius),
        end = Offset(left + width, bottom - radius),
        strokeWidth = strokeWidth
    )
}

/** A left chevron: returning from a sub-screen. */
@Composable
fun OmnixBackIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val dx = s * 0.16f
        val dy = s * 0.22f

        drawLine(
            color = color,
            start = Offset(cx + dx, cy - dy),
            end = Offset(cx - dx, cy),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx - dx, cy),
            end = Offset(cx + dx, cy + dy),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}
