package com.jarvis.assistant.agent.localai.mediapipe

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.jarvis.assistant.agent.localai.LocalModelManager
import com.jarvis.assistant.agent.localai.LocalModelRuntime
import com.jarvis.assistant.agent.localai.LocalModelSpec
import com.jarvis.assistant.agent.localai.LocalModelState
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import javax.inject.Singleton

/**
 * Жизненный цикл локальной модели (пункты 6 и 17 ТЗ).
 *
 * ```
 * первый Local AI запрос → загрузка (~1-3 с) → модель в памяти
 *          последующие запросы → инференс без перезагрузки
 *          idle > modelIdleUnloadMs → unload() (return idle, Battery)
 *          memory pressure     → unload()
 * ```
 *
 * Модель НЕ грузится при старте приложения: это добавило бы секунды к startup
 * и ~1 ГБ RSS пользователям, которые локальной моделью не пользуются.
 *
 * Battery: модель НЕ держится активной постоянно. После последнего
 * использования таймер ([IdleUnloadScheduler]) выгружает тяжёлую модель, и
 * система возвращается в idle; следующий запрос лениво грузит её заново.
 * Окно (5 минут) больше худшего инференса (инструментальные таймауты ≤ 4 с),
 * поэтому выгрузка не может закрыть нативный движок посреди генерации.
 *
 * Файл модели (~529 МБ) НЕ входит в APK — он ожидается во внутреннем хранилище
 * приложения. Отсутствие файла — штатное состояние [LocalModelState.NotInstalled],
 * а не ошибка. Подробности и команда установки — docs/LOCAL_AI.md.
 */
