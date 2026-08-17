package com.jarvis.assistant.agent.apps

import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты каскада разрешения приложений (чистая логика, без PackageManager):
 *
 *   exact match → normalized match → alias match → fuzzy match → semantic match
 *
 * Примеры из требований: «ютуб», «ютюб», «youtube», «You Tube», «ютубчик»
 * должны разрешаться в com.google.android.youtube — но только если установлены.
 */
class AppResolverTest {

    private lateinit var cascade: AppMatchCascade

    private val youtube = InstalledApp("com.google.android.youtube", "YouTube")
    private val telegram = InstalledApp("org.telegram.messenger", "Telegram")
    private val maps = InstalledApp("com.google.android.apps.maps", "Google Maps")

    @Before
    fun setUp() {
        cascade = AppMatchCascade(SemanticTextMatcher())
    }

    // ===========================================
    // Основной сценарий: youtube разрешается во всех формах
    // ===========================================

    @Test
    fun `youtube alias resolves to youtube package`() {
        // Кириллические формы — через alias; латиница «youtube» матчится
        // точным label'ом (это даже строже алиаса).
        listOf("ютуб", "ютюб", "ют", "ю туб").forEach { query ->
            val resolution = cascade.resolve(listOf(youtube, telegram, maps), query)
            assertTrue("'$query' должен разрешиться, получено $resolution", resolution is AppResolution.Resolved)
            resolution as AppResolution.Resolved
            assertEquals("com.google.android.youtube", resolution.packageName)
            assertEquals(AppResolution.MatchKind.ALIAS, resolution.matchedBy)
        }
    }

    @Test
    fun `youtube latin resolves via exact label`() {
        val resolution = cascade.resolve(listOf(youtube, telegram, maps), "youtube")

        assertTrue(resolution is AppResolution.Resolved)
        resolution as AppResolution.Resolved
        assertEquals("com.google.android.youtube", resolution.packageName)
        assertEquals(AppResolution.MatchKind.EXACT_LABEL, resolution.matchedBy)
    }

    @Test
    fun `you tube with space resolves via normalized match`() {
        val resolution = cascade.resolve(listOf(youtube, telegram, maps), "You Tube")

        assertTrue(resolution is AppResolution.Resolved)
        resolution as AppResolution.Resolved
        assertEquals("com.google.android.youtube", resolution.packageName)
        assertEquals(AppResolution.MatchKind.NORMALIZED, resolution.matchedBy)
    }

    @Test
    fun `diminutive ютубчик resolves via alias prefix`() {
        val resolution = cascade.resolve(listOf(youtube, telegram, maps), "ютубчик")

        assertTrue(resolution is AppResolution.Resolved)
        resolution as AppResolution.Resolved
        assertEquals("com.google.android.youtube", resolution.packageName)
        assertEquals(AppResolution.MatchKind.ALIAS, resolution.matchedBy)
    }

    // ===========================================
    // Проверка установленности — критично
    // ===========================================

    @Test
    fun `known alias for not installed app returns NotInstalled`() {
        // YouTube НЕ в списке установленных → честный NotInstalled.
        val resolution = cascade.resolve(listOf(telegram, maps), "ютуб")

        assertTrue(resolution is AppResolution.NotInstalled)
        assertEquals("com.google.android.youtube", (resolution as AppResolution.NotInstalled).knownPackage)
    }

    @Test
    fun `exact package match works for installed app`() {
        val resolution = cascade.resolve(listOf(youtube, telegram, maps), "org.telegram.messenger")

        assertTrue(resolution is AppResolution.Resolved)
        resolution as AppResolution.Resolved
        assertEquals("org.telegram.messenger", resolution.packageName)
        assertEquals(AppResolution.MatchKind.EXACT_PACKAGE, resolution.matchedBy)
    }

    @Test
    fun `unknown app is reported as unknown`() {
        val resolution = cascade.resolve(listOf(youtube, telegram, maps), "несуществующееприложение")

        assertTrue(resolution is AppResolution.Unknown)
    }

    // ===========================================
    // Нормализация и сходство
    // ===========================================

    @Test
    fun `normalize lowercases and strips punctuation`() {
        assertEquals("youtube", AppMatchCascade.normalize("  YouTube!  "))
        assertEquals("телеграм", AppMatchCascade.normalize("Телеграм,"))
    }

    @Test
    fun `normalize unifies yo letter`() {
        assertEquals(AppMatchCascade.normalize("телега"), AppMatchCascade.normalize("телёга"))
    }

    @Test
    fun `compact removes spaces and keeps letters`() {
        assertEquals("youtube", AppMatchCascade.compact("You Tube"))
        assertEquals("com.google.android.youtube", AppMatchCascade.compact("com.google.android.youtube"))
    }

    @Test
    fun `identical strings have similarity 1`() {
        assertEquals(1.0, AppMatchCascade.similarity("telegram", "telegram"), 0.0001)
    }

    @Test
    fun `typo has high similarity`() {
        // "ютюб" vs "ютуб" — одна замена
        assertTrue(AppMatchCascade.similarity("ютюб", "ютуб") > 0.7)
    }

    @Test
    fun `substring match scores high`() {
        assertTrue(AppMatchCascade.similarity("youtube", "youtube music") > 0.8)
    }

    @Test
    fun `unrelated words have low similarity`() {
        assertTrue(AppMatchCascade.similarity("телеграм", "калькулятор") < 0.5)
    }

    @Test
    fun `levenshtein computes expected distances`() {
        assertEquals(0, AppMatchCascade.levenshtein("abc", "abc"))
        assertEquals(1, AppMatchCascade.levenshtein("abc", "abd"))
        assertEquals(3, AppMatchCascade.levenshtein("abc", "xyz"))
        assertEquals(3, AppMatchCascade.levenshtein("", "abc"))
    }
}
