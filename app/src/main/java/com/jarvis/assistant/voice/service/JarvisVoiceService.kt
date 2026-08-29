package com.jarvis.assistant.voice.service

import com.jarvis.assistant.R
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.presentation.MainActivity
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode
import com.jarvis.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class JarvisVoiceService : Service() {

    @Inject
    lateinit var orchestrator: VoiceInteractionOrchestrator

    @Inject
    lateinit var workingMemory: WorkingMemory

    private val systemEventReceiver = SystemEventReceiver()
    private val receiverRegistered = AtomicBoolean(false)

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "uncaught exception in voice service scope", t)
    }
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        Dispatchers.Main + serviceJob + exceptionHandler
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyManager: TelephonyManager? = null

    // Modern callback (API 31+).
    private var telephonyCallback: TelephonyCallback? = null

    // CR-10: fallback listener для API 29–30. Держим сильную ссылку — без
    // неё TelephonyManager может собрать его GC (поведение до Android 12, где
    // listener хранился как WeakReference).
    private var legacyPhoneStateListener: PhoneStateListener? = null
    private val telephonyExecutor = Executors.newSingleThreadExecutor()
    private val started = AtomicBoolean(false)

    companion object {
        const val CHANNEL_ID = "jarvis_voice"
        const val CHANNEL_NAME = "JARVIS Voice Service"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "JarvisVoiceService"
        private const val WAKELOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L

        const val ACTION_START = "com.jarvis.action.START_SERVICE"
        const val ACTION_STOP = "com.jarvis.action.STOP_SERVICE"
        const val ACTION_PAUSE = "com.jarvis.action.PAUSE_SERVICE"
        const val ACTION_RESUME = "com.jarvis.action.RESUME_SERVICE"

        /**
         * CR-11: Единая точка входа на запуск foreground service с проверкой
         * разрешений и защитой от краша на платформенных ограничениях.
         *
         * Возвращает false если запуск невозможен (нет разрешения или
         * система отвергла startForegroundService — в этом случае вызывающий
         * должен уведомить пользователя, а не считать сервис поднявшимся).
         */
        fun start(context: Context): Boolean {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "microphone foreground service start denied: permission missing")
                return false
            }
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            return try {
                // startForegroundService доступен с API 26, minSdk = 29 —
                // проверка версии избыточна (lint: ObsoleteSdkInt).
                context.startForegroundService(intent)
                true
            } catch (failure: RuntimeException) {
                Log.e(
                    TAG,
                    "microphone foreground service start rejected | type=${failure.javaClass.simpleName}"
                )
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    // ----------------------------------------- Telephony listeners

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class JarvisTelephonyCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            dispatchCallState(state)
        }
    }

    /** CR-10: legacy PhoneStateListener для API 29-30. */
    @Suppress("DEPRECATION")
    private inner class LegacyPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            dispatchCallState(state)
        }
    }

    private fun dispatchCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> orchestrator.pauseForPhoneCall()
            TelephonyManager.CALL_STATE_IDLE -> orchestrator.resumeAfterPhoneCall()
        }
    }

    // ----------------------------------------- Service lifecycle

    override fun onCreate() {
        super.onCreate()
        // CR-11: единый safeStartForeground из onCreate с проверкой разрешения.
        if (!safeStartForeground(
                getString(R.string.jarvis_slushaet),
                "onCreate"
            )
        ) {
            // safeStartForeground сам вызывает stopSelf() при отказе; ранний возврат.
            return
        }
        acquireWakeLock()
        registerTelephonyListener()
        registerSystemReceivers()
        initWorkingMemoryDefaults()
        observeOrchestrator()
        started.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CR-11: ЛЮБАЯ ветка onStartCommand (включая STICKY restart с null intent)
        // обязана либо вызвать safeStartForeground(), либо остановить сервис.
        if (!safeStartForeground(
                getString(R.string.jarvis_slushaet),
                "onStartCommand(${intent?.action})"
            )
        ) {
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START, ACTION_RESUME, null -> {
                // null = STICKY restart системой — всегда безопасно перезапускаем
                // pipeline с нуля.
                orchestrator.startServicePipeline()
            }
            ACTION_PAUSE -> {
                orchestrator.stopServicePipeline()
                updateNotification(getString(R.string.jarvis_na_pauze))
            }
            ACTION_STOP -> {
                orchestrator.stopServicePipeline()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    /**
     * CR-11: Безопасный старт foreground service.
     *
     * Проверяет RECORD_AUDIO permission ДО вызова startForeground;
     * оборачивает вызов в try/catch от возможных RuntimeException
     * (BackgroundServiceStartNotAllowedException и т.п.); при ошибке
     * вызывает stopSelf(startId) и возвращает false — сервис не остаётся
     * в неопределённом «created но не foreground» состоянии.
     */
    private fun safeStartForeground(statusText: String, source: String): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "safeStartForeground denied: RECORD_AUDIO missing | source=$source")
            stopSelf()
            return false
        }
        return try {
            val notification = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (failure: RuntimeException) {
            Log.e(
                TAG,
                "safeStartForeground rejected | source=$source | type=${failure.javaClass.simpleName}"
            )
            stopSelf()
            false
        }
    }

    private fun initWorkingMemoryDefaults() {
        try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level > 0) {
                workingMemory.put("battery_percent", level)
            }
        } catch (e: Exception) {
            Log.e(TAG, "initWorkingMemoryDefaults: не удалось прочитать уровень батареи", e)
        }
    }

    private fun registerSystemReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            }
            registerReceiver(systemEventReceiver, filter)
            receiverRegistered.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "registerSystemReceivers: не удалось зарегистрировать ресиверы", e)
        }
    }

    /**
     * CR-10: регистрация слушателя звонков.
     *  - API 31+ (S): TelephonyCallback (современный API).
     *  - API 29–30: PhoneStateListener (устаревший, но единственный рабочий;
     *    хранится в сильном поле legacyPhoneStateListener, чтобы GC его не
     *    собрал — до Android 12 TelephonyManager держал listener по WeakReference).
     * Без READ_PHONE_STATE permission (или если нет TelephonyManager) —
     * пауза при звонке отключается (логируется), но сервис не падает.
     */
    @SuppressLint("MissingPermission")
    private fun registerTelephonyListener() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "call-state pause disabled: phone-state permission missing")
            return
        }
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager = tm ?: return

            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val cb = JarvisTelephonyCallback()
                    telephonyCallback = cb
                    tm.registerTelephonyCallback(telephonyExecutor, cb)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    // API 29-30: deprecated PhoneStateListener с сильной ссылкой.
                    val listener = LegacyPhoneStateListener()
                    legacyPhoneStateListener = listener
                    @Suppress("DEPRECATION")
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerTelephonyListener: не удалось зарегистрировать слушатель звонков", e)
        }
    }

    private fun unregisterTelephonyListener() {
        val tm = telephonyManager
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    telephonyCallback?.let { cb ->
                        runCatching { tm?.unregisterTelephonyCallback(cb) }
                            .onFailure { Log.w(TAG, "unregister TelephonyCallback failed", it) }
                    }
                    telephonyCallback = null
                }
                else -> {
                    legacyPhoneStateListener?.let { listener ->
                        @Suppress("DEPRECATION")
                        runCatching { tm?.listen(listener, PhoneStateListener.LISTEN_NONE) }
                            .onFailure { Log.w(TAG, "unregister PhoneStateListener failed", it) }
                    }
                    legacyPhoneStateListener = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "unregisterTelephonyListener: сбой отмены регистрации", e)
        }
        telephonyManager = null
    }

    private fun observeOrchestrator() {
        serviceScope.launch {
            orchestrator.currentMode.collectLatest { mode ->
                val statusText = when (mode) {
                    OrchestratorMode.STANDBY_WAKE_WORD -> getString(R.string.jarvis_slushaet_v_naushnike)
                    OrchestratorMode.VERIFYING_KEYWORD -> getString(R.string.analiz_golosa)
                    OrchestratorMode.LISTENING_USER_QUERY -> "Слушаю ваш запрос..."
                    OrchestratorMode.CONTINUOUS_CONVERSATION -> getString(R.string.slushayu_prodolzhenie_dialoga)
                    OrchestratorMode.AI_THINKING -> "Выполнение команды..."
                    OrchestratorMode.TTS_SPEAKING -> getString(R.string.ozvuchivanie_otveta)
                    OrchestratorMode.AWAITING_CONFIRMATION -> getString(R.string.ozhidanie_podtverzhdeniya)
                    // C-02: ждём голосовое/экранное «да» на cloud-consent.
                    OrchestratorMode.AWAITING_PRIVACY_CONSENT -> "Ожидаю подтверждения отправки в облако..."
                    OrchestratorMode.LIVE_EAR_INTERPRETER -> "🎧 Синхронный переводчик в ухе активен..."
                    OrchestratorMode.PAUSED_CALL_OR_SLEEP -> "Наушники отключены / Пауза"
                }
                if (started.get()) {
                    updateNotification(statusText)
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JARVIS:BackgroundVoiceWakeLock"
        )?.apply {
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLock: не удалось освободить wake lock", e)
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fonovoe_raspoznavanie_klyuchevogo_slova)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    // createNotificationChannel() вызывается из buildNotification (лениво) — это
    // безопасно делать после startForeground, но мы вызываем его один раз в
    // конструкторе нотификации:
    private fun buildNotification(statusText: String): Notification {
        createNotificationChannel()
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMain = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisVoiceService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Voice Service")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingMain)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        started.set(false)
        // Сначала останавливаем наблюдателей, потом orchestrator со всеми
        // своими подсистемами (TTS/STT/Wake/Bluetooth), затем освобождаем
        // system-level ресурсы сервиса.
        serviceScope.cancel()
        orchestrator.destroy()
        releaseWakeLock()

        if (receiverRegistered.compareAndSet(true, false)) {
            runCatching { unregisterReceiver(systemEventReceiver) }
                .onFailure { Log.w(TAG, "onDestroy: не удалось unregister receiver", it) }
        }

        unregisterTelephonyListener()
        telephonyExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
