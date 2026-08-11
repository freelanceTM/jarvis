package com.jarvis.assistant.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.presentation.theme.*

@Composable
fun JarvisOrbVisualizer(
    assistantState: VoiceAssistantState,
    modifier: Modifier = Modifier,
    rmsDb: Float = 0f
) {
    // Автономная GPU-анимация (без лишних recomposition дерева Compose)
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_orb_smooth")

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val (primaryGlow, secondaryGlow) = when (assistantState) {
        is VoiceAssistantState.Idle -> JarvisCyanPrimary to JarvisBlueAccent
        is VoiceAssistantState.Listening -> JarvisGreen to JarvisCyanPrimary
        is VoiceAssistantState.Recognizing -> JarvisAmber to JarvisGreen
        is VoiceAssistantState.Thinking -> JarvisPurpleAccent to JarvisCyanPrimary
        is VoiceAssistantState.Speaking -> JarvisCyanPrimary to JarvisBlueAccent
        is VoiceAssistantState.Error -> JarvisRed to Color(0xFFFF8A80)
    }

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2) * 0.65f * pulseScale

            // Внешний ореол
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = baseRadius * 1.5f
                ),
                radius = baseRadius * 1.4f,
                center = center
            )

            // Вращающееся внешнее кольцо
            rotate(rotationAngle, pivot = center) {
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.75f),
                    radius = baseRadius * 1.1f,
                    center = center,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(25f, 15f), 0f)
                    )
                )
            }

            // Внутреннее контр-кольцо
            rotate(-rotationAngle * 1.4f, pivot = center) {
                drawCircle(
                    color = secondaryGlow.copy(alpha = 0.5f),
                    radius = baseRadius * 0.85f,
                    center = center,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                    )
                )
            }

            // Светящееся ядро реактора
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f),
                        primaryGlow.copy(alpha = 0.85f),
                        secondaryGlow.copy(alpha = 0.3f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 0.65f
                ),
                radius = baseRadius * 0.6f,
                center = center
            )
        }
    }
}
