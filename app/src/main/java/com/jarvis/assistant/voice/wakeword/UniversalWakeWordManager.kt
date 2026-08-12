package com.jarvis.assistant.voice.wakeword

import android.util.Log
import com.jarvis.assistant.data.preferences.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UniversalWakeWordManager
 * 
 * Центральный диспетчер Wake Word в JARVIS, поддерживающий 3 независимых движка:
 * 1. Hybrid Acoustic VAD & Formant Tracker (AlisaStyleWakeWordEngine) - 0 МБ
 * 2. Picovoice Porcupine Neural KWS (PorcupineWakeWordEngine) - <50 мс задержка
 * 3. Vosk Small Offline Kaldi KWS (VoskWakeWordEngine) - 100% Offline
 */
@Singleton
class UniversalWakeWordManager @Inject constructor(
    private val hybridEngine: AlisaStyleWakeWordEngine,
    private val porcupineEngine: PorcupineWakeWordEngine,
    private val voskEngine: VoskWakeWordEngine,
    private val settingsDataStore: SettingsDataStore
) : WakeWordDetector {

    companion object {
        private const val TAG = "UniversalWakeWord"
    }

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeEngine: WakeWordDetector = hybridEngine
    private var activeEngineType: WakeWordEngineType = WakeWordEngineType.HYBRID_ACOUSTIC_VAD

    init {
        observeEngineChanges()
        relayEvents(hybridEngine)
        relayEvents(porcupineEngine)
        relayEvents(voskEngine)
    }

    private fun observeEngineChanges() {
        scope.launch {
            settingsDataStore.selectedWakeWordEngineFlow.collectLatest { engineId ->
                val newType = WakeWordEngineType.fromId(engineId)
                if (newType != activeEngineType) {
                    val wasRunning = activeEngine.isRunning()
                    if (wasRunning) {
                        activeEngine.stopListening()
                    }

                    activeEngineType = newType
                    activeEngine = when (newType) {
                        WakeWordEngineType.HYBRID_ACOUSTIC_VAD -> hybridEngine
                        WakeWordEngineType.PICOVOICE_PORCUPINE -> porcupineEngine
                        WakeWordEngineType.VOSK_OFFLINE_KWS -> voskEngine
                    }
                    Log.d(TAG, "Switched active Wake Word engine to: ${newType.displayName}")

                    if (wasRunning) {
                        activeEngine.startListening()
                    }
                }
            }
        }

        scope.launch {
            settingsDataStore.porcupineAccessKeyFlow.collectLatest { key ->
                porcupineEngine.updateAccessKey(key)
            }
        }

        scope.launch {
            settingsDataStore.wakeWordSensitivityFlow.collectLatest { sensitivity ->
                setSensitivity(sensitivity)
            }
        }
    }

    private fun relayEvents(engine: WakeWordDetector) {
        scope.launch {
            engine.events.collect { event ->
                if (engine == activeEngine || activeEngineType == WakeWordEngineType.HYBRID_ACOUSTIC_VAD) {
                    _events.emit(event)
                }
            }
        }
    }

    override fun startListening() {
        activeEngine.startListening()
    }

    override fun stopListening() {
        activeEngine.stopListening()
    }

    override fun isRunning(): Boolean = activeEngine.isRunning()

    override fun setSensitivity(sensitivity: Float) {
        hybridEngine.setSensitivity(sensitivity)
        porcupineEngine.setSensitivity(sensitivity)
        voskEngine.setSensitivity(sensitivity)
    }

    override fun destroy() {
        hybridEngine.destroy()
        porcupineEngine.destroy()
        voskEngine.destroy()
        scope.cancel()
    }
}
