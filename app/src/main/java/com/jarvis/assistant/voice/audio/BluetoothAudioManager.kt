package com.jarvis.assistant.voice.audio

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

@Singleton
class BluetoothAudioManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _isBluetoothHeadsetConnected = MutableStateFlow(false)
    val isBluetoothHeadsetConnected: StateFlow<Boolean> = _isBluetoothHeadsetConnected.asStateFlow()

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                _isBluetoothHeadsetConnected.value = true
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                _isBluetoothHeadsetConnected.value = false
            }
        }
    }

    init {
        checkConnectedDevices()
        try {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.registerReceiver(scoReceiver, filter)
        } catch (_: Exception) { }
    }

    private fun checkConnectedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
            val hasBt = devices.any { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET 
            }
            _isBluetoothHeadsetConnected.value = hasBt
        }
    }

    fun startBluetoothSco() {
        try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) { }
    }

    fun stopBluetoothSco() {
        try {
            audioManager?.let { am ->
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) { }
    }

    fun requestAudioFocus(): Boolean {
        // Simple and robust audio focus request for voice interactions
        return audioManager?.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonAudioFocus() {
        try {
            audioManager?.abandonAudioFocus(null)
        } catch (_: Exception) { }
    }
}
