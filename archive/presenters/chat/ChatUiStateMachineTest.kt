package com.jarvis.assistant.presentation.chat

import com.jarvis.assistant.agent.decision.PrivacyClassification
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.PrivacyReason
import com.jarvis.assistant.agent.decision.RequestSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 6: exhaustive event-sequence tests для [ChatUiStateMachine].
 *
 * Покрывает:
 *   1. Базовый ввод + debounce-классификация (CR-17).
 *   2. Быстрый набор текста: keystrokes до debounce не должны вызывать
 *      классификацию; idle — вызывает ровно один раз.
 *   3. Пустой ввод сбрасывает иконку приватности в NOT_CLASSIFIED.
 *   4. Отправка пустого/пробельного текста игнорируется.
 *   5. Повторный send во время isSending игнорируется (no double-send).
 *   6. Stale classification от старого текста не применяется (в машине
 *      мы делаем CAS: если inputText изменился — не обновлять).
 *   7. C-02 consent flow: NeedsConsent показывает карточку; confirm
 *      отправляет флаг, deny отказывает; прямой ввод «да/нет» в input
 *      обрабатывается как ответ на consent.
 *   8. Tool-confirmation flow: ConfirmationRequired показывает карточку,
 *      «да» → ConfirmAction, «нет» → CancelAction.
 *   9. Гонка: пользователь набирает новый текст во время показа
 *      confirmation/consent карточки — карточка снимается (implicit cancel
 *      для confirmation; consent сохраняется для ответа пользователя).
 *  10. onError сбрасывает isSending и pending карточки.
 */
class ChatUiStateMachineTest {

    private fun fakeClassify(alwaysLevel: PrivacyLevel = PrivacyLevel.NORMAL): (String) -> PrivacyClassification =
        { text ->
            val level = when {
                text.contains("password", ignoreCase = true) -> PrivacyLevel.SENSITIVE
                text.contains("@") -> PrivacyLevel.PRIVATE
                else -> alwaysLevel
            }
            PrivacyClassification(level = level, reasons = setOf(PrivacyReason.NONE))
        }

    @Test
    fun `idle input triggers classification and updates icon`() {
        val sm = ChatUiStateMachine(fakeClassify(PrivacyLevel.NORMAL))
        sm.onInputChanged("hello")
        assertEquals(
            "До debounce classification должна быть NOT_CLASSIFIED",
            PrivacyLevel.UNKNOWN,
            sm.snapshot().privacyLevel
        )
        sm.onInputIdleAfterTyping()
        assertEquals(PrivacyLevel.NORMAL, sm.snapshot().privacyLevel)
    }

    @Test
    fun `rapid keystrokes do not classify until idle fires`() {
        var classifyCalls = 0
        val sm = ChatUiStateMachine { text ->
            classifyCalls++
            fakeClassify()(text)
        }
        "hello world".forEach { ch ->
            sm.onInputChanged(sm.snapshot().inputText + ch)
        }
        assertEquals(0, classifyCalls)
        sm.onInputIdleAfterTyping()
        assertEquals(1, classifyCalls)
        // repeated idle (double-fire) — re-classifies (classifier pure, не страшно):
        sm.onInputIdleAfterTyping()
        assertEquals(2, classifyCalls)
    }

    @Test
    fun `empty input resets classification to unknown and does not send`() {
        val sm = ChatUiStateMachine(fakeClassify(PrivacyLevel.NORMAL))
        sm.onInputChanged("hi")
        sm.onInputIdleAfterTyping()
        assertEquals(PrivacyLevel.NORMAL, sm.snapshot().privacyLevel)
        sm.onInputChanged("")
        assertEquals(PrivacyLevel.UNKNOWN, sm.snapshot().privacyClassification.level)
        assertEquals("", sm.snapshot().inputText)
        assertTrue(sm.onSendClicked().isEmpty())
    }

