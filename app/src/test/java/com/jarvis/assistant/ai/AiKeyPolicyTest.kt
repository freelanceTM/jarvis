package com.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт аудита #3 (CRITICAL): ключ пользователя не должен уходить в чужой сервис.
 *
 * Главный инвариант: fallback к OpenRouter возможен ТОЛЬКО с ключом
 * sk-or-...; Gemini-ключ (AIza...) никогда не отправляется в OpenRouter.
 */
class AiKeyPolicyTest {

    @Test
    fun `openrouter keys are detected by prefix`() {
        assertTrue(AiKeyPolicy.isOpenRouterKey("sk-or-v1-abcdef123456"))
        assertFalse(AiKeyPolicy.isOpenRouterKey("AIzaSyBlaBlaBla"))
    }

    @Test
    fun `groq and openai keys are detected`() {
        assertTrue(AiKeyPolicy.isGroqKey("gsk_abcdef"))
        assertTrue(AiKeyPolicy.isOpenAiKey("sk-proj-abcdef"))
        assertFalse(AiKeyPolicy.isGroqKey("sk-or-v1-abc"))
    }

    @Test
    fun `gemini key is everything non-openai-compatible`() {
        assertTrue(AiKeyPolicy.isGeminiKey("AIzaSyBlaBlaBla1234567890"))
        assertFalse(AiKeyPolicy.isGeminiKey("sk-or-v1-abc"))
        assertFalse(AiKeyPolicy.isGeminiKey("gsk_abc"))
        assertFalse(AiKeyPolicy.isGeminiKey("sk-abc"))
    }

    @Test
    fun `fallback to openrouter is allowed only for sk-or keys`() {
        // Ключевой guard аудита #3:
        assertTrue(AiKeyPolicy.canFallbackToOpenRouter("sk-or-v1-abc"))
        assertFalse("Gemini-ключ НЕ должен уходить в OpenRouter", AiKeyPolicy.canFallbackToOpenRouter("AIzaSyBlaBlaBla"))
        assertFalse(AiKeyPolicy.canFallbackToOpenRouter("gsk_abc"))
        assertFalse(AiKeyPolicy.canFallbackToOpenRouter("sk-abc"))
        assertFalse(AiKeyPolicy.canFallbackToOpenRouter(""))
    }

    @Test
    fun `geo-block message explains the fix without leaking secrets`() {
        val msg = AiKeyPolicy.geminiBlockedMessage(403)

        assertTrue(msg.contains("заблокирован"))
        assertTrue("Сообщение должно подсказывать решение", msg.contains("OpenRouter"))
        assertFalse("Сообщение не должно содержать ключи", msg.contains("AIza"))
    }

    @Test
    fun `rate limit and generic messages`() {
        assertEquals(
            "Лимит запросов Gemini исчерпан. Пожалуйста, подождите 30 секунд.",
            AiKeyPolicy.geminiBlockedMessage(429)
        )
        assertEquals("Ошибка сервера AI (500).", AiKeyPolicy.geminiBlockedMessage(500))
    }
}
