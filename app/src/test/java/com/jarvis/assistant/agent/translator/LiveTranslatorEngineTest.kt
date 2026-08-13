package com.jarvis.assistant.agent.translator

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.repository.AIRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LiveTranslatorEngineTest {

    private lateinit var engine: LiveTranslatorEngine
    private lateinit var fakeAiRepository: FakeAIRepository

    class FakeAIRepository : AIRepository {
        override suspend fun generateResponse(
            prompt: String,
            systemPrompt: String,
            history: List<Message>
        ): Resource<String> {
            return when {
                prompt.contains("Hello") -> Resource.Success("Привет")
                prompt.contains("Как дела") -> Resource.Success("How are you")
                else -> Resource.Success("Nähili (Türkmençe)")
            }
        }
    }

    @Before
    fun setUp() {
        fakeAiRepository = FakeAIRepository()
        engine = LiveTranslatorEngine(fakeAiRepository)
    }

    @Test
    fun testLiveTranslationRussianToEnglish() = runBlocking {
        val result = engine.translate("Как дела", sourceLang = "ru", targetLang = "en")
        assertEquals("How are you", result)
    }

    @Test
    fun testLiveTranslationEnglishToRussian() = runBlocking {
        val result = engine.translate("Hello", sourceLang = "en", targetLang = "ru")
        assertEquals("Привет", result)
    }

    @Test
    fun testSupportedLanguagesList() {
        val codes = LiveTranslatorEngine.SUPPORTED_LANGUAGES.map { it.code }
        assertTrue(codes.contains("ru"))
        assertTrue(codes.contains("en"))
        assertTrue(codes.contains("tk"))
        assertTrue(codes.contains("tr"))
    }
}
