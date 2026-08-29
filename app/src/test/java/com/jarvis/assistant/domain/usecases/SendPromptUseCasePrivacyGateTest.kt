package com.jarvis.assistant.domain.usecases

import android.content.Context
import com.jarvis.assistant.agent.decision.*
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.pipeline.AgentPipeline
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * C-02 (P0) privacy-gate unit tests for [SendPromptUseCase].
 *
 * Проверяют контракт:
 *   - NORMAL с cloudExplicitlyAllowed=false      → pipeline вызывается.
 *   - PRIVATE / SENSITIVE без флага              → НЕ вызывается pipeline,
 *                                                   возвращается NeedsConsent.
 *   - PRIVATE / SENSITIVE с флагом true          → pipeline вызывается
 *                                                   (согласие получено).
 *
 * Слом этого контракта — повторение P0-бага, когда приватные запросы
 * навсегда блокируются без UI-согласия.
 */
class SendPromptUseCasePrivacyGateTest {

    private lateinit var context: Context
    private lateinit var messageRepo: MessageRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var memoryManager: JarvisMemoryManager
    private lateinit var pipeline: AgentPipeline
    private lateinit var useCase: SendPromptUseCase

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.getString(any()) } answers { "str-${firstArg<Int>()}" }

        messageRepo = mockk(relaxed = true)
        coEvery { messageRepo.getRecentMessages(any()) } returns emptyList()
        coEvery { messageRepo.insertMessage(any()) } returns 0L

        settingsRepo = mockk(relaxed = true)
        every { settingsRepo.systemPromptFlow } returns flowOf("")

        memoryManager = mockk(relaxed = true)
        every { memoryManager.workingMemory.resolveContextualQuery(any()) } answers { firstArg<String>() }

        pipeline = mockk(relaxed = true)
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Success(PromptExecutionResult.DirectAnswer("ok"))

        useCase = SendPromptUseCase(context, messageRepo, settingsRepo, memoryManager, pipeline)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `normal query passes through to pipeline without consent`() = runTest {
        // Простой запрос без приватных данных — классификатор даёт NORMAL →
        // pipeline должен быть вызван с cloudExplicitlyAllowed=false.
        val result = useCase("какая погода в Лондоне", RequestSource.CHAT, PrivacyLevel.NORMAL, false)
        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { pipeline.process(match<ExecutionRequest> {
            !it.cloudExplicitlyAllowed && it.effectivePrivacyLevel == PrivacyLevel.NORMAL
        }) }
    }

    @Test
    fun `PRIVATE query without consent returns NeedsConsent and does NOT call pipeline`() = runTest {
        // C-02 главная проверка: письмо с email без флага — не ходим в сеть.
        val emailQuery = "напиши ответ на test@example.com"
        val result = useCase(emailQuery, RequestSource.CHAT, PrivacyLevel.PRIVATE, false)

        assertTrue(
            "PRIVATE без consent должен вернуть NeedsConsent, а не Success/Error",
            result is Resource.NeedsConsent
        )
        val consent = result as Resource.NeedsConsent
        assertEquals(PrivacyLevel.PRIVATE, consent.privacyLevel)
        assertEquals(emailQuery.trim(), consent.retryOnConsentArgs.userPrompt)
        // Pipeline НЕ должен вызываться до согласия.
        coVerify(exactly = 0) { pipeline.process(any<ExecutionRequest>()) }
    }

    @Test
    fun `SENSITIVE query without consent returns NeedsConsent`() = runTest {
        val passwordQuery = "мой пароль Password123"
        val result = useCase(passwordQuery, RequestSource.CHAT, PrivacyLevel.SENSITIVE, false)
        assertTrue(result is Resource.NeedsConsent)
        assertEquals(PrivacyLevel.SENSITIVE, (result as Resource.NeedsConsent).privacyLevel)
        coVerify(exactly = 0) { pipeline.process(any<ExecutionRequest>()) }
    }

    @Test
    fun `PRIVATE query WITH explicit consent goes through to pipeline`() = runTest {
        // Пользователь нажал «Отправить в облако» — cloudExplicitlyAllowed=true.
        val result = useCase(
            userPrompt = "мой email test@example.com",
            source = RequestSource.VOICE,
            privacyLevel = PrivacyLevel.PRIVATE,
            cloudExplicitlyAllowed = true
        )
        assertTrue("Согласие получено → ответ должен быть Success", result is Resource.Success)
        coVerify(exactly = 1) { pipeline.process(match<ExecutionRequest> {
            it.cloudExplicitlyAllowed &&
                it.effectivePrivacyLevel == PrivacyLevel.PRIVATE &&
                it.source == RequestSource.VOICE
        }) }
    }
}
