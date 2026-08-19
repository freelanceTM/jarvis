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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Жизненный цикл локальной модели (пункты 6 и 17 ТЗ).
 *
 * ```
 * первый Local AI запрос → загрузка (~1-3 с) → модель в памяти
 *          последующие запросы → инференс без перезагрузки
 *          memory pressure     → unload()
 * ```
 *
 * Модель НЕ грузится при старте приложения: это добавило бы секунды к startup
 * и ~1 ГБ RSS пользователям, которые локальной моделью не пользуются.
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

    @Volatile
    private var currentState: LocalModelState = LocalModelState.NotInitialized

    @Volatile
    private var runtime: LocalModelRuntime? = null

    override val state: LocalModelState get() = currentState

    /** Путь, где ожидается файл модели. */
    val modelFile: File get() = File(File(context.filesDir, MODEL_DIR), spec.fileName)

    init {
        // Реакция на memory pressure (пункт 17 ТЗ): при нехватке памяти система
        // просит освободить ресурсы — выгружаем модель вместо того, чтобы
        // ждать, пока нас убьёт LMK.
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    Log.i(TAG, "onTrimMemory(level=$level) — выгружаю локальную модель")
                    unloadBlocking()
                }
            }

            override fun onLowMemory() {
                Log.i(TAG, "onLowMemory — выгружаю локальную модель")
                unloadBlocking()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
    }

    override fun isReady(): Boolean = currentState is LocalModelState.Ready && runtime != null

    override suspend fun runtimeOrNull(): LocalModelRuntime? {
        runtime?.let { return it }
        initialize()
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

        return@withLock try {
            val startedAt = System.currentTimeMillis()
            val created = withContext(dispatchers.default) {
                runtimeFactory.create(modelPath = file.absolutePath, spec = spec)
            }
            val loadTimeMs = System.currentTimeMillis() - startedAt

            runtime = created
            currentState = LocalModelState.Ready(modelId = spec.modelId, loadTimeMs = loadTimeMs)
            Log.i(
                TAG,
                "model = ${spec.modelId} | runtime = ${created.runtimeId} | loaded = true | " +
                    "loadTimeMs = $loadTimeMs"
            )
            currentState
        } catch (e: CancellationException) {
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

    /** Синхронная выгрузка для колбэков системы (они не suspend). */
    private fun unloadBlocking() {
        if (runtime == null) return
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
