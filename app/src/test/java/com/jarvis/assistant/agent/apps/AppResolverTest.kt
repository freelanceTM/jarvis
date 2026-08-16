package com.jarvis.assistant.agent.apps

import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты чистой логики AppResolver (нормализация и fuzzy-сходство).
 * Разрешение против PackageManager проверяется в instrumented-тестах.
 */
class AppResolverTest {

    @Test
    fun `normalize lowercases and strips punctuation`() {
        assertEquals("youtube", AppResolver.normalize("  YouTube!  "))
        assertEquals("телеграм", AppResolver.normalize("Телеграм,"))
    }

    @Test
    fun `normalize unifies yo letter`() {
        assertEquals(AppResolver.normalize("телега"), AppResolver.normalize("телёга"))
    }

    @Test
    fun `identical strings have similarity 1`() {
        assertEquals(1.0, AppResolver.similarity("telegram", "telegram"), 0.0001)
    }

    @Test
    fun `typo has high similarity`() {
        // "ютюб" vs "ютуб" — одна замена
        assertTrue(AppResolver.similarity("ютюб", "ютуб") > 0.7)
    }

    @Test
    fun `substring match scores high`() {
        assertTrue(AppResolver.similarity("youtube", "youtube music") > 0.8)
    }

    @Test
    fun `unrelated words have low similarity`() {
        assertTrue(AppResolver.similarity("телеграм", "калькулятор") < 0.5)
    }

    @Test
    fun `levenshtein computes expected distances`() {
        assertEquals(0, AppResolver.levenshtein("abc", "abc"))
        assertEquals(1, AppResolver.levenshtein("abc", "abd"))
        assertEquals(3, AppResolver.levenshtein("abc", "xyz"))
        assertEquals(3, AppResolver.levenshtein("", "abc"))
    }

    @Test
    fun `empty query has zero similarity`() {
        assertEquals(0.0, AppResolver.similarity("", "telegram"), 0.0001)
    }
}
