package com.jarvis.assistant.agent.decision

import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 7 / CR-03 regression: [RepositoryCloudAiExecutor] должен пробрасывать
 * [ExecutionRequest.history] в AIRepository без потерь, не дублируя systemPrompt,
 * не перезаписывая текущий prompt и не обрезая порядок.
 */
class RepositoryCloudAiExecutorHistoryTest {

    private lateinit var aiRepo: AIRepository
    private lateinit var settings: SettingsRepository
    private lateinit var tools: ToolRegistry
    private lateinit var network: NetworkMonitor
    private lateinit var executor: RepositoryCloudAiExecutor

    @Before
    fun setUp() {
        aiRepo = mockk(relaxed = false)
        settings = mockk()
        tools = mockk()
        network = mockk()
        every { settings.systemPromptFlow } returns flowOf("BASE-SYS")
        every { tools.buildTargetedSystemPrompt(any()) } returns ""
        every { network.isCurrentlyOnline() } returns true
        coEvery {
            aiRepo.generateResponse(
                any(), any(), any(), any(), any(),
                cloudExplicitlyAllowed = any(), history = any()
            )
        } returns Resource.Success("ok")
        executor = RepositoryCloudAiExecutor(aiRepo, settings, tools, network)
    }

    private fun m(role: MessageRole, text: String) = Message(role = role, text = text)

    @Test
    fun `empty history is forwarded as empty list`() = runBlocking {
        val req = ExecutionRequest(
            text = "q", source = RequestSource.VOICE, history = emptyList()
        )
        executor.complete(req)
        val slot = mutableListOf<List<Message>>()
        coVerify(exactly = 1) {
            aiRepo.generateResponse(any(), any(), any(), any(), any(),
                cloudExplicitlyAllowed = any(), history = capture(slot))
        }
        assertNotNull(slot.single())
        assertEquals(0, slot.single().size)
    }

    @Test
    fun `single message is forwarded verbatim with order preserved`() = runBlocking {
        val hist = listOf(m(MessageRole.USER, "единственное сообщение"))
        executor.complete(ExecutionRequest(text = "q2", source = RequestSource.CHAT, history = hist))
        val slot = mutableListOf<List<Message>>()
        coVerify { aiRepo.generateResponse(any(), any(), any(), any(), any(), cloudExplicitlyAllowed = any(), history = capture(slot)) }
        assertEquals(1, slot.single().size)
        assertEquals("единственное сообщение", slot.single()[0].text)
        assertEquals(MessageRole.USER, slot.single()[0].role)
    }

    @Test
    fun `multi-message history preserves exact order and no-loss`() = runBlocking {
        val hist = listOf(
            m(MessageRole.USER, "u1"),
            m(MessageRole.ASSISTANT, "a1"),
            m(MessageRole.USER, "u2"),
            m(MessageRole.ASSISTANT, "a2"),
            m(MessageRole.USER, "u3")
        )
        executor.complete(ExecutionRequest(text = "current", source = RequestSource.VOICE, history = hist))
        val slot = mutableListOf<List<Message>>()
        coVerify { aiRepo.generateResponse(any(), any(), any(), any(), any(), cloudExplicitlyAllowed = any(), history = capture(slot)) }
        val forwarded = slot.single()
        assertEquals(5, forwarded.size)
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3"), forwarded.map(Message::text))
    }

    @Test
    fun `current prompt is NOT overwritten by history content`() = runBlocking {
        val hist = listOf(m(MessageRole.USER, "OLD-PROMPT"), m(MessageRole.ASSISTANT, "OLD-ANS"))
        executor.complete(ExecutionRequest(text = "CURRENT-PROMPT", source = RequestSource.VOICE, history = hist))
        val promptSlot = mutableListOf<String>()
        coVerify { aiRepo.generateResponse(capture(promptSlot), any(), any(), any(), any(), cloudExplicitlyAllowed = any(), history = any()) }
        assertEquals("CURRENT-PROMPT", promptSlot.single())
    }

    @Test
    fun `systemPrompt is concatenated, not duplicated into history`() = runBlocking {
        every { tools.buildTargetedSystemPrompt(any()) } returns "TOOLS"
        executor.complete(ExecutionRequest(
            text = "q", source = RequestSource.CHAT,
            history = listOf(m(MessageRole.USER, "u"))
        ))
        val sysSlot = mutableListOf<String>()
        val histSlot = mutableListOf<List<Message>>()
        coVerify { aiRepo.generateResponse(any(), capture(sysSlot), any(), any(), any(), cloudExplicitlyAllowed = any(), history = capture(histSlot)) }
        assertTrue("sys prompt should contain BASE-SYS", sysSlot.single().contains("BASE-SYS"))
        assertTrue("sys prompt should contain TOOLS", sysSlot.single().contains("TOOLS"))
        assertTrue("history should not contain BASE-SYS",
            histSlot.single().none { it.text.contains("BASE-SYS") })
    }

    @Test
    fun `requiresWeb privacy consent and source are forwarded`() = runBlocking {
        executor.complete(ExecutionRequest(
            text = "найди в сети", source = RequestSource.VOICE,
            requiresWeb = true,
            privacyLevel = PrivacyLevel.PRIVATE,
            cloudExplicitlyAllowed = true,
            history = listOf(m(MessageRole.USER, "u"))
        ))
        coVerify(exactly = 1) {
            aiRepo.generateResponse(
                prompt = "найди в сети",
                systemPrompt = any(),
                source = "VOICE",
                privacyLevel = PrivacyLevel.PRIVATE.name,
                requiresWeb = true,
                cloudExplicitlyAllowed = true,
                history = any()
            )
        }
    }
}
