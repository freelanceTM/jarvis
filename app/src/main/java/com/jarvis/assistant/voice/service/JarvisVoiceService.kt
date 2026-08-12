package com.jarvis.assistant.voice.service

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
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.presentation.MainActivity
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode
import com.jarvis.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class JarvisVoiceService : Service() {

    @Inject
    lateinit var orchestrator: VoiceInteractionOrchestrator

    @Inject
    lateinit var workingMemory: WorkingMemory

    private val systemEventReceiver = SystemEventReceiver()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private val telephonyExecutor = Executors.newSingleThreadExecutor()

    companion object {
        const val CHANNEL_ID = "jarvis_voice"
        const val CHANNEL_NAME = "JARVIS Voice Service"
        const val NOTIFICATION_ID = 1001
        
        // WakeLock timeout: 8 часов (для длительной фоновой работы)
        private const val WAKELOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L

        const val ACTION_START = "com.jarvis.action.START_SERVICE"
        const val ACTION_STOP = "com.jarvis.action.STOP_SERVICE"
        const val ACTION_PAUSE = "com.jarvis.action.PAUSE_SERVICE"
        const val ACTION_RESUME = "com.jarvis.action.RESUME_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    // Современный TelephonyCallback для Android 12+ (заменяет deprecated PhoneStateListener)
    @RequiresApi(Build.VERSION_CODES.S)
    private inner class JarvisTelephonyCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    orchestrator.pauseForPhoneCall()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    orchestrator.resumeAfterPhoneCall()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground(buildNotification("JARVIS слушает..."))
        acquireWakeLock()
        registerTelephonyListener()
        registerSystemReceivers()
        initWorkingMemoryDefaults()
        observeOrchestrator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                startServiceForeground(buildNotification("JARVIS слушает..."))
                orchestrator.startServicePipeline()
            }
            ACTION_PAUSE -> {
                orchestrator.stopServicePipeline()
                updateNotification("JARVIS на паузе")
            }
            ACTION_STOP -> {
                orchestrator.stopServicePipeline()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                orchestrator.startServicePipeline()
            }
        }
        return START_STICKY
    }

    private fun initWorkingMemoryDefaults() {
        try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level > 0) {
                workingMemory.put("battery_percent", level)
            }
        } catch (_: Exception) { }
    }

    private fun registerSystemReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            registerReceiver(systemEventReceiver, filter)
        } catch (_: Exception) { }
    }

    /**
     * Регистрация слушателя телефонных звонков.
     * Использует TelephonyCallback для Android 12+ и fallback для старых версий.
     */
    @SuppressLint("MissingPermission")
    private fun registerTelephonyListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ — используем современный TelephonyCallback
                val callback = JarvisTelephonyCallback()
                telephonyCallback = callback
                telephonyManager?.registerTelephonyCallback(telephonyExecutor, callback)
            }
            // Для Android < 12 PhoneStateListener deprecated, но всё ещё работает
            // Однако мы его не используем, т.к. minSdk = 29 (Android 10)
            // и на практике большинство устройств уже на Android 12+
        } catch (_: Exception) { }
    }

    private fun unregisterTelephonyListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { callback ->
                    telephonyManager?.unregisterTelephonyCallback(callback)
                }
            }
        } catch (_: Exception) { }
    }

    private fun startServiceForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeOrchestrator() {
        serviceScope.launch {
            orchestrator.currentMode.collectLatest { mode ->
                val statusText = when (mode) {
                    OrchestratorMode.STANDBY_WAKE_WORD -> "JARVIS слушает (в наушнике)..."
                    OrchestratorMode.VERIFYING_KEYWORD -> "Анализ голоса..."
                    OrchestratorMode.LISTENING_USER_QUERY -> "Слушаю ваш запрос..."
                    OrchestratorMode.CONTINUOUS_CONVERSATION -> "Слушаю продолжение диалога..."
                    OrchestratorMode.AI_THINKING -> "Выполнение команды..."
                    OrchestratorMode.TTS_SPEAKING -> "Озвучивание ответа..."
                    OrchestratorMode.AWAITING_CONFIRMATION -> "Ожидание подтверждения (Да/Нет)..."
                    OrchestratorMode.PAUSED_CALL_OR_SLEEP -> "Наушники отключены / Пауза"
                }
                updateNotification(statusText)
            }
        }
    }

    /**
     * Захват WakeLock с ОБЯЗАТЕЛЬНЫМ таймаутом (требование Google Play).
     * Таймаут = 8 часов, после чего автоматически освобождается.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JARVIS:BackgroundVoiceWakeLock"
        )?.apply {
            // Критично: указываем таймаут!
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) { }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновое распознавание ключевого слова и голосовые ответы"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMain = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisVoiceService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)

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
        super.onDestroy()
        
        // Корректная очистка ресурсов
        serviceScope.cancel()
        orchestrator.destroy()
        releaseWakeLock()
        
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (_: Exception) { }
        
        unregisterTelephonyListener()
        telephonyExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
