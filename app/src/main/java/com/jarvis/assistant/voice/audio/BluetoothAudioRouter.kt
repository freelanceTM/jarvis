package com.jarvis.assistant.voice.audio

import com.jarvis.assistant.R
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
import android.util.Log
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
    companion object {
        private const val TAG = "BluetoothAudioRouter"
    }

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
                    val name = connectedDevices.first().name ?: context.getString(R.string.bluetooth_garnitura)
                    _audioState.value = BluetoothAudioState.Connected(name, isSingleEarbud = true)
                    _isHeadsetPlugged.value = true
                    routeAudioToEarbud()
                    triggerHeadphoneAutomation()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                _audioState.value = BluetoothAudioState.Disconnected
                routeAudioToSpeaker()
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
                    val name = device?.name ?: this@BluetoothAudioRouter.context.getString(R.string.bluetooth_naushnik)
                    _audioState.value = BluetoothAudioState.Connected(name, isSingleEarbud = true)
                    _isHeadsetPlugged.value = true
                    routeAudioToEarbud()
                    triggerHeadphoneAutomation()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    routeAudioToSpeaker()
                    checkHeadsetConnection()
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    val isPlugged = (state == 1) || isBluetoothConnected()
                    _isHeadsetPlugged.value = isPlugged
                    if (state == 1) {
                        _audioState.value = BluetoothAudioState.Connected(this@BluetoothAudioRouter.context.getString(R.string.provodnye_naushniki))
                        routeAudioToEarbud()
                        triggerHeadphoneAutomation()
                    } else if (!isBluetoothConnected()) {
                        _audioState.value = BluetoothAudioState.Disconnected
                        routeAudioToSpeaker()
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
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bluetoothManager?.adapter
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
        } catch (e: Exception) {
            Log.e(TAG, "registerConnectionReceiver: не удалось зарегистрировать ресивер", e)
        }
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

    /**
     * Маршрутизация звука и микрофона на гарнитуру / наушник (Bluetooth SCO 16kHz)
     */
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
                        Log.d(TAG, "Communication device routed to headset: ${headset.productName}")
                        return
                    }
                }

                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                Log.d(TAG, "Bluetooth SCO started")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to route to earbud: ${e.localizedMessage}")
        }
    }

    /**
     * Возврат звука и микрофона на стандартный динамик смартфона
     */
    fun routeAudioToSpeaker() {
        try {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                }
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
                am.mode = AudioManager.MODE_NORMAL
                am.isSpeakerphoneOn = false
                Log.d(TAG, "Audio routed back to normal speaker/mic")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to route to speaker: ${e.localizedMessage}")
        }
    }
}
