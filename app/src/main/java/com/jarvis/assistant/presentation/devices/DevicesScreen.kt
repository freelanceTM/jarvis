package com.jarvis.assistant.presentation.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.ClipStatusBar
import com.jarvis.assistant.presentation.components.OmnixDivider
import com.jarvis.assistant.presentation.components.OmnixEmptyState
import com.jarvis.assistant.presentation.components.OmnixPanel
import com.jarvis.assistant.presentation.components.OmnixStatusDot
import com.jarvis.assistant.presentation.components.clipDotColor
import com.jarvis.assistant.presentation.components.clipLabel
import com.jarvis.assistant.presentation.components.OmnixScreenHeader
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.state.ClipCapability
import com.jarvis.assistant.presentation.state.ClipState
import java.text.DateFormat
import java.util.Date

/**
 * Devices — Clip-centric, not a Bluetooth manager (§23, §40).
 *
 * The screen shows the Clip first and only mentions other audio devices when
 * they matter. Every row is a fact the system actually reports: when the
 * device does not expose its battery level, the row says so instead of
 * showing an invented percentage (§3, §33).
 */
@Composable
fun DevicesScreen(
    clip: ClipState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onConnect: () -> Unit = {},
    onOpenSystemBluetooth: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))

        OmnixScreenHeader(
            title = stringResource(R.string.omnix_devices_title),
            onBack = onBack
        )

        Spacer(Modifier.height(spacing.lg))

        when (clip) {
            is ClipState.Connected -> ConnectedClip(clip)

            is ClipState.BatteryLow -> ConnectedClip(
                ClipState.Connected(
                    deviceName = clip.deviceName,
                    battery = ClipCapability.Available(clip.percent)
                )
            )

            ClipState.BluetoothOff -> OmnixEmptyState(
                title = stringResource(R.string.omnix_error_bt_off_title),
                description = stringResource(R.string.omnix_error_bt_off_body),
                actionLabel = stringResource(R.string.omnix_error_bt_off_action),
                onAction = onOpenSystemBluetooth
            )

            is ClipState.Connecting, ClipState.Searching -> ClipStatusBar(
                clip = clip,
                isOnline = isOnline
            )

            else -> OmnixEmptyState(
                title = stringResource(R.string.omnix_devices_empty_title),
                description = stringResource(R.string.omnix_devices_empty_body),
                actionLabel = stringResource(R.string.omnix_devices_connect),
                onAction = onConnect
            )
        }

        if (clip is ClipState.Disconnected && clip.lastSeenMillis != null) {
            Spacer(Modifier.height(spacing.lg))
            LastSeenRow(clip.lastSeenMillis)
        }

        Spacer(Modifier.height(spacing.xxl))
    }
}

@Composable
private fun ConnectedClip(clip: ClipState.Connected) {
    val spacing = OmnixTheme.spacing

    OmnixPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OmnixStatusDot(color = clipDotColor(clip))
            Spacer(Modifier.width(spacing.xs))
            Column {
                Text(
                    text = clip.deviceName.ifBlank {
                        stringResource(R.string.omnix_devices_clip_name)
                    },
                    style = OmnixTheme.typography.heading,
                    color = OmnixTheme.colors.textPrimary
                )
                Text(
                    text = clipLabel(clip),
                    style = OmnixTheme.typography.caption,
                    color = OmnixTheme.colors.textSecondary
                )
            }
        }

        Spacer(Modifier.height(spacing.md))
        OmnixDivider()
        Spacer(Modifier.height(spacing.md))

        // Battery: shown only when the device genuinely reports it.
        DetailRow(
            label = stringResource(R.string.omnix_devices_battery),
            value = when (val battery = clip.battery) {
                is ClipCapability.Available -> stringResource(
                    R.string.omnix_percent, battery.value
                )
                ClipCapability.Unavailable -> stringResource(
                    R.string.omnix_devices_battery_unavailable
                )
                ClipCapability.ComingSoon -> stringResource(R.string.omnix_coming_soon)
                ClipCapability.NotConfigured -> stringResource(R.string.omnix_not_configured)
            }
        )

        DetailRow(
            label = stringResource(R.string.omnix_devices_connection),
            value = stringResource(R.string.omnix_devices_connection_active)
        )

        Spacer(Modifier.height(spacing.md))

        // "Find my Clip" is a real product feature that this build cannot
        // perform, so it is stated as such rather than shown as a dead button.
        Text(
            text = stringResource(R.string.omnix_clip_find_coming_soon),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textDisabled
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OmnixTheme.spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textSecondary
        )
        Text(
            text = value,
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textPrimary
        )
    }
}

@Composable
private fun LastSeenRow(lastSeenMillis: Long) {
    val formatted = remember(lastSeenMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(lastSeenMillis))
    }
    Text(
        text = stringResource(R.string.omnix_clip_last_seen, formatted),
        style = OmnixTheme.typography.caption,
        color = OmnixTheme.colors.textTertiary
    )
}
