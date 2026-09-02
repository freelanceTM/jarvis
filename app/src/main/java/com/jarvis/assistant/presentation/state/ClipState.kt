package com.jarvis.assistant.presentation.state

import androidx.compose.runtime.Immutable

/**
 * State of the OMNIX Clip — the wearable the user actually speaks into
 * (§13, §23, §40, §70).
 *
 * In the current product the Clip is the connected Bluetooth headset exposed by
 * `BluetoothAudioRouter`. Capabilities the device does not report are modelled
 * explicitly by [ClipCapability] instead of being invented (§3, §33).
 */
@Immutable
sealed interface ClipState {

    /** Nothing known yet — the very first frame after launch. */
    data object Unknown : ClipState

    /** Bluetooth is switched off at the system level (§13, §49). */
    data object BluetoothOff : ClipState

    /** Looking for the Clip. */
    data object Searching : ClipState

    /** A Clip was found and is being connected. */
    data class Connecting(val deviceName: String) : ClipState

    /** The Clip is connected and voice is available hands-free. */
    data class Connected(
        val deviceName: String,
        /** Battery, as reported by the device. Unknown when unsupported. */
        val battery: ClipCapability<Int> = ClipCapability.Unavailable,
        val isSingleEarbud: Boolean = true
    ) : ClipState

    /** Previously connected, currently not. */
    data class Disconnected(
        val lastDeviceName: String? = null,
        /** Epoch millis of the last confirmed connection, when persisted. */
        val lastSeenMillis: Long? = null
    ) : ClipState

    /** The connection attempt failed and the user can retry. */
    data class ConnectionFailed(val deviceName: String? = null) : ClipState

    /** Connected, but the battery the device reports is low (§71). */
    data class BatteryLow(val deviceName: String, val percent: Int) : ClipState
}

/**
 * A device capability that may genuinely be unavailable.
 *
 * Never render "86%" when the device does not report a battery level — render
 * the honest state instead (§3, §33).
 */
@Immutable
sealed interface ClipCapability<out T> {
    /** The device reported a real value. */
    data class Available<T>(val value: T) : ClipCapability<T>

    /** The connected device does not expose this information. */
    data object Unavailable : ClipCapability<Nothing>

    /** The product will support this, but not in this build. */
    data object ComingSoon : ClipCapability<Nothing>

    /** Supported, but the user has not configured it yet. */
    data object NotConfigured : ClipCapability<Nothing>
}
