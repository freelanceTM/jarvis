package com.jarvis.assistant.presentation.chat

import com.jarvis.assistant.agent.decision.PrivacyClassification
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.PrivacyReason
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.core.confirmation.ConfirmationIntent

/**
 * P2-cleanup (Этап 6): pure-Kotlin UI state-machine для чата, вынесенная
 * из [ChatViewModel], чтобы покрыть сложные event-sequences (debounce,
 * rapid input, confirmation/privacy-consent races) JVM-тестами без
 * Robolectric и без Android-зависимостей.
 *
 * Задача: единственный источник истины для полей [ChatUiState], связанных
 * с отправкой сообщения, privacy-icon и ожидающими confirm/consent
 * карточками. Все suspend/IO операции (TTS, STT, sendPromptUseCase,
 * classifier) остаются в ViewModel — машина решает, КАКОЕ состояние
 * и какой Intent должны получиться в ответ на входящее событие при
 * текущем состоянии, без сайд-эффектов.
 *
 * Debounce эмулируется вызовом [onInputIdleAfterTyping] (ViewModel
 * вызывает его из collectLatest после CLASSIFY_DEBOUNCE_MS). Для тестов
 * это позволяет детерминированно шагать по времени без корутин.
 *
 * ВАЖНО: машина не знает про ToolCall и прочие тяжёлые доменные объекты.
 * Для подвешенных tool-confirmations она работает только с абстрактным
 * `confirmationId` (токеном), который ViewModel мапит в ToolCall сам.
 * Это сохраняет чистоту класса и не тянет домен в UI-логику.
 */
data class PendingConsentUi(
    val privacyLevel: PrivacyLevel,
    val promptMessage: String,
    val userPrompt: String
)

data class PendingActionUi(
    val confirmationId: String,
    val promptMessage: String
)

data class ChatUiSnapshot(
    val inputText: String = "",
    val isSending: Boolean = false,
    val isVoiceDictating: Boolean = false,
    val privacyClassification: PrivacyClassification =
        PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED),
    val pendingAction: PendingActionUi? = null,
    val pendingConsent: PendingConsentUi? = null
) {
    val privacyLevel: PrivacyLevel get() = privacyClassification.level
}

sealed class ChatUiEffect {
    /** Отправить запрос в SendPromptUseCase с текущими параметрами. */
    data class SendPrompt(
        val text: String,
        val privacyHint: PrivacyLevel,
        val source: RequestSource
    ) : ChatUiEffect()

    /** Отправить confirm для приватного запроса (cloudExplicitlyAllowed=true). */
    data class ConfirmCloudConsent(val userPrompt: String, val privacyLevel: PrivacyLevel) : ChatUiEffect()

    /** Показать отказ от облачной отправки. */
    data object ShowCloudConsentDeclined : ChatUiEffect()

    /** Подтвердить tool-action по идентификатору. */
    data class ConfirmAction(val confirmationId: String) : ChatUiEffect()

    /** Отменить pending tool-action. */
    data object CancelAction : ChatUiEffect()

    /** Озвучить текст (TTS). */
    data class Speak(val text: String) : ChatUiEffect()
}

/**
 * @param classify функция, имитирующая PrivacyClassifier.classifySafely.
 */