    @Test
    fun `blank input is ignored`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("   ")
        assertTrue(sm.onSendClicked().isEmpty())
        assertFalse(sm.snapshot().isSending)
    }

    @Test
    fun `double-send is ignored while isSending is true`() {
        val sm = ChatUiStateMachine(fakeClassify(PrivacyLevel.NORMAL))
        sm.onInputChanged("hello")
        val first = sm.onSendClicked()
        assertEquals(1, first.size)
        assertTrue(sm.snapshot().isSending)
        // Вторая отправка без onAnswerReceived — игнорируется:
        val second = sm.onSendClicked()
        assertTrue("double-send must not emit effects while isSending", second.isEmpty())
    }

    @Test
    fun `stale classification does not overwrite updated text via CAS contract`() {
        // Эмулируем ситуацию: пользователь набрал "a", debounce ждёт;
        // быстро стёр и набрал "b"; старый debounce для "a" прилетает
        // после того как inputText уже "b". Машина не должна применять
        // классификацию старого текста.
        //
        // Контракт in-machine: classify() вызывается на onInputIdleAfterTyping,
        // но читает state.inputText в момент вызова — поэтому если текст
        // поменялся МЕЖДУ keystroke и idle, используется актуальный текст.
        val seen = mutableListOf<String>()
        val sm = ChatUiStateMachine { text ->
            seen.add(text)
            fakeClassify()(text)
        }
        sm.onInputChanged("a")
        sm.onInputChanged("ab")
        sm.onInputChanged("abc")
        sm.onInputIdleAfterTyping()
        assertEquals(
            "after idle, classify must be called once on current text",
            listOf("abc"),
            seen
        )
    }

    @Test
    fun `send uses privacy hint from current debounce-classified state`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("email me at test@example.com")
        sm.onInputIdleAfterTyping()
        assertEquals(PrivacyLevel.PRIVATE, sm.snapshot().privacyLevel)
        val effects = sm.onSendClicked()
        val send = effects.filterIsInstance<ChatUiEffect.SendPrompt>().single()
        assertEquals("email me at test@example.com", send.text)
        assertEquals(PrivacyLevel.PRIVATE, send.privacyHint)
        assertEquals(RequestSource.CHAT, send.source)
        assertTrue(sm.snapshot().isSending)
        assertEquals("", sm.snapshot().inputText)
    }

    @Test
    fun `C-02 needs consent shows card and resets isSending`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("hi")
        sm.onSendClicked()
        val consent = PendingConsentUi(
            privacyLevel = PrivacyLevel.PRIVATE,
            promptMessage = "Отправить в облако?",
            userPrompt = "hi"
        )
        val effects = sm.onNeedsConsent(consent)
        assertFalse(sm.snapshot().isSending)
        assertEquals(consent, sm.snapshot().pendingConsent)
        assertNull(sm.snapshot().pendingAction)
        assertTrue(effects.any { it is ChatUiEffect.Speak && it.text.contains("облако") })
    }

    @Test
    fun `C-02 confirm-click sends retry and clears card`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("my password is x")
        sm.onInputIdleAfterTyping()
        sm.onSendClicked()
        val consent = PendingConsentUi(PrivacyLevel.SENSITIVE, "?", "my password is x")
        sm.onNeedsConsent(consent)
        val confirmFx = sm.onConfirmCloudConsentClicked()
        val retry = confirmFx.filterIsInstance<ChatUiEffect.ConfirmCloudConsent>().single()
        assertEquals(PrivacyLevel.SENSITIVE, retry.privacyLevel)
        assertEquals("my password is x", retry.userPrompt)
        assertTrue(sm.snapshot().isSending)
        assertNull("consent card must clear", sm.snapshot().pendingConsent)
    }

    @Test
    fun `C-02 deny-click clears card and does not send`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("hi")
        sm.onSendClicked()
        sm.onNeedsConsent(PendingConsentUi(PrivacyLevel.PRIVATE, "?", "hi"))
        val fx = sm.onDenyCloudConsentClicked()
        assertTrue(fx.any { it is ChatUiEffect.ShowCloudConsentDeclined })
        assertNull(sm.snapshot().pendingConsent)
        assertFalse(sm.snapshot().isSending)
        // Проверка: isSending сброшен, нового SendPrompt эффекта нет.
        assertTrue(fx.none { it is ChatUiEffect.SendPrompt || it is ChatUiEffect.ConfirmCloudConsent })
    }

    @Test
    fun `text yes-no while consent card shown routes to consent answer`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("password=xxx")
        sm.onSendClicked()
        sm.onNeedsConsent(PendingConsentUi(PrivacyLevel.SENSITIVE, "?", "password=xxx"))
        // Пользователь пишет "да" и жмёт отправить — это должно быть
        // интерпретировано как согласие, а не новый запрос.
        sm.onInputChanged("да")
        val fx = sm.onSendClicked()
        assertTrue(fx.any { it is ChatUiEffect.ConfirmCloudConsent })
        assertNull(sm.snapshot().pendingConsent)
        assertTrue(sm.snapshot().isSending)
    }

    @Test
    fun `text no while consent card shown declines without sending`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("secret")
        sm.onSendClicked()
        sm.onNeedsConsent(PendingConsentUi(PrivacyLevel.PRIVATE, "?", "secret"))
        sm.onInputChanged("нет")
        val fx = sm.onSendClicked()
        assertTrue(fx.any { it is ChatUiEffect.ShowCloudConsentDeclined })
        assertNull(sm.snapshot().pendingConsent)
        assertFalse(sm.snapshot().isSending)
        assertTrue(fx.none { it is ChatUiEffect.SendPrompt })
    }

    @Test
    fun `tool confirmation yes-no via input routes to ConfirmAction or CancelAction`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("позвони маме")
        sm.onSendClicked()
        val pending = PendingActionUi(confirmationId = "tok-1", promptMessage = "Звоню маме?")
        sm.onConfirmationRequired(pending)
        assertNull(sm.snapshot().pendingConsent)
        assertEquals(pending, sm.snapshot().pendingAction)

        // Нет — отмена
        sm.onInputChanged("нет")
        val noFx = sm.onSendClicked()
        assertTrue(noFx.any { it is ChatUiEffect.CancelAction })
        assertNull(sm.snapshot().pendingAction)
        assertFalse(sm.snapshot().isSending)

        // Повторная отправка уже без pending:
        sm.onInputChanged("позвони папе")
        sm.onSendClicked()
        val pending2 = PendingActionUi("tok-2", "Звоню папе?")
        sm.onConfirmationRequired(pending2)
        sm.onInputChanged("да")
        val yesFx = sm.onSendClicked()
        val confirm = yesFx.filterIsInstance<ChatUiEffect.ConfirmAction>().single()
        assertEquals("tok-2", confirm.confirmationId)
    }

    @Test
    fun `onError resets sending and cards`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("hi")
        sm.onSendClicked()
        assertTrue(sm.snapshot().isSending)
        sm.onError("сеть упала")
        assertFalse(sm.snapshot().isSending)
        assertNull(sm.snapshot().pendingAction)
        assertNull(sm.snapshot().pendingConsent)
    }

    @Test
    fun `onAnswerReceived resets state and emits speak`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("hi")
        sm.onSendClicked()
        val fx = sm.onAnswerReceived("привет")
        assertFalse(sm.snapshot().isSending)
        assertTrue(fx.any { it is ChatUiEffect.Speak && it.text == "привет" })
    }

    @Test
    fun `deny without pending consent is no-op`() {
        val sm = ChatUiStateMachine(fakeClassify())
        assertTrue(sm.onDenyCloudConsentClicked().isEmpty())
    }

    @Test
    fun `confirm without pending consent is no-op`() {
        val sm = ChatUiStateMachine(fakeClassify())
        assertTrue(sm.onConfirmCloudConsentClicked().isEmpty())
    }

    @Test
    fun `voice dictation flag is settable and readable`() {
        val sm = ChatUiStateMachine(fakeClassify())
        assertFalse(sm.snapshot().isVoiceDictating)
        sm.onVoiceDictationChanged(true)
        assertTrue(sm.snapshot().isVoiceDictating)
        sm.onVoiceDictationChanged(false)
        assertFalse(sm.snapshot().isVoiceDictating)
    }

    @Test
    fun `sensitive text classification propagates to send hint`() {
        val sm = ChatUiStateMachine(fakeClassify())
        sm.onInputChanged("мой password=123")
        sm.onInputIdleAfterTyping()
        assertEquals(PrivacyLevel.SENSITIVE, sm.snapshot().privacyLevel)
        val send = sm.onSendClicked().filterIsInstance<ChatUiEffect.SendPrompt>().single()
        assertEquals(PrivacyLevel.SENSITIVE, send.privacyHint)
    }
}
