package com.jarvis.assistant.voice.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BluetoothAudioState {
    data object Disconnected : BluetoothAudioState
    data object Connecting : BluetoothAudioState
    data class Connected(val deviceName: String, val isSingleEarbud: Boolean = true) : BluetoothAudioState
    data class Error(val message: String) : BluetoothAudioState
}

@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var bluetoothHeadset: BluetoothHeadset? = null

    private val _audioState = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    val audioState: StateFlow<BluetoothAudioState> = _audioState.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as? BluetoothHeadset
                val connectedDevices = bluetoothHeadset?.connectedDevices.orEmpty()
                if (connectedDevices.isNotEmpty()) {
                    val name = connectedDevices.first().name ?: "Bluetooth Гарнитура"
                    _audioState.value = BluetoothAudioState.Connected(name, isSingleEarbud = true)
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                _audioState.value = BluetoothAudioState.Disconnected
            }
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = device?.name ?: "Bluetooth Наушник"
                    _audioState.value = BluetoothAudioState.Connected(name, isSingleEarbud = true)
                    routeAudioToEarbud()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    _audioState.value = BluetoothAudioState.Disconnected
                    routeAudioToSpeaker()
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        _audioState.value = BluetoothAudioState.Connected("Моно-наушник (SCO)", true)
                    }
                }
            }
        }
    }

    init {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            context.registerReceiver(connectionReceiver, filter)
            routeAudioToEarbud()
        } catch (_: Exception) { }
    }

    fun routeAudioToEarbud() {
        try {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = am.availableCommunicationDevices
                    val headset = devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    if (headset != null) {
                        am.setCommunicationDevice(headset)
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        return
                    }
                }

                // Fallback for Android 10-11
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) { }
    }

    fun routeAudioToSpeaker() {
        try {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                }
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) { }
    }

    fun isHeadsetConnected(): Boolean {
        return _audioState.value is BluetoothAudioState.Connected
    }
}
