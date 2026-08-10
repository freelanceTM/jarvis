package com.jarvis.assistant.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.presentation.theme.JarvisGreen
import com.jarvis.assistant.presentation.theme.JarvisRed
import com.jarvis.assistant.presentation.theme.JarvisSurfaceVariant
import com.jarvis.assistant.presentation.theme.TextPrimary
import com.jarvis.assistant.presentation.theme.TextSecondary

@Composable
fun StatusBadgeRow(
    isMicActive: Boolean,
    isOnline: Boolean,
    isBluetoothConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Network Status Badge
        StatusChip(
            icon = if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
            label = if (isOnline) "Онлайн" else "Офлайн",
            isActive = isOnline,
            activeColor = JarvisGreen,
            inactiveColor = JarvisRed
        )

        // Mic Status Badge
        StatusChip(
            icon = if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
            label = if (isMicActive) "Микрофон активен" else "Микрофон свободен",
            isActive = isMicActive,
            activeColor = JarvisGreen,
            inactiveColor = TextSecondary
        )

        // Bluetooth Headset Status Badge
        if (isBluetoothConnected) {
            StatusChip(
                icon = Icons.Default.Bluetooth,
                label = "Гарнитура",
                isActive = true,
                activeColor = JarvisGreen,
                inactiveColor = TextSecondary
            )
        }
    }
}

@Composable
fun StatusChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val indicatorColor by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        label = "chipColor"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(JarvisSurfaceVariant.copy(alpha = 0.8f))
            .border(1.dp, indicatorColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = indicatorColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )
    }
}
