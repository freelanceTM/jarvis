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
import com.jarvis.assistant.agent.automation.engine.PersonalAutomationEngine
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BluetoothAudioState {
    data object Disconnected : BluetoothAudioState
    data object Connecting : BluetoothAudioState
    data class Connected(val deviceName: String, val isSingleEarbud: Boolean = true) : BluetoothAudioState
}

@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automationEngine: PersonalAutomationEngine
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var bluetoothHeadset: BluetoothHeadset? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _audioState = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    val audioState: StateFlow<BluetoothAudioState> = _audioState.asStateFlow()

    private val _isHeadsetPlugged = MutableStateFlow(false)
    val isHeadsetPlugged: StateFlow<Boolean> = _isHeadsetPlugged.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as? BluetoothHeadset
                val connectedDevices = bluetoothHeadset?.connectedDevices.orEmpty()
                if (connectedDevices.isNotEmpty()) {
                    val name = connectedDevices.first().name ?: "Bluetooth Гарнитура"
                    _audioState.value = BluetoothAudioState.Connected(name, isSingleEarbud = true)
                    _isHeadsetPlugged.value = true
                    triggerHeadphoneAutomation()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                _audioState.value = BluetoothAudioState.Disconnected
                checkHeadsetConnection()
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
                    _isHeadsetPlugged.value = true
                    routeAudioToEarbud()
                    triggerHeadphoneAutomation()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    checkHeadsetConnection()
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    _isHeadsetPlugged.value = (state == 1) || isBluetoothConnected()
                    if (state == 1) {
                        _audioState.value = BluetoothAudioState.Connected("Проводные наушники")
                        triggerHeadphoneAutomation()
                    } else if (!isBluetoothConnected()) {
                        _audioState.value = BluetoothAudioState.Disconnected
                    }
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        _isHeadsetPlugged.value = true
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
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            context.registerReceiver(connectionReceiver, filter)
            checkHeadsetConnection()
        } catch (_: Exception) { }
    }

    private fun triggerHeadphoneAutomation() {
        scope.launch {
            automationEngine.onSystemEvent(AutomationTriggerType.HEADPHONES_CONNECTED)
        }
    }

    fun checkHeadsetConnection(): Boolean {
        var connected = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
            connected = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
        _isHeadsetPlugged.value = connected
        if (!connected) {
            _audioState.value = BluetoothAudioState.Disconnected
        }
        return connected
    }

    fun isHeadsetConnected(): Boolean = _isHeadsetPlugged.value

    private fun isBluetoothConnected(): Boolean = _audioState.value is BluetoothAudioState.Connected

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

                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) { }
    }
}
