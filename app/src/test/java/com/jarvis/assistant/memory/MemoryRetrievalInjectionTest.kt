package com.jarvis.assistant.memory

import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RepositoryCloudAiExecutor
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.agent.localai.JarvisLocalPromptBuilder
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MEMORY: стадия «Query → Memory retrieval → Relevant memories only → AI».
 *
 * В LLM уходит только retrieval-блок ([ExecutionRequest.memoryContext],
 * top-K, ≤800 символов), а не вся память. Блок:
 *  - участвует в privacy-классификации (память выведена из реплик
 *    пользователя — приватный факт в памяти не уходит в облако под
 *    «безобидным» запросом);
 *  - попадает в cloud system prompt (дорого — потому bounded);
 *  - попадает в офлайн-промпт (локальная модель знает long-term факты).
 */
class MemoryRetrievalInjectionTest {

    private lateinit var aiRepo: AIRepository
    private lateinit var executor: RepositoryCloudAiExecutor

    @Before
    fun setUp() {
        val settings = mockk<SettingsRepository>()
        val tools = mockk<ToolRegistry>()
        val network = mockk<NetworkMonitor>()
        aiRepo = mockk()
        every { settings.systemPromptFlow } returns flowOf("BASE-SYS")
        every { tools.buildTargetedSystemPrompt(any()) } returns ""
        every { network.isCurrentlyOnline() } returns true
        coEvery {
            aiRepo.generateResponse(any(), any(), any(), any(), any(),
                cloudExplicitlyAllowed = any(), history = any())
        } returns Resource.Success("ok")
        executor = RepositoryCloudAiExecutor(aiRepo, settings, tools, network)
    }

    // ------------------------------------------------ classification includes memory

    @Test
    fun `benign memory keeps request NORMAL`() {
        val request = ExecutionRequest.withContextualClassification(
            text = "какая погода будет завтра",
            source = RequestSource.CHAT,
            memoryContext = "Память о пользователе:\n• любит кофе без сахара"
        )
        assertEquals(PrivacyLevel.NORMAL, request.effectivePrivacyLevel)
        assertFalse(request.effectivePrivacyLevel.isCloudRestricted)
    }

    @Test
    fun `private fact stored in memory blocks cloud without consent`() {
        // «Безобидный» запрос, но в retrieval-блок попала сохранённая ранее
        // памятка с паролем — классификация обязана это увидеть.
        val request = ExecutionRequest.withContextualClassification(
            text = "напомни, о чём мы говорили",
            source = RequestSource.CHAT,
            memoryContext = "Память о пользователе:\n• пароль от роутера: admin4821"
        )
        assertTrue(request.effectivePrivacyLevel.isCloudRestricted)
    }

    @Test
    fun `blank memory is excluded from classification input`() {
        val request = ExecutionRequest.withContextualClassification(
            text = "привет",
            source = RequestSource.CHAT,
            memoryContext = ""
        )
        assertEquals(PrivacyLevel.NORMAL, request.effectivePrivacyLevel)
    }

    // ------------------------------------------------------- cloud path

    @Test
    fun `cloud system prompt contains retrieved memory block`() = runBlocking {
        executor.complete(
            ExecutionRequest(
                text = "что ты помнишь обо мне",
                source = RequestSource.CHAT,
                memoryContext = "Текущий контекст: тема: отпуск\nПамять о пользователе:\n• был в Японии в 2024"
            )
        )
        val sysSlot = slot<String>()
        coVerify(exactly = 1) {
            aiRepo.generateResponse(capture(sysSlot), any(), any(), any(), any(),
                cloudExplicitlyAllowed = any(), history = any())
        }
        assertTrue(sysSlot.captured.contains("BASE-SYS"))
        assertTrue(sysSlot.captured.contains("был в Японии в 2024"))
    }

    @Test
    fun `empty memory adds nothing to cloud prompt`() = runBlocking {
        executor.complete(
            ExecutionRequest(
                text = "q",
                source = RequestSource.VOICE,
                memoryContext = ""
            )
        )
        val sysSlot = slot<String>()
        coVerify(exactly = 1) {
            aiRepo.generateResponse(capture(sysSlot), any(), any(), any(), any(),
                cloudExplicitlyAllowed = any(), history = any())
        }
        assertEquals("BASE-SYS", sysSlot.captured)
    }

    // ------------------------------------------------------- local path

    @Test
    fun `local prompt includes memory block for offline recall`() {
        val prompt = JarvisLocalPromptBuilder().build(
            ExecutionRequest(
                text = "как зовут мою дочь?",
                source = RequestSource.VOICE,
                memoryContext = "Память о пользователе:\n• дочь Алиса"
            )
        )
        assertTrue(prompt.contains("дочь Алиса"))
        assertTrue(prompt.contains("как зовут мою дочь?"))
    }

    @Test
    fun `local prompt without memory stays clean`() {
        val prompt = JarvisLocalPromptBuilder().build(
            ExecutionRequest(
                text = "привет",
                source = RequestSource.VOICE,
                memoryContext = ""
            )
        )
        assertFalse(prompt.contains("Память о пользователе"))
        assertTrue(prompt.contains("привет"))
    }
}