@Singleton
class MediaPipeModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
    private val runtimeFactory: MediaPipeRuntimeFactory,
    private val spec: LocalModelSpec
) : LocalModelManager {

    private companion object {
        const val TAG = "LocalAI"

        /** Подкаталог во внутреннем хранилище: /data/data/<pkg>/files/llm/ */
        const val MODEL_DIR = "llm"
    }

    /** Защищает загрузку/выгрузку: параллельные запросы не грузят модель дважды. */
    private val lifecycleMutex = Mutex()
    private val lifecycleJob = SupervisorJob()
    private val lifecycleScope = CoroutineScope(lifecycleJob + dispatchers.default)

    @Volatile
    private var currentState: LocalModelState = LocalModelState.NotInitialized

    @Volatile
    private var runtime: LocalModelRuntime? = null

    /**
     * CR-24: держим сильную ссылку на зарегистрированный ComponentCallbacks2,
     * чтобы GC не собрал его (аналогично PhoneStateListener до Android 12) и
     * чтобы мы могли симметрично unregister его в [close].
     */
    @Volatile
    private var trimMemoryCallback: ComponentCallbacks2? = null

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Battery: окно неактивности до выгрузки тяжёлой модели. 5 минут —
     * компромисс: диалоговый сценарий (несколько запросов подряд) не платит
     * перезагрузкой, а после паузы модель освобождает память и связанные
     * ресурсы (см. docs/BATTERY.md).
     */
    val modelIdleUnloadMs: Long = 5 * 60_000L

    /**
     * Idle-планировщик выгрузки (часы monotonic). Объявлен ПОСЛЕ lifecycleScope
     * и runtime — инициализаторы свойств исполняются по порядку объявления.
     */
    private val idleUnloadScheduler = IdleUnloadScheduler(
        clock = { android.os.SystemClock.elapsedRealtime() },
        idleMs = modelIdleUnloadMs,
        scope = lifecycleScope,
        onIdle = {
            if (runtime != null) {
                Log.i(TAG, "model idle > ${modelIdleUnloadMs}ms — выгружаю тяжёлую модель (return idle)")
                unload()
            }
        }
    )

    override val state: LocalModelState get() = currentState

    /** Путь, где ожидается файл модели. */
    val modelFile: File get() = File(File(context.filesDir, MODEL_DIR), spec.fileName)

    init {
        registerTrimMemoryCallback()
    }

    /**
     * CR-24: регистрация ComponentCallbacks2 с сильной ссылкой на callback.
     * Идемпотентна — повторный вызов не регистрирует второй callback.
     */
    private fun registerTrimMemoryCallback() {
        if (trimMemoryCallback != null) return
        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (closed.get()) return
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    Log.i(TAG, "onTrimMemory(level=$level) — выгружаю локальную модель")
                    lifecycleScope.launch { runCatching { unload() } }
                }
            }

            override fun onLowMemory() {
                if (closed.get()) return
                Log.i(TAG, "onLowMemory — выгружаю локальную модель")
                lifecycleScope.launch { runCatching { unload() } }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        try {
            context.registerComponentCallbacks(cb)
            trimMemoryCallback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerComponentCallbacks failed", t)
        }
    }

    /**
     * Симметричная unregister-очистка. Идемпотентна. Может вызываться
     * при необходимости освободить все ресурсы (инструментарий/тесты/
     * полное выключение локального AI). Как @Singleton в обычном lifecycle
     * процесса не вызывается — процесс уходит целиком.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.d(TAG, "close: releasing MediaPipe resources")
        idleUnloadScheduler.cancel()
        lifecycleJob.cancel()
        trimMemoryCallback?.let { cb ->
            runCatching { context.unregisterComponentCallbacks(cb) }
                .onFailure { Log.w(TAG, "unregisterComponentCallbacks failed", it) }
        }
        trimMemoryCallback = null
        closeRuntime()
    }

    override fun isReady(): Boolean = currentState is LocalModelState.Ready && runtime != null

    override suspend fun runtimeOrNull(): LocalModelRuntime? {
        runtime?.let {
            idleUnloadScheduler.noteUsed()
            return it
        }
        initialize()
        if (runtime != null) idleUnloadScheduler.noteUsed()
        return runtime
    }

    override suspend fun initialize(): LocalModelState = lifecycleMutex.withLock {
        // Уже готово — повторная загрузка не нужна (идемпотентность).
        runtime?.let { return@withLock currentState }

        val file = modelFile
        if (!file.exists() || file.length() == 0L) {
            currentState = LocalModelState.NotInstalled(file.absolutePath)
            Log.i(TAG, "model not installed | expected=${file.absolutePath}")
            return@withLock currentState
        }

        if (!hasEnoughMemory()) {
            currentState = LocalModelState.Failed("Недостаточно свободной RAM для локальной модели")
            Log.w(TAG, "model load skipped: недостаточно памяти (нужно ~${spec.minRuntimeMemoryMb} МБ)")
            return@withLock currentState
        }

        currentState = LocalModelState.Loading
        Log.i(TAG, "model loading | id=${spec.modelId} | sizeMb=${spec.approxSizeMb}")

        var createdDuringAttempt: LocalModelRuntime? = null
        return@withLock try {
            val startedAt = System.currentTimeMillis()
            val created = withContext(dispatchers.default) {
                val candidate = runtimeFactory.create(modelPath = file.absolutePath, spec = spec)
                createdDuringAttempt = candidate
                try {
                    // Native creation itself is blocking, but cancellation while
                    // it runs must close the freshly created engine immediately
                    // rather than publishing/leaking it after the caller left.
                    coroutineContext.ensureActive()
                    candidate
                } catch (cancelled: CancellationException) {
                    (candidate as? AutoCloseable)?.close()
                    createdDuringAttempt = null
                    throw cancelled
                }
            }
            // Also cover cancellation after the worker block completes but before
            // withContext dispatches its value back to this coroutine.
            coroutineContext.ensureActive()
            val loadTimeMs = System.currentTimeMillis() - startedAt

            runtime = created
            createdDuringAttempt = null // ownership transferred to the manager
            currentState = LocalModelState.Ready(modelId = spec.modelId, loadTimeMs = loadTimeMs)
            idleUnloadScheduler.noteUsed()
            Log.i(
                TAG,
                "model = ${spec.modelId} | runtime = ${created.runtimeId} | loaded = true | " +
                    "loadTimeMs = $loadTimeMs"
            )
            currentState
        } catch (e: CancellationException) {
            (createdDuringAttempt as? AutoCloseable)?.close()
            createdDuringAttempt = null
            runtime = null
            currentState = LocalModelState.NotInitialized
            throw e
        } catch (e: Throwable) {
            // Ловим Throwable: нативная библиотека может кинуть UnsatisfiedLinkError
            // или OutOfMemoryError, и это не должно ронять приложение.
            runtime = null
            val reason = e.javaClass.simpleName
            currentState = LocalModelState.Failed(reason)
            Log.e(TAG, "model load failed | id=${spec.modelId}", e)
            currentState
        }
    }

    override suspend fun unload() = lifecycleMutex.withLock {
        closeRuntime()
    }

    private fun closeRuntime() {
        val current = runtime ?: return
        runtime = null
        currentState = LocalModelState.NotInitialized
        try {
            (current as? AutoCloseable)?.close()
            Log.i(TAG, "model unloaded")
        } catch (e: Exception) {
            Log.w(TAG, "model unload: сбой освобождения нативных ресурсов", e)
        }
    }

    /**
     * Грубая проверка доступной памяти. Цель — не «точно предсказать», а не
     * пытаться грузить ~1 ГБ на устройстве, которое уже в lowMemory.
     */
    private fun hasEnoughMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true // не смогли проверить — не блокируем

        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        if (info.lowMemory) return false

        val availableMb = info.availMem / (1024 * 1024)
        val enough = availableMb >= spec.minRuntimeMemoryMb
        if (!enough) {
            Log.w(TAG, "available RAM = ${availableMb}MB < required ${spec.minRuntimeMemoryMb}MB")
        }
        return enough
    }
}