class ChatUiStateMachine(
    private val classify: (String) -> PrivacyClassification
) {
    private var state = ChatUiSnapshot()

    fun snapshot(): ChatUiSnapshot = state

    /**
     * Пользователь изменил текст в поле ввода.
     *
     * Не запускаем classifier — только обновляем inputText. Переклассификация
     * произойдёт в [onInputIdleAfterTyping] после debounce.
     */
    fun onInputChanged(newText: String): List<ChatUiEffect> {
        val prev = state
        if (newText == prev.inputText) return emptyList()
        state = prev.copy(
            inputText = newText,
            privacyClassification = if (newText.isEmpty()) {
                PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED)
            } else {
                prev.privacyClassification
            }
        )
        return emptyList()
    }

    /**
     * Сработал debounce после последнего keystroke — классифицируем текст,
     * если он не изменился между классификацией и применением (CAS против
     * stale результата).
     */
    fun onInputIdleAfterTyping(): List<ChatUiEffect> {
        val text = state.inputText
        if (text.isEmpty()) return emptyList()
        val classification = classify(text)
        state = state.copy(privacyClassification = classification)
        return emptyList()
    }

    /**
     * Пользователь нажал отправить / пришёл FinalResult от STT.
     * Не классифицирует — использует privacyHint из snapshot.
     */
    fun onSendClicked(): List<ChatUiEffect> {
        val query = state.inputText.trim()
        if (query.isBlank() || state.isSending) return emptyList()

        // C-02: если висит cloud-consent вопрос — «да/нет» это ответ на него.
        val consent = state.pendingConsent
        if (consent != null && ConfirmationIntent.isDefinitive(query)) {
            state = state.copy(inputText = "")
            return if (ConfirmationIntent.isYes(query)) {
                state = state.copy(pendingConsent = null, isSending = true)
                listOf(ChatUiEffect.ConfirmCloudConsent(consent.userPrompt, consent.privacyLevel))
            } else {
                state = state.copy(pendingConsent = null)
                listOf(ChatUiEffect.ShowCloudConsentDeclined)
            }
        }

        // Если ждём подтверждения действия — «да/нет» ответ на это.
        val action = state.pendingAction
        if (action != null && ConfirmationIntent.isDefinitive(query)) {
            state = state.copy(inputText = "")
            return if (ConfirmationIntent.isYes(query)) {
                state = state.copy(pendingAction = null, isSending = true)
                listOf(ChatUiEffect.ConfirmAction(action.confirmationId))
            } else {
                state = state.copy(pendingAction = null)
                listOf(ChatUiEffect.CancelAction)
            }
        }

        val hint = state.privacyClassification.level
        state = state.copy(
            inputText = "",
            isSending = true,
            pendingConsent = null
        )
        return listOf(
            ChatUiEffect.SendPrompt(
                text = query,
                privacyHint = hint,
                source = RequestSource.CHAT
            )
        )
    }

    /** SendPromptUseCase ответил NeedsConsent — показываем карточку. */
    fun onNeedsConsent(consent: PendingConsentUi): List<ChatUiEffect> {
        state = state.copy(isSending = false, pendingConsent = consent)
        return listOf(ChatUiEffect.Speak(consent.promptMessage))
    }

    /** SendPromptUseCase попросил подтвердить действие. */
    fun onConfirmationRequired(action: PendingActionUi): List<ChatUiEffect> {
        state = state.copy(isSending = false, pendingAction = action)
        return listOf(ChatUiEffect.Speak(action.promptMessage))
    }

    /** SendPromptUseCase вернул прямой ответ. */
    fun onAnswerReceived(answer: String): List<ChatUiEffect> {
        state = state.copy(isSending = false, pendingAction = null, pendingConsent = null)
        return listOf(ChatUiEffect.Speak(answer))
    }

    /** SendPromptUseCase/ToolExecutor упали с ошибкой. */
    fun onError(message: String): List<ChatUiEffect> {
        state = state.copy(isSending = false, pendingAction = null, pendingConsent = null)
        return listOf(ChatUiEffect.Speak("Ошибка: $message"))
    }

    /** Пользователь нажал «Отправить в облако» на карточке. */
    fun onConfirmCloudConsentClicked(): List<ChatUiEffect> {
        val consent = state.pendingConsent ?: return emptyList()
        state = state.copy(pendingConsent = null, isSending = true)
        return listOf(ChatUiEffect.ConfirmCloudConsent(consent.userPrompt, consent.privacyLevel))
    }

    /** Пользователь нажал «Только локально». */
    fun onDenyCloudConsentClicked(): List<ChatUiEffect> {
        if (state.pendingConsent == null) return emptyList()
        state = state.copy(pendingConsent = null)
        return listOf(ChatUiEffect.ShowCloudConsentDeclined)
    }

    /** ToolExecutor подтверждён и вернул ответ. */
    fun onActionCompleted(message: String): List<ChatUiEffect> = onAnswerReceived(message)

    /** Voice dictation start/stop — для будущих STT тестов. */
    fun onVoiceDictationChanged(active: Boolean): List<ChatUiEffect> {
        state = state.copy(isVoiceDictating = active)
        return emptyList()
    }
}
