package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.state.ClipState

/**
 * The Clip status line that sits under the OMNIX wordmark on Home (§9, §23).
 *
 * It answers one question — "can OMNIX hear me right now?" — and nothing else.
 * No battery percentage is displayed unless the device actually reports one,
 * and no signal strength, codec or MAC address ever appears here (§3, §33).
 */
@Composable
fun ClipStatusBar(
    clip: ClipState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing

    val label = clipLabel(clip)
    val dotColor = when (clip) {
        is ClipState.Connected -> colors.stateListening
        is ClipState.Connecting, ClipState.Searching -> colors.stateRecognizing
        ClipState.BluetoothOff, is ClipState.ConnectionFailed -> colors.stateError
        else -> colors.textDisabled
    }

    val description = stringResource(R.string.omnix_a11y_clip_status, label)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = spacing.touchTarget)
            .padding(vertical = spacing.xs)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OmnixStatusDot(color = dotColor)
        Text(
            text = label,
            style = OmnixTheme.typography.status,
            color = colors.textSecondary
        )
        if (!isOnline) {
            Text(
                text = "·",
                style = OmnixTheme.typography.status,
                color = colors.textDisabled
            )
            Text(
                text = stringResource(R.string.omnix_status_offline),
                style = OmnixTheme.typography.status,
                color = colors.textDisabled
            )
        }
    }
}

/** Human copy for each Clip state — the only place this mapping exists. */
@Composable
fun clipLabel(clip: ClipState): String = when (clip) {
    is ClipState.Connected -> stringResource(R.string.omnix_status_clip_connected)
    is ClipState.Connecting -> stringResource(R.string.omnix_status_clip_connecting)
    ClipState.Searching -> stringResource(R.string.omnix_status_clip_searching)
    ClipState.BluetoothOff -> stringResource(R.string.omnix_status_bluetooth_off)
    is ClipState.ConnectionFailed -> stringResource(R.string.omnix_error_clip_failed_title)
    is ClipState.BatteryLow -> stringResource(R.string.omnix_clip_battery_low_title)
    is ClipState.Disconnected -> stringResource(R.string.omnix_status_clip_disconnected)
    ClipState.Unknown -> stringResource(R.string.omnix_status_clip_disconnected)
}

/** Colour used for the Clip dot; exposed for reuse on the Devices screen. */
@Composable
fun clipDotColor(clip: ClipState): Color = when (clip) {
    is ClipState.Connected -> OmnixTheme.colors.stateListening
    is ClipState.Connecting, ClipState.Searching -> OmnixTheme.colors.stateRecognizing
    ClipState.BluetoothOff, is ClipState.ConnectionFailed -> OmnixTheme.colors.stateError
    else -> OmnixTheme.colors.textDisabled
}
