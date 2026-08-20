package com.jarvis.assistant.agent.memory

import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import com.jarvis.assistant.agent.memory.context.ReferenceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * Пункт аудита #10 (MEDIUM): contextStore не растёт бесконечно —
 * LRU-вытеснение по [WorkingMemory.MAX_CONTEXT_ENTRIES] и TTL-очистка.
 */
class WorkingMemoryTest {

    private lateinit var memory: WorkingMemory

    @Before
    fun setUp() {
        memory = WorkingMemory(AnaphoraContextEngine(), ReferenceResolver())
    }

    // ===========================================
    // LRU-вытеснение
    // ===========================================

    @Test
    fun `contextStore never exceeds max entries - oldest evicted`() {
        repeat(WorkingMemory.MAX_CONTEXT_ENTRIES + 20) { i ->
            memory.put("key_$i", i)
        }

        assertTrue(
            "Размер должен быть ограничен MAX_CONTEXT_ENTRIES",
            memory.contextStoreSize() <= WorkingMemory.MAX_CONTEXT_ENTRIES
        )
        // Самые старые ключи вытеснены.
        assertNull(memory.get("key_0"))
        assertNull(memory.get("key_5"))
        // Самые свежие на месте.
        assertEquals(WorkingMemory.MAX_CONTEXT_ENTRIES + 19, memory.get("key_${WorkingMemory.MAX_CONTEXT_ENTRIES + 19}"))
    }

    @Test
    fun `recently accessed entry survives eviction - LRU order`() {
        repeat(WorkingMemory.MAX_CONTEXT_ENTRIES) { i ->
            memory.put("key_$i", i)
        }
        // Обращаемся к первой записи — она становится самой свежей по LRU.
        assertEquals(0, memory.get("key_0"))

        // Добавляем ещё 5 — вытесняться должны НЕ key_0, а следующие за ней.
        repeat(5) { i ->
            memory.put("new_$i", i)
        }

        assertEquals("key_0 должен выжить (к нему обращались)", 0, memory.get("key_0"))
        // key_1..key_5 — не обращались дольше всех → вытеснены.
        assertNull(memory.get("key_1"))
    }

    // ===========================================
    // TTL-очистка
    // ===========================================

    @Test
    fun `expired entries are evicted by TTL`() {
        val now = System.currentTimeMillis()
        memory.put("fresh", 1)
        // evictExpired с now из будущего — симулируем прошедший TTL.
        memory.evictExpired(now + WorkingMemory.TTL_MS + 1000)

        assertNull("Запись старше TTL должна быть удалена", memory.get("fresh"))
        assertEquals(0, memory.contextStoreSize())
    }

    @Test
    fun `fresh entries survive eviction`() {
        val now = System.currentTimeMillis()
        memory.put("fresh", 1)

        memory.evictExpired(now + WorkingMemory.TTL_MS - 1000) // чуть раньше TTL

        assertEquals("Свежая запись должна выжить", 1, memory.get("fresh"))
    }

    @Test
    fun `stale entry is removed on get`() {
        memory.put("stale", 42)
        // Имитируем старение: evictExpired удалит, get вернёт null.
        memory.evictExpired(System.currentTimeMillis() + WorkingMemory.TTL_MS + 1000)
        assertNull(memory.get("stale"))
    }

    // ===========================================
    // Слоты и базовая функциональность
    // ===========================================

    @Test
    fun `slots work as before`() {
        memory.setLastApp("Telegram")
        memory.setLastContact("мама")
        memory.setLastAction("device.open_app")
        memory.setLastMessage("привет")
        memory.setLastConversation("Чат с Иваном")

        assertEquals("Telegram", memory.getLastApp())
        assertEquals("мама", memory.getLastContact())
        assertEquals("device.open_app", memory.getLastAction())
        assertEquals("привет", memory.getLastMessage())
        assertEquals("Чат с Иваном", memory.getLastConversation())
    }

    @Test
    fun `put get roundtrip and overwrite`() {
        memory.put("battery_percent", 80)
        assertEquals(80, memory.get("battery_percent"))

        memory.put("battery_percent", 60)
        assertEquals(60, memory.get("battery_percent"))
    }

    @Test
    fun `clearContext empties store and slots`() {
        memory.setLastApp("Telegram")
        memory.put("battery_percent", 80)

        memory.clearContext()

        assertEquals(0, memory.contextStoreSize())
        assertNull(memory.getLastApp())
        assertNull(memory.get("battery_percent"))
    }

    @Test
    fun `concurrent observation and dialog access remains bounded and exception free`() {
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val workers = List(12) { worker ->
            Thread {
                try {
                    start.await()
                    repeat(2_000) { i ->
                        val key = "worker_${worker}_$i"
                        memory.put(key, i)
                        memory.get(key)
                        memory.setLastApp("app_$worker")
                        if (i % 20 == 0) memory.evictExpired()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        workers.forEach(Thread::start)
        start.countDown()
        workers.forEach(Thread::join)

        assertTrue("Concurrent access failed: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue(memory.contextStoreSize() <= WorkingMemory.MAX_CONTEXT_ENTRIES)
    }

    @Test
    fun `constants are sane`() {
        assertTrue(WorkingMemory.MAX_CONTEXT_ENTRIES > 0)
        assertTrue(WorkingMemory.TTL_MS > 0)
    }
}
