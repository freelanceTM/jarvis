package com.jarvis.assistant.voice.service

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.agent.automation.engine.PersonalAutomationEngine
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemEventReceiverEntryPoint {
    fun automationEngine(): PersonalAutomationEngine
}

class SystemEventReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemEventReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action: $action")

        val automationEngine = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SystemEventReceiverEntryPoint::class.java
            ).automationEngine()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot obtain automation engine", e)
            return
        }

        when (action) {
            Intent.ACTION_HEADSET_PLUG -> {
                when (intent.getIntExtra("state", -1)) {
                    1 -> dispatch(automationEngine, AutomationTriggerType.HEADPHONES_CONNECTED)
                    0 -> dispatch(automationEngine, AutomationTriggerType.HEADPHONES_DISCONNECTED)
                    else -> Log.w(TAG, "HEADSET_PLUG without a valid state")
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (isAudioBluetoothDevice(intent)) {
                    dispatch(automationEngine, AutomationTriggerType.HEADPHONES_CONNECTED)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (isAudioBluetoothDevice(intent)) {
                    dispatch(automationEngine, AutomationTriggerType.HEADPHONES_DISCONNECTED)
                }
            }

            Intent.ACTION_BATTERY_LOW ->
                dispatch(automationEngine, AutomationTriggerType.BATTERY_LOW)

            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                // WIFI_STATE_CHANGED означает лишь включение адаптера. Событие
                // WIFI_CONNECTED отправляем только при реальном active Wi-Fi.
                if (isWifiConnected(context)) {
                    dispatch(automationEngine, AutomationTriggerType.WIFI_CONNECTED)
                }
            }
        }
    }

    /**
     * BroadcastReceiver может быть уничтожен сразу после onReceive. goAsync()
     * удерживает pending result до завершения Room/tool workflow.
     */
    private fun dispatch(
        automationEngine: PersonalAutomationEngine,
        trigger: AutomationTriggerType
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                automationEngine.onSystemEvent(trigger)
            } catch (e: Exception) {
                Log.e(TAG, "Automation failed for $trigger", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isAudioBluetoothDevice(intent: Intent): Boolean {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return false

        return try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (_: SecurityException) {
            // Без BLUETOOTH_CONNECT безопаснее не запускать headphone automation,
            // чем принять клавиатуру/автомобильный датчик за наушники.
            false
        }
    }
}
