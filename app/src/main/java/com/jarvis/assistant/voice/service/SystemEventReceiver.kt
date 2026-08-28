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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemEventReceiverEntryPoint {
    fun automationEngine(): PersonalAutomationEngine
}

class SystemEventReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemEventReceiver"

        /**
         * S-03: строгий upper-bound на всю асинхронную работу receiver'а.
         *
         * goAsync() позволяет BroadcastReceiver-у продолжить работу после
         * возврата из onReceive, но не даёт ему права висеть бесконечно:
         * система убьёт приложение ANR-ом через ~10с. Мы ограничиваем
         * полезную работу 8 секундами, оставляя запас на finish().
         */
        private const val DISPATCH_TIMEOUT_MS = 8_000L

        // S-03: один общий supervisor scope на все dispatch'и receiver'а,
        // с CEH — исключение в одном automation event не гасит остальные.
        // Receiver живёт как одноразовый объект (система может создать
        // несколько), поэтому scope является его собственным и
        // закрывается на последнем finish().
        private val receiverJob = SupervisorJob()
        private val receiverExceptionHandler = CoroutineExceptionHandler { _, t ->
            if (t is CancellationException) throw t
            Log.e(TAG, "uncaught exception in system event receiver", t)
        }
        private val dispatchScope = CoroutineScope(
            Dispatchers.IO + receiverJob + receiverExceptionHandler
        )
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
     * S-03: goAsync + withTimeout(8000) + гарантированный ровно-один finish().
     *
     * - pendingResult удерживается до конца работы (успех / exception / timeout);
     * - AtomicBoolean guards от двойного finish() при гонке timeout vs normal;
     * - CancellationException не маскируется и не логируется как ошибка;
     * - при timeout in-flight coroutine отменяется structured-concurrency'ом;
     * - scope привязан к Companion object и не утекает (один на процесс).
     */
    private fun dispatch(
        automationEngine: PersonalAutomationEngine,
        trigger: AutomationTriggerType
    ) {
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        dispatchScope.launch {
            try {
                withTimeout(DISPATCH_TIMEOUT_MS) {
                    automationEngine.onSystemEvent(trigger)
                }
            } catch (ce: CancellationException) {
                // Timeout / scope shutdown — это ожидаемое завершение.
                Log.w(TAG, "system event dispatch cancelled: $trigger")
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Automation failed for $trigger", t)
            } finally {
                if (finished.compareAndSet(false, true)) {
                    pendingResult.finish()
                }
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
