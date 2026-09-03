package com.jarvis.assistant.agent.localai.mediapipe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Планировщик idle-выгрузки тяжёлой модели (Battery: «heavy processing →
 * return idle»).
 *
 * Раньше модель выгружалась ТОЛЬКО по memory pressure: после первого же
 * инференса ~529 МБ и нативные ресурсы оставались резидентными до давления
 * системы — тяжёлая модель была активна постоянно. Теперь каждое использование
 * перепланирует таймер: если в течение [idleMs] запросов не было, модель
 * выгружается, и система возвращается в idle-состояние (следующий запрос
 * лениво загрузит модель заново, ~1–3 с).
 *
 * Часы инжектируются ([clock]) — в тестах виртуальные; задержка реальная
 * (delay), поэтому тест использует runTest с виртуальным временем.
 *
 * Гонки: конкурентные [noteUsed] могут создать два таймера — оба безопасны
 * (onIdle идемпотентен, у менеджера lifecycleMutex + closeRuntime no-op).
 */
class IdleUnloadScheduler(
    /** Monotonic-часы (SystemClock.elapsedRealtime в проде). */
    private val clock: () -> Long,
    /** Окно неактивности, после которого срабатывает выгрузка. */
    val idleMs: Long,
    private val scope: CoroutineScope,
    private val onIdle: suspend () -> Unit
) {

    @Volatile
    private var lastUsedAtMs: Long = clock()

    @Volatile
    private var job: Job? = null

    /** Использование модели: сдвигает окно и перепланирует выгрузку. */
    fun noteUsed() {
        lastUsedAtMs = clock()
        job?.cancel()
        job = scope.launch {
            delay(idleMs)
            val idle = clock() - lastUsedAtMs
            if (idle >= idleMs) {
                onIdle()
            }
        }
    }

    /** Немедленная выгрузка без ожидания окна (memory pressure, close). */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
