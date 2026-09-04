package com.jarvis.assistant.ai

import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.data.remote.JarvisApiClient
import com.jarvis.assistant.data.remote.MessageDto
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H-02 / Refactor #3: регрессионные тесты для [JarvisApiAiClient] после удаления
 * дублирующей классификации.
 *
 * После рефакторинга клиент НЕ запускает PrivacyClassifier повторно — он
 * доверяет пришедшему effective-уровню от [SendPromptUseCase], который
 * считается единственным источником истины на клиенте. Единственная проверка
 * здесь — invariant: PRIVATE/SENSITIVE без cloudExplicitlyAllowed не должны
 * попасть в сеть (защита от бага в вызывающем коде). Серверный AiRouter
 * всё равно переклассифицирует запрос (trust boundary).
 */
class JarvisApiAiClientPrivacyMatrixTest {

    private lateinit var network: JarvisApiClient

    @Before
    fun setUp() {
        network = mockk(relaxed = false)
        coEvery {
            network.execute(
                any(), any(), any(), any(),
                systemContext = any(),
                cloudExplicitlyAllowed = any(),
                history = any(),
                requestId = any()
            )
        } returns Resource.Success("ok")
    }

    @Test
    fun `NORMAL proceeds to network without consent`() = runBlocking {
        val ai = JarvisApiAiClient(network)
        val result = ai.completeWithContext(
            prompt = "какая погода", systemPrompt = "sys", source = "VOICE",
            effectivePrivacyLevel = PrivacyLevel.NORMAL,
            requiresWeb = false, cloudExplicitlyAllowed = false,
            history = emptyList()
        )
        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) {
            network.execute(
                text = "какая погода", source = "VOICE",
                privacyLevel = "NORMAL", requiresWeb = false,
                systemContext = "sys", cloudExplicitlyAllowed = false,
                history = any(), requestId = any()
            )
        }
    }

    @Test
    fun `SENSITIVE without consent is blocked (invariant defence)`() = runBlocking {
        val ai = JarvisApiAiClient(network)
        val result = ai.completeWithContext(
            prompt = "мой пароль XYZ", systemPrompt = "sys", source = "CHAT",
            effectivePrivacyLevel = PrivacyLevel.SENSITIVE,
            requiresWeb = false, cloudExplicitlyAllowed = false,
            history = emptyList()
        )
        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).exception is PrivacyCloudBlockedException)
        coVerify(exactly = 0) {
            network.execute(any(), any(), any(), any(),
                systemContext = any(), cloudExplicitlyAllowed = any(), history = any(), requestId = any())
        }
    }

    @Test
    fun `SENSITIVE with consent is sent to network`() = runBlocking {
        val ai = JarvisApiAiClient(network)
        val result = ai.completeWithContext(
            prompt = "мой пароль XYZ", systemPrompt = "sys", source = "VOICE",
            effectivePrivacyLevel = PrivacyLevel.SENSITIVE,
            requiresWeb = true, cloudExplicitlyAllowed = true,
            history = emptyList()
        )
        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) {
            network.execute(
                text = "мой пароль XYZ", source = "VOICE",
                privacyLevel = "SENSITIVE", requiresWeb = true,
                systemContext = "sys", cloudExplicitlyAllowed = true,
                history = any(), requestId = any()
            )
        }
    }

    @Test
    fun `system-role messages and blank messages are filtered from history (CR-03)`() = runBlocking {
        val ai = JarvisApiAiClient(network)
        ai.completeWithContext(
            prompt = "q", systemPrompt = "sys", source = "CHAT",
            effectivePrivacyLevel = PrivacyLevel.NORMAL,
            requiresWeb = false, cloudExplicitlyAllowed = false,
            history = listOf(
                Message(role = MessageRole.SYSTEM, text = "hidden system prompt"),
                Message(role = MessageRole.USER, text = "hi"),
                Message(role = MessageRole.ASSISTANT, text = "   "),
                Message(role = MessageRole.ASSISTANT, text = "hello"),
            )
        )
        val slot = mutableListOf<List<MessageDto>>()
        coVerify(exactly = 1) {
            network.execute(any(), any(), any(), any(),
                systemContext = any(), cloudExplicitlyAllowed = any(),
                history = capture(slot), requestId = any())
        }
        assertEquals(2, slot.single().size)
        assertEquals("user", slot.single()[0].role)
        assertEquals("hi", slot.single()[0].content)
        assertEquals("assistant", slot.single()[1].role)
        assertFalse(slot.single().any { it.role == "system" })
        assertFalse(slot.single().any { it.content.isBlank() })
    }

    @Test
    fun `empty history is passed as empty list`() = runBlocking {
        val ai = JarvisApiAiClient(network)
        ai.completeWithContext(
            prompt = "q", systemPrompt = "", source = "CHAT",
            effectivePrivacyLevel = PrivacyLevel.NORMAL,
            requiresWeb = false,
        )
        val slot = mutableListOf<List<MessageDto>>()
        coVerify(exactly = 1) {
            network.execute(any(), any(), any(), any(),
                systemContext = null,
                cloudExplicitlyAllowed = false,
                history = capture(slot), requestId = any())
        }
        assertNotNull(slot.single())
        assertEquals(0, slot.single().size)
    }

    @Test
    fun `baseline complete() uses NORMAL level and CHAT source (legacy translator path)`() = runBlocking {
        val net = mockk<JarvisApiClient>(relaxed = false)
        coEvery {
            net.execute(any(), any(), any(), any(),
                systemContext = any(), cloudExplicitlyAllowed = any(), history = any(), requestId = any())
        } returns Resource.Success("ok")
        val ai = JarvisApiAiClient(net)
        val result = ai.complete(
            prompt = "hello world",
            systemPrompt = "translate",
            history = listOf(Message(role = MessageRole.USER, text = "prev"))
        )
        assertTrue(result is Resource.Success)
        val sourceSlot = mutableListOf<String>()
        val levelSlot = mutableListOf<String>()
        val consentSlot = mutableListOf<Boolean>()
        coVerify(exactly = 1) {
            net.execute(
                text = "hello world",
                source = capture(sourceSlot),
                privacyLevel = capture(levelSlot),
                requiresWeb = false,
                systemContext = "translate",
                cloudExplicitlyAllowed = capture(consentSlot),
                history = any(),
                requestId = any()
            )
        }
        assertEquals("CHAT", sourceSlot.single())
        assertEquals("NORMAL", levelSlot.single())
        assertEquals(true, consentSlot.single())
    }
}
