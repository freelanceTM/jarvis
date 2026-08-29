package com.jarvis.assistant.voice.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.automation.engine.PersonalAutomationEngine
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BluetoothAudioState {
    data object Disconnected : BluetoothAudioState
    data object Connecting : BluetoothAudioState
    data class Connected(val deviceName: String, val isSingleEarbud: Boolean = true) : BluetoothAudioState
}

/**
 * CR-12 / CR-13: Маршрутизатор аудио для Bluetooth-гарнитуры.
 *
 * Владеет: BroadcastReceiver, BluetoothHeadset proxy-прокси, собственной
 * корутинной областью и флагом audio mode (MODE_IN_COMMUNICATION / MODE_NORMAL).
 * ВСЕ эти ресурсы должны быть симметрично освобождены в [dispose].
 */
@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automationEngine: PersonalAutomationEngine
) {
    companion object {
        private const val TAG = "BluetoothAudioRouter"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val disposed = AtomicBoolean(false)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null

    // CR-13: Structured concurrency — SupervisorJob + CEH. Исключения в launch
    // логируются и не убивают scope; CancellationException не маскируется.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) throw throwable
        Log.e(TAG, "uncaught exception in bluetooth router scope", throwable)
    }
    // SupervisorJob — это фабричная функция (возвращает CompletableJob),
    // поэтому тип поля — CompletableJob.
    private var routerJob: CompletableJob? = SupervisorJob()
    private var scope: CoroutineScope? = CoroutineScope(
        requireNotNull(routerJob) + Dispatchers.IO + exceptionHandler
    )

    private val _audioState = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    val audioState: StateFlow<BluetoothAudioState> = _audioState.asStateFlow()

    private val _isHeadsetPlugged = MutableStateFlow(false)
    val isHeadsetPlugged: StateFlow<Boolean> = _isHeadsetPlugged.asStateFlow()

    // N-05: явная проверка BLUETOOTH_CONNECT (API 31+) вместо @SuppressLint.
    // До API 31 разрешение BLUETOOTH_CONNECT не существует — все BT-вызовы
    // легитимны по BLUETOOTH / BLUETOOTH_ADMIN (normal/permissions до 30).
    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (disposed.get()) {
                // CR-12: если connect пришёл после dispose — сразу закрываем,
                // не оставляем висячий proxy.
                (proxy as? BluetoothHeadset)?.let { safeCloseProxy(it) }
                return
            }
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as? BluetoothHeadset
                // N-05: connectedDevices / .name требуют BLUETOOTH_CONNECT на API 31+.
                // SecurityException обрабатываем явно (TOCTOU: разрешение могли
                // отозвать между проверкой и вызовом) — тот же паттерн, что и для
                // .name ниже.
                val connectedDevices = try {
                    if (hasBluetoothConnectPermission()) {
                        bluetoothHeadset?.connectedDevices.orEmpty()
                    } else {
                        emptyList()
                    }
                } catch (_: SecurityException) {
                    emptyList()
                }
                if (connectedDevices.isNotEmpty()) {
                    val name = try {
                        if (hasBluetoothConnectPermission()) connectedDevices.first().name else null
                    } catch (_: SecurityException) { null }
                        ?: context.getString(R.string.bluetooth_garnitura)
                    _audioState.value = BluetoothAudioState.Connected(name, isSingleEarbud = true)
                    _isHeadsetPlugged.value = true
                    routeAudioToEarbud()
                    triggerHeadphoneAutomation()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (disposed.get()) return
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                _audioState.value = BluetoothAudioState.Disconnected
                routeAudioToSpeaker()
                checkHeadsetConnection()
            }
        }
    }

    private var connectionReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (disposed.get()) return
            // CR-13: любая ошибка в onReceive не должна убивать процесс / ресивер.
            runCatching {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        // N-05: device?.name требует BLUETOOTH_CONNECT на API 31+.
                        val name = try {
                            if (hasBluetoothConnectPermission()) device?.name else null
                        } catch (_: SecurityException) { null }
                            ?: this@BluetoothAudioRouter.context.getString(R.string.bluetooth_naushnik)
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
                            _audioState.value = BluetoothAudioState.Connected(
                                this@BluetoothAudioRouter.context.getString(
                                    R.string.provodnye_naushniki
                                )
                            )
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
                // when используется как statement: без этого он становился бы
                // последним выражением лямбды runCatching и требовал
                // исчерпывающих ветвей (else) и if-цепочек с else.
                Unit
            }.onFailure {
                Log.e(TAG, "connectionReceiver: ошибка обработки broadcast", it)
            }
        }
    }

    init {
        registerSafely()
    }

    // N-05: getProfileProxy / registerReceiver не требуют BLUETOOTH_CONNECT — это
    // системные вызовы регистрации; вызовы к самому proxy/Device обёрнуты в
    // hasBluetoothConnectPermission() выше. @SuppressLint снят.
    private fun registerSafely() {
        if (disposed.get()) return
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)

            val receiver = connectionReceiver ?: return
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            context.registerReceiver(receiver, filter)
            checkHeadsetConnection()
        } catch (e: Exception) {
            Log.e(TAG, "registerConnectionReceiver: не удалось зарегистрировать ресивер", e)
        }
    }

    private fun triggerHeadphoneAutomation() {
        val currentScope = scope ?: return
        currentScope.launch {
            runCatching {
                automationEngine.onSystemEvent(AutomationTriggerType.HEADPHONES_CONNECTED)
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "headphone automation failed", it)
            }
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
        if (disposed.get()) return
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

    fun routeAudioToSpeaker() {
        if (disposed.get()) return
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

    /**
     * CR-12: Идемпотентное освобождение всех ресурсов роутера.
     *
     * Отменяет корутины, возвращает audio mode в MODE_NORMAL, снимает
     * регистрацию BroadcastReceiver, закрывает BluetoothHeadset proxy.
     * Повторный вызов безопасен.
     */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        Log.d(TAG, "dispose: releasing bluetooth audio resources")

        // Сначала отменяем корутины (прерываем in-flight automation),
        // потом возвращаем аудио в норму, потом снимаем receiver/proxy.
        routerJob?.cancel()
        routerJob = null
        scope = null

        runCatching { routeAudioToSpeaker() }

        connectionReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "dispose: не удалось unregister receiver", it) }
        }
        connectionReceiver = null

        bluetoothHeadset?.let { proxy ->
            safeCloseProxy(proxy)
            bluetoothHeadset = null
        }
        bluetoothAdapter = null

        _audioState.value = BluetoothAudioState.Disconnected
        _isHeadsetPlugged.value = false
    }

    /**
     * CR-12: proxy освобождается единственным официальным API —
     * [BluetoothAdapter.closeProfileProxy]. BluetoothHeadset не имеет
     * собственного close(), а stopVoiceRecognition() требует конкретное
     * устройство и нигде в приложении не парен с startVoiceRecognition.
     */
    private fun safeCloseProxy(proxy: BluetoothHeadset) {
        runCatching {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
        }.onFailure { Log.w(TAG, "safeCloseProxy: не удалось закрыть proxy", it) }
    }
}
