package com.jarvis.assistant.voice.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.jarvis.assistant.agent.automation.engine.PersonalAutomationEngine
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@AndroidEntryPoint
class SystemEventReceiver : BroadcastReceiver() {

    @Inject
    lateinit var automationEngine: PersonalAutomationEngine

    private val receiverScope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            // 🎧 1. Проводные наушники
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", 0)
                val trigger = if (state == 1) {
                    AutomationTriggerType.HEADPHONES_CONNECTED
                } else {
                    AutomationTriggerType.HEADPHONES_DISCONNECTED
                }
                receiverScope.launch {
                    automationEngine.onSystemEvent(trigger)
                }
            }

            // 🎧 2. Bluetooth наушники / TWS-гарнитура
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                receiverScope.launch {
                    automationEngine.onSystemEvent(AutomationTriggerType.HEADPHONES_CONNECTED)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                receiverScope.launch {
                    automationEngine.onSystemEvent(AutomationTriggerType.HEADPHONES_DISCONNECTED)
                }
            }

            // 🔋 3. Низкий заряд батареи (< 15-20%)
            Intent.ACTION_BATTERY_LOW -> {
                receiverScope.launch {
                    automationEngine.onSystemEvent(AutomationTriggerType.BATTERY_LOW)
                }
            }

            // 📶 4. Подключение к сети Wi-Fi
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    receiverScope.launch {
                        automationEngine.onSystemEvent(AutomationTriggerType.WIFI_CONNECTED)
                    }
                }
            }
        }
    }
}
