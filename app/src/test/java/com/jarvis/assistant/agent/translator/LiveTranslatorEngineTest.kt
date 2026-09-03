package com.jarvis.assistant.agent.translator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты движка перевода: проверяют, что движок выбирает подходящий провайдер
 * и НЕ выдаёт неуспешный перевод за успешный.
 */
class LiveTranslatorEngineTest {

    /** Провайдер, который умеет переводить только заданную пару языков. */
    private class FakeProvider(
        override val providerId: String,
        override val isOffline: Boolean,
        private val available: Boolean = true,
        private val supportedTargets: Set<String> = setOf("ru", "en"),
        private val response: (String) -> TranslationResult = { text ->
            TranslationResult.Success("translated:$text", "auto", "ru", "fake")
        }
    ) : TranslationProvider {
        var translateCalls = 0
            private set

        override fun supports(sourceLang: String, targetLang: String) = targetLang in supportedTargets
        override suspend fun isAvailable() = available
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): TranslationResult {
            translateCalls++
            return response(text)
        }
    }

    @Test
    fun `returns success from available provider`() = runBlocking {
        val provider = FakeProvider("online", isOffline = false)
        val engine = LiveTranslatorEngine(setOf(provider))

        val result = engine.translateStructured("Hello", "en", "ru")

        assertTrue(result is TranslationResult.Success)
        assertEquals("translated:Hello", (result as TranslationResult.Success).translatedText)
    }

    @Test
    fun `unsupported language pair is reported honestly`() = runBlocking {
        val engine = LiveTranslatorEngine(setOf(FakeProvider("online", isOffline = false)))

        val result = engine.translateStructured("Hello", "en", "ja")

        assertTrue("Expected Unsupported, got $result", result is TranslationResult.Unsupported)
    }

    @Test
    fun `offline provider is preferred over online`() = runBlocking {
        val offline = FakeProvider("offline", isOffline = true)
        val online = FakeProvider("online", isOffline = false)
        val engine = LiveTranslatorEngine(setOf(online, offline))

        engine.translateStructured("Hello", "en", "ru")

        assertEquals("Offline provider must be tried first", 1, offline.translateCalls)
        assertEquals(0, online.translateCalls)
    }

    @Test
    fun `falls back to next provider when first is unavailable`() = runBlocking {
        val offline = FakeProvider("offline", isOffline = true, available = false)
        val online = FakeProvider("online", isOffline = false)
        val engine = LiveTranslatorEngine(setOf(offline, online))

        val result = engine.translateStructured("Hello", "en", "ru")

        assertTrue(result is TranslationResult.Success)
        assertEquals(1, online.translateCalls)
    }

    @Test
    fun `no available provider never returns success`() = runBlocking {
        val offline = FakeProvider("offline", isOffline = true, available = false)
        val engine = LiveTranslatorEngine(setOf(offline))

        val result = engine.translateStructured("Hello", "en", "ru")

        assertFalse(result is TranslationResult.Success)
        assertTrue(result is TranslationResult.ModelUnavailable)
    }

    @Test
    fun `translateOrNull returns null when translation failed`() = runBlocking {
        val failing = FakeProvider("online", isOffline = false) {
            TranslationResult.Error("boom")
        }
        val engine = LiveTranslatorEngine(setOf(failing))

        assertNull(engine.translateOrNull("Hello", "en", "ru"))
    }

    @Test
    fun `empty text is not translated`() = runBlocking {
        val engine = LiveTranslatorEngine(setOf(FakeProvider("online", isOffline = false)))
        assertTrue(engine.translateStructured("   ", "en", "ru") is TranslationResult.Error)
    }

    @Test
    fun `supported languages contain core set`() {
        val codes = LiveTranslatorEngine.SUPPORTED_LANGUAGES.map { it.code }
        assertTrue(codes.containsAll(listOf("ru", "en", "tk", "tr")))
    }
    @Test
    fun `local-first cascade - local failure falls through to cloud`() = runBlocking {
        // Локальный провайдер «есть, но модель не готова» — движок обязан
        // дойти до облачного и вернуть его успех (fallback, не заглушка).
        val local = FakeProvider("local_llm", isOffline = true, available = false)
        val cloud = FakeProvider("llm", isOffline = false)
        val engine = LiveTranslatorEngine(setOf(cloud, local))

        val result = engine.translateStructured("Hello", "en", "ru")

        assertTrue(result is TranslationResult.Success)
        // Вызовов у недоступного локального не было; облачный отработал.
        assertEquals(0, local.translateCalls)
        assertEquals(1, cloud.translateCalls)
    }

    @Test
    fun `local provider answers first when ready - cloud is not called`() = runBlocking {
        val local = FakeProvider("local_llm", isOffline = true)
        val cloud = FakeProvider("llm", isOffline = false)
        val engine = LiveTranslatorEngine(setOf(cloud, local))

        val result = engine.translateStructured("Hello", "en", "ru")

        assertTrue(result is TranslationResult.Success)
        assertEquals(1, local.translateCalls)
        assertEquals(0, cloud.translateCalls)
    }

}
