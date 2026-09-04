package com.jarvis.assistant.core.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * OBSERVABILITY: формат и свойства единого request id (`omx_01J…`).
 * id протаскивается Voice → Router → Tool → AI → Server → Provider;
 * сервер принимает клиентский id только длиной ≤ 64 (JarvisApiHandler).
 */
class RequestIdsTest {

    @Test
    fun `id has omx prefix and ulid shape`() {
        val id = RequestIds.newId()
        assertTrue("prefix", id.startsWith("omx_"))
        // omx_ + 10 time + 16 random = 30 символов (лимит сервера 64).
        assertEquals(RequestIds.LENGTH, id.length)
        assertEquals(30, id.length)
        assertTrue("looks like omnix id", RequestIds.looksLikeOmnixId(id))
    }

    @Test
    fun `alphabet is crockford base32 - no ambiguous chars`() {
        val id = RequestIds.newId()
        val body = id.removePrefix("omx_")
        // I/L/O/U отсутствуют в алфавите — не путаются при чтении с экрана.
        assertTrue(body.none { it in "ILOU" })
        assertTrue(body.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun `ids are unique`() {
        val ids = (1..1000).map { RequestIds.newId(nowMs = 1_700_000_000_000L) }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test
    fun `lexicographic order equals chronological order`() {
        val t0 = 1_700_000_000_000L
        val earlier = RequestIds.newId(nowMs = t0, random = Random(1))
        val later = RequestIds.newId(nowMs = t0 + 1, random = Random(1))
        val latest = RequestIds.newId(nowMs = t0 + 123_456, random = Random(1))
        assertTrue(earlier < later)
        assertTrue(later < latest)
    }

    @Test
    fun `deterministic for injected clock and random`() {
        val a = RequestIds.newId(nowMs = 42L, random = Random(7))
        val b = RequestIds.newId(nowMs = 42L, random = Random(7))
        assertEquals(a, b)
    }
}
