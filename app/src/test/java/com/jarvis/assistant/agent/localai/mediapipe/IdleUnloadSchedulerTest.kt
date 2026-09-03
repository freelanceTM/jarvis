package com.jarvis.assistant.agent.localai.mediapipe

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Battery: idle-выгрузка тяжёлой модели («heavy processing → return idle»).
 *
 * Планировщик: использование сдвигает окно; окно без использования → выгрузка
 * ровно один раз; использование внутри окна отменяет исходный таймер;
 * cancel() снимает выгрузку.
 *
 * Часы инжектированы ([clock] = виртуальная переменная): ВАЖНО — виртуальные
 * часы обновляются ДО advanceTimeBy, потому что advanceTimeBy исполняет
 * задачи, попадающие на границу времени, и fire-условие
 * `clock() - lastUsedAtMs >= idleMs` должно видеть уже сдвинутое время.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleUnloadSchedulerTest {

    @Test
    fun `idle past the window triggers unload exactly once`() = runTest {
        var virtual = 0L
        val unloads = AtomicInteger()
        val scheduler = IdleUnloadScheduler(
            clock = { virtual },
            idleMs = 300_000,
            scope = backgroundScope,
            onIdle = { unloads.incrementAndGet() }
        )

        scheduler.noteUsed() // t=0
        virtual = 299_999
        advanceTimeBy(299_999)
        advanceUntilIdle()
        assertEquals(0, unloads.get()) // 299_999 < окна — ещё в окне

        virtual = 300_000
        advanceTimeBy(1) // delay(300_000) завершается; 300_000 - 0 >= окна
        advanceUntilIdle()
        assertEquals(1, unloads.get())

        // Повторного срабатывания нет (job завершён, перезапускает только noteUsed).
        virtual += 1_000_000
        advanceTimeBy(1_000_000)
        advanceUntilIdle()
        assertEquals(1, unloads.get())
    }

    @Test
    fun `usage inside the window cancels the original timer and rearms`() = runTest {
        var virtual = 0L
        val unloads = AtomicInteger()
        val scheduler = IdleUnloadScheduler(
            clock = { virtual },
            idleMs = 300_000,
            scope = backgroundScope,
            onIdle = { unloads.incrementAndGet() }
        )

        scheduler.noteUsed() // t=0
        virtual = 299_999
        advanceTimeBy(299_999)
        // Пользователь сказал ещё одну команду — окно сдвинулось, исходный
        // таймер (истекает на t=300_000) отменён и перепланирован.
        scheduler.noteUsed() // lastUsed = 299_999

        virtual = 300_000
        advanceTimeBy(1)
        advanceUntilIdle()
        // Исходное окно истекло по часам, но выгрузки НЕТ — использование
        // внутри окна перенесло её.
        assertEquals(0, unloads.get())

        // Перепланированный таймер истекает на 299_999 + 300_000 = 599_999.
        virtual = 599_999
        advanceTimeBy(299_999)
        advanceUntilIdle()
        assertEquals(1, unloads.get())
    }

    @Test
    fun `cancel prevents scheduled unload`() = runTest {
        var virtual = 0L
        val unloads = AtomicInteger()
        val scheduler = IdleUnloadScheduler(
            clock = { virtual },
            idleMs = 60_000,
            scope = backgroundScope,
            onIdle = { unloads.incrementAndGet() }
        )
        scheduler.noteUsed()
        scheduler.cancel()
        virtual = 120_000
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertEquals(0, unloads.get())
    }
}
