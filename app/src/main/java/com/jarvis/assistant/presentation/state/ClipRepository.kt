package com.jarvis.assistant.presentation.state

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.jarvis.assistant.data.preferences.OmnixExperienceStore
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.audio.BluetoothAudioState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of Clip state for the frontend (§13, §23, §40).
 *
 * In this build the OMNIX Clip is the connected Bluetooth audio device that
 * `BluetoothAudioRouter` already owns. This repository does not open a second
 * device stack — it translates the real audio state into the product's own
 * vocabulary and adds the two facts the UI needs and the router does not
 * store: whether the adapter is on, and when the Clip was last seen.
 *
 * Capabilities the device genuinely does not report (battery, firmware,
 * "find my Clip") are expressed as [ClipCapability.Unavailable] rather than
 * being invented (§3, §33).
 */
@Singleton
class ClipRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val experienceStore: OmnixExperienceStore
) {
    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** True when the system Bluetooth adapter is switched on. */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /** True when the device has no Bluetooth radio at all. */
    fun hasBluetoothHardware(): Boolean = bluetoothAdapter != null

    /**
     * Live Clip state.
     *
     * @param searching set by the pairing screen while it is actively looking,
     *        so "Searching" is a real UI intent rather than a guess.
     */
    fun clipState(searching: Flow<Boolean>): Flow<ClipState> = combine(
        bluetoothAudioRouter.audioState,
        bluetoothAudioRouter.isHeadsetPlugged,
        experienceStore.clipLastSeenMillis,
        experienceStore.clipLastDeviceName,
        searching
    ) { audioState, plugged, lastSeen, lastName, isSearching ->
        when {
            !hasBluetoothHardware() -> ClipState.Disconnected(lastName, lastSeen)

            audioState is BluetoothAudioState.Connected -> ClipState.Connected(
                deviceName = audioState.deviceName,
                // The Android audio stack does not expose a headset battery
                // level for arbitrary devices; we never fabricate one.
                battery = ClipCapability.Unavailable,
                isSingleEarbud = audioState.isSingleEarbud
            )

            audioState is BluetoothAudioState.Connecting ->
                ClipState.Connecting(lastName.orEmpty())

            // A wired headset still means "OMNIX can hear you", so it is a
            // connected Clip from the user's point of view.
            plugged -> ClipState.Connected(
                deviceName = lastName.orEmpty(),
                battery = ClipCapability.Unavailable
            )

            !isBluetoothEnabled() -> ClipState.BluetoothOff

            isSearching -> ClipState.Searching

            else -> ClipState.Disconnected(lastName, lastSeen)
        }
    }

    /** Records a real connection so "Last seen" is never invented (§23). */
    suspend fun onClipConnected(deviceName: String) {
        experienceStore.recordClipConnected(deviceName, System.currentTimeMillis())
    }

    /** Refreshes the "last seen" stamp while the Clip stays connected. */
    suspend fun onClipStillConnected() {
        experienceStore.recordClipSeen(System.currentTimeMillis())
    }

    /** Asks the audio layer to re-evaluate the current connection. */
    fun refresh(): Boolean = bluetoothAudioRouter.checkHeadsetConnection()

    /** Hot state for callers that do not drive a search themselves. */
    fun clipStateIn(scope: CoroutineScope, searching: Flow<Boolean>) =
        clipState(searching).stateIn(scope, SharingStarted.WhileSubscribed(5_000), ClipState.Unknown)
}
