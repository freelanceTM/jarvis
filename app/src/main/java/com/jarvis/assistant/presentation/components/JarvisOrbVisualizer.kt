package com.jarvis.assistant.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import com.jarvis.assistant.presentation.theme.JarvisAmber
import com.jarvis.assistant.presentation.theme.JarvisBlueAccent
import com.jarvis.assistant.presentation.theme.JarvisCyanPrimary
import com.jarvis.assistant.presentation.theme.JarvisGreen
import com.jarvis.assistant.presentation.theme.JarvisPurpleAccent
import com.jarvis.assistant.presentation.theme.JarvisRed

@Composable
fun JarvisOrbVisualizer(
    assistantState: VoiceAssistantState,
    modifier: Modifier = Modifier,
    rmsDb: Float = 0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_orb_transition")

    // Continuous rotation for outer reactor rings
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pulsating radius
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Core Glow color based on assistant state
    val (primaryGlow, secondaryGlow) = when (assistantState) {
        is VoiceAssistantState.Idle -> JarvisCyanPrimary to JarvisBlueAccent
        is VoiceAssistantState.Listening -> JarvisGreen to JarvisCyanPrimary
        is VoiceAssistantState.Recognizing -> JarvisAmber to JarvisGreen
        is VoiceAssistantState.Thinking -> JarvisPurpleAccent to JarvisCyanPrimary
        is VoiceAssistantState.Speaking -> JarvisCyanPrimary to JarvisBlueAccent
        is VoiceAssistantState.Error -> JarvisRed to Color(0xFFFF8A80)
    }

    val dynamicAudioMultiplier = (1.0f + (rmsDb.coerceAtLeast(0f) / 10f) * 0.4f)

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2) * 0.65f * pulseScale * dynamicAudioMultiplier

            // 1. Outermost Ambient Glow Ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = baseRadius * 1.6f
                ),
                radius = baseRadius * 1.5f,
                center = center
            )

            // 2. Outer Rotating Dashed Ring
            rotate(rotationAngle, pivot = center) {
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.7f),
                    radius = baseRadius * 1.15f,
                    center = center,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f, 10f, 20f), 0f)
                    )
                )
            }

            // 3. Middle Counter-Rotating Ring
            rotate(-rotationAngle * 1.5f, pivot = center) {
                drawCircle(
                    color = secondaryGlow.copy(alpha = 0.5f),
                    radius = baseRadius * 0.9f,
                    center = center,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                    )
                )
            }

            // 4. Glowing Arc Reactor Core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.9f),
                        primaryGlow.copy(alpha = 0.85f),
                        secondaryGlow.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 0.7f
                ),
                radius = baseRadius * 0.65f,
                center = center
            )

            // 5. Central Concentric Solid Point
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx() * pulseScale,
                center = center
            )
        }
    }
}
