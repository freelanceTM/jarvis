package com.jarvis.assistant.presentation.chat

import android.content.Context
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.models.VoiceSettings
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.usecases.ClearChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SendPromptUseCase
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * C-02 (P0) regression test for the privacy-consent UI gate in ChatViewModel.
 *
 * Scenario:
 *   1. Ввод содержит email → PrivacyClassifier даёт PRIVATE.
 *   2. Первый вызов SendPromptUseCase возвращает Resource.NeedsConsent (без флага).
 *   3. ChatViewModel выставляет pendingCloudConsent в UiState.
 *   4. Нажатие «Отправить в облако» вызывает use case ПОВТОРНО с cloudExplicitlyAllowed=true.
 *   5. Второй вызов возвращает DirectAnswer; pendingCloudConsent очищен.
 *
 * Это — регрессия к P0-багу, когда cloudExplicitlyAllowed=true нигде не
 * выставлялся (приватные запросы навсегда блокировались без всякой карточки).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPrivacyConsentTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var getChatHistory: GetChatHistoryUseCase
    private lateinit var clearHistory: ClearChatHistoryUseCase
    private lateinit var sendPrompt: SendPromptUseCase
    private lateinit var getSettings: GetSettingsUseCase
    private lateinit var tts: TextToSpeechManager
    private lateinit var stt: SpeechRecognizerManager
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var messageRepository: MessageRepository
    private lateinit var memoryManager: JarvisMemoryManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Контекст — замокан, чтобы не тянуть Robolectric. Все строки
        // из R.string возвращаем как сами идентификаторы (для верификации
        // нам нужны только вызовы use case, не человекочитаемый текст).
        context = mockk(relaxed = true)
        every { context.getString(any()) } answers { "str-${firstArg<Int>()}" }

        getChatHistory = mockk(relaxed = true)
        coEvery { getChatHistory.invoke() } returns flowOf(emptyList())
        clearHistory = mockk(relaxed = true)
        getSettings = mockk(relaxed = true)
        every { getSettings.invoke() } returns flowOf(
            VoiceSettings(
                userName = "user",
                systemPrompt = "",
                speechRate = 1.0f,
                speechPitch = 1.0f,
                selectedModel = "local",
                isHeadsetOnlyMode = false,
                wakeWordSensitivity = 0.5f
            )
        )
        tts = mockk(relaxed = true)
        stt = mockk(relaxed = true)
        toolExecutor = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        coEvery { messageRepository.getRecentMessages(any()) } returns emptyList()

        memoryManager = mockk(relaxed = true)
        sendPrompt = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ChatViewModel(
        context = context,
        getChatHistoryUseCase = getChatHistory,
        clearChatHistoryUseCase = clearHistory,
        sendPromptUseCase = sendPrompt,
        getSettingsUseCase = getSettings,
        textToSpeechManager = tts,
        speechRecognizerManager = stt,
        toolExecutor = toolExecutor,
        messageRepository = messageRepository
    )

    @Test
    fun `private query surfaces NeedsConsent card and confirm retries with cloudExplicitlyAllowed=true`() =
        runTest {
            val vm = createViewModel()

            val privatePrompt = "мой email test@example.com, напиши ответ"
            val consentReturned = Resource.NeedsConsent(
                privacyLevel = PrivacyLevel.PRIVATE,
                prompt = "приватный запрос — отправить в облако?",
                retryOnConsentArgs = Resource.NeedsConsent.RetryArgs(
                    userPrompt = privatePrompt,
                    source = com.jarvis.assistant.agent.decision.RequestSource.CHAT,
                    privacyLevel = PrivacyLevel.PRIVATE
                )
            )
            val successAnswer = PromptExecutionResult.DirectAnswer("ок, ответил")

            // Мокаем use case: первый вызов (без флага) → NeedsConsent,
            // второй вызов (с флагом true) → Success.
            var sendCalls = 0
            val flags = mutableListOf<Boolean>()
            coEvery {
                sendPrompt.invoke(
                    userPrompt = any(),
                    source = any(),
                    privacyLevel = any(),
                    cloudExplicitlyAllowed = capture(flags)
                )
            } coAnswers {
                sendCalls++
                if (sendCalls == 1) consentReturned else Resource.Success(successAnswer)
            }

            // 2) Отправка приватного запроса
            vm.sendTextMessage(privatePrompt)
            testDispatcher.scheduler.advanceUntilIdle()

            // 3) В UiState должна появиться pendingCloudConsent карточка
            val stateAfterFirstCall = vm.uiState.value
            assertNotNull(
                "C-02: после приватного запроса должен выставляться pendingCloudConsent",
                stateAfterFirstCall.pendingCloudConsent
            )
            assertEquals(PrivacyLevel.PRIVATE, stateAfterFirstCall.pendingCloudConsent?.privacyLevel)
            assertEquals(1, sendCalls)
            assertEquals(false, flags.firstOrNull())
            assertNull(
                "Confirmation для tool-action не должен появляться вместо consent",
                stateAfterFirstCall.pendingConfirmation
            )

            // 4) Подтверждаем согласие — «Отправить в облако»
            val consent = stateAfterFirstCall.pendingCloudConsent!!
            vm.confirmCloudConsent(consent)
            testDispatcher.scheduler.advanceUntilIdle()

            // 5) Отправлен повторный вызов с флагом=true; карточка очищена
            assertEquals(2, sendCalls)
            assertEquals(true, flags.last())
            val stateAfterConfirm = vm.uiState.value
            assertNull(
                "После подтвержения pendingCloudConsent должен быть сброшен",
                stateAfterConfirm.pendingCloudConsent
            )
            assertFalse("isSending сброшен после ответа", stateAfterConfirm.isSending)
        }

    @Test
    fun `denyCloudConsent clears card without retrying sendPromptUseCase`() = runTest {
        val vm = createViewModel()

        var sendCalls = 0
        coEvery {
            sendPrompt.invoke(any(), any(), any(), any())
        } coAnswers {
            sendCalls++
            Resource.NeedsConsent(
                privacyLevel = PrivacyLevel.SENSITIVE,
                prompt = "чувствительные данные",
                retryOnConsentArgs = Resource.NeedsConsent.RetryArgs(
                    userPrompt = "мой пароль hh29sk",
                    source = com.jarvis.assistant.agent.decision.RequestSource.CHAT,
                    privacyLevel = PrivacyLevel.SENSITIVE
                )
            )
        }
        coEvery { messageRepository.insertMessage(any()) } returns 0L

        vm.sendTextMessage("мой пароль hh29sk")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingCloudConsent)
        assertEquals(1, sendCalls)

        vm.denyCloudConsent()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull("После отказа карточка сбрасывается", vm.uiState.value.pendingCloudConsent)
        assertEquals(
            "Отказ НЕ должен вызывать повторный sendPromptUseCase",
            1, sendCalls
        )
        // Сообщение-ассистент об отказе должно быть сохранено.
        coVerify(atLeast = 1) { messageRepository.insertMessage(match { it.role == MessageRole.ASSISTANT }) }
    }
}
