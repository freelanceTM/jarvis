package com.jarvis.assistant.voice.orchestrator.engine

/**
 * AR-07 / Phase 7: чистая state-machine голосового оркестратора.
 *
 * Моделирует диаграмму состояний [OrchestratorMode], не ссылается на Android,
 * корутины, TTS/STT движки или зависимости. Получает на вход [OrchestratorEvent]
 * и возвращает список [OrchestratorCommand] для исполнения адаптером.
 *
 * Задача — вынести правила переходов и их edge-cases в тестируемый на JVM слой,
 * чтобы regression-тесты ловили гонки/повторные события/late results, не требуя
 * Robolectric и не завися от timing.
 */
class OrchestratorEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val silenceTimeoutMs: Long = 3_500L,
    private val followUpWindowMs: Long = 6_000L,
    private val confirmationTimeoutMs: Long = 10_000L
) {

    data class Snapshot(
        val mode: OrchMode,
        val pendingToolCallId: String? = null,
        val hasQueuedCommands: Boolean = false,
        val lastError: String? = null
    )

    private var mode: OrchMode = OrchMode.STANDBY
    private var pendingCallId: String? = null
    private var lastEventAt: Long = clock()

    fun snapshot(): Snapshot = Snapshot(mode = mode, pendingToolCallId = pendingCallId)

    /**
     * Подаёт событие в движок. Идемпотентно: дублирующиеся и опоздавшие события
     * не должны переводить машину в некорректное состояние — вместо этого
     * они возвращают пустой список команд (считаются no-op).
     */
    fun onEvent(event: OrchestratorEvent): List<OrchCommand> {
        lastEventAt = clock()
        return when (event) {
            is OrchEvent.Reset -> goTo(OrchMode.STANDBY, dropPending = true) + OrchCmd.StopAll

            is OrchEvent.ServiceStart -> when (mode) {
                OrchMode.STANDBY -> goTo(OrchMode.STANDBY)
                else -> alreadyIn(event)
            }

            is OrchEvent.ServiceStop -> goTo(OrchMode.PAUSED, dropPending = true) + OrchCmd.StopAll

            // ---------- Wake word ----------
            is OrchEvent.WakeWordDetected -> when (mode) {
                OrchMode.STANDBY -> goTo(OrchMode.VERIFYING_KEYWORD) + OrchCmd.StartKeywordVerification
                OrchMode.AWAITING_CONFIRMATION -> alreadyIn(event)
                OrchMode.LIVE_INTERPRETER -> alreadyIn(event)
                else -> alreadyIn(event)
            }

            is OrchEvent.KeywordVerified -> when (mode) {
                OrchMode.VERIFYING_KEYWORD ->
                    goTo(OrchMode.LISTENING) +
                        OrchCmd.StopWakeWordDetection +
                        OrchCmd.StartStt(startSilenceTimeout = silenceTimeoutMs)
                else -> alreadyIn(event)
            }

            is OrchEvent.KeywordRejected -> when (mode) {
                OrchMode.VERIFYING_KEYWORD -> goTo(OrchMode.STANDBY)
                else -> alreadyIn(event)
            }

            // ---------- STT ----------
            is OrchEvent.SttStarted -> when (mode) {
                OrchMode.LISTENING -> alreadyOk(event)
                OrchMode.CONTINUOUS_CONVERSATION -> alreadyOk(event)
                OrchMode.LIVE_INTERPRETER -> alreadyOk(event)
                else -> alreadyIn(event)
            }

            is OrchEvent.SttPartial -> when (mode) {
                OrchMode.LISTENING,
                OrchMode.CONTINUOUS_CONVERSATION,
                OrchMode.LIVE_INTERPRETER -> {
                    // Частичный результат не меняет режим, только сигнализирует
                    // UI подсветкой.
                    listOf(OrchCmd.UpdatePartialText(event.text))
                }
                else -> alreadyIn(event)
            }

            is OrchEvent.SttFinal -> when (mode) {
                OrchMode.LISTENING,
                OrchMode.CONTINUOUS_CONVERSATION -> {
                    val text = event.text.trim()
                    when {
                        text.isEmpty() -> goTo(OrchMode.STANDBY) +
                            OrchCmd.StopStt +
                            OrchCmd.SilenceFeedback
                        else -> goTo(OrchMode.AI_THINKING) +
                            OrchCmd.StopStt +
                            OrchCmd.CancelSilenceTimer +
                            OrchCmd.ExecuteAi(text)
                    }
                }
                // Если мы ждём подтверждения, а STT прислал финальный текст —
                // это ответ пользователя на подтверждение, обрабатывается
                // отдельной веткой (не AI-execute).
                OrchMode.AWAITING_CONFIRMATION -> handleConfirmationResponse(event.text.trim())
                OrchMode.LIVE_INTERPRETER -> listOf(OrchCmd.TranslatePartial(event.text))
                else -> alreadyIn(event) // late STT result after cancellation
            }

            is OrchEvent.SttError -> when (mode) {
                OrchMode.LISTENING,
                OrchMode.CONTINUOUS_CONVERSATION ->
                    goTo(OrchMode.STANDBY) +
                        OrchCmd.StopStt +
                        OrchCmd.NotifyError(event.reason)
                else -> alreadyIn(event)
            }

            is OrchEvent.SilenceTimeout -> when (mode) {
                OrchMode.LISTENING ->
                    goTo(OrchMode.STANDBY) +
                        OrchCmd.StopStt +
                        OrchCmd.SilenceFeedback
                else -> alreadyIn(event)
            }

            // ---------- AI ----------
            is OrchEvent.AiStarted -> when (mode) {
                OrchMode.AI_THINKING -> alreadyOk(event)
                else -> alreadyIn(event)
            }

            is OrchEvent.AiDirectAnswer -> when (mode) {
                OrchMode.AI_THINKING, OrchMode.AWAITING_CONFIRMATION ->
                    goTo(OrchMode.TTS_SPEAKING) +
                        OrchCmd.StartTts(event.text)
                else -> alreadyIn(event)
            }

            is OrchEvent.AiConfirmationRequired -> when (mode) {
                OrchMode.AI_THINKING -> {
                    pendingCallId = event.callId
                    goTo(OrchMode.AWAITING_CONFIRMATION) +
                        OrchCmd.StartTts(event.prompt) +
                        OrchCmd.StartConfirmationTimer(confirmationTimeoutMs)
                }
                else -> alreadyIn(event)
            }

            is OrchEvent.AiError -> when (mode) {
                OrchMode.AI_THINKING ->
                    goTo(OrchMode.TTS_SPEAKING) +
                        OrchCmd.StartTts(event.userMessage)
                else -> alreadyIn(event)
            }

            // ---------- TTS ----------
            is OrchEvent.TtsStarted -> when (mode) {
                OrchMode.TTS_SPEAKING, OrchMode.AWAITING_CONFIRMATION -> alreadyOk(event)
                else -> alreadyIn(event)
            }

            is OrchEvent.TtsCompleted -> when (mode) {
                OrchMode.TTS_SPEAKING ->
                    // После ответа держим короткое окно follow-up (пользователь
                    // может продолжить без повторного «Джарвис»).
                    goTo(OrchMode.CONTINUOUS_CONVERSATION) +
                        OrchCmd.StartFollowUpWindow(followUpWindowMs) +
                        OrchCmd.StartStt(startSilenceTimeout = silenceTimeoutMs)

                OrchMode.AWAITING_CONFIRMATION -> {
                    // TTS прогрел prompt подтверждения; режим остаётся
                    // AWAITING_CONFIRMATION до ответа пользователя / таймаута.
                    alreadyOk(event)
                }
                else -> alreadyIn(event)
            }

            is OrchEvent.TtsError -> when (mode) {
                OrchMode.TTS_SPEAKING -> goTo(OrchMode.STANDBY) + OrchCmd.NotifyError("tts_error")
                OrchMode.AWAITING_CONFIRMATION -> goTo(OrchMode.STANDBY) + OrchCmd.NotifyError("tts_error")
                else -> alreadyIn(event)
            }

            // ---------- Confirmation ----------
            is OrchEvent.ConfirmationYes -> when (mode) {
                OrchMode.AWAITING_CONFIRMATION -> {
                    val callId = pendingCallId
                    pendingCallId = null
                    goTo(OrchMode.AI_THINKING) +
                        OrchCmd.CancelConfirmationTimer +
                        OrchCmd.ExecuteConfirmedTool(callId)
                }
                else -> alreadyIn(event)
            }

            is OrchEvent.ConfirmationNo -> when (mode) {
                OrchMode.AWAITING_CONFIRMATION -> {
                    pendingCallId = null
                    goTo(OrchMode.TTS_SPEAKING) +
                        OrchCmd.CancelConfirmationTimer +
                        OrchCmd.StartTts("Операция отменена, сэр.")
                }
                else -> alreadyIn(event)
            }

            is OrchEvent.ConfirmationTimeout -> when (mode) {
                OrchMode.AWAITING_CONFIRMATION -> {
                    pendingCallId = null
                    goTo(OrchMode.TTS_SPEAKING) +
                        OrchCmd.StartTts("Запрос подтверждения истёк, сэр.")
                }
                else -> alreadyIn(event)
            }

            // ---------- Continuous conversation ----------
            is OrchEvent.FollowUpWindowExpired -> when (mode) {
                OrchMode.CONTINUOUS_CONVERSATION ->
                    goTo(OrchMode.STANDBY) +
                        OrchCmd.StopStt +
                        OrchCmd.StartWakeWordDetection
                else -> alreadyIn(event)
            }

            // ---------- Live interpreter ----------
            is OrchEvent.LiveInterpreterStart -> when (mode) {
                OrchMode.STANDBY, OrchMode.PAUSED ->
                    goTo(OrchMode.LIVE_INTERPRETER) +
                        OrchCmd.StopWakeWordDetection +
                        OrchCmd.StartStt(startSilenceTimeout = 0)
                else -> alreadyIn(event)
            }

            is OrchEvent.LiveInterpreterStop -> when (mode) {
                OrchMode.LIVE_INTERPRETER -> goTo(OrchMode.STANDBY) + OrchCmd.StopAll
                else -> alreadyIn(event)
            }

            // ---------- Phone call / sleep ----------
            is OrchEvent.PhoneCallPause -> goTo(OrchMode.PAUSED, dropPending = true) + OrchCmd.StopAll

            is OrchEvent.PhoneCallResume -> when (mode) {
                OrchMode.PAUSED -> goTo(OrchMode.STANDBY)
                else -> alreadyIn(event)
            }

            is OrchEvent.UserInterruption ->
                // Любая фаза прерывается и возвращает в LISTENING (новая попытка).
                goTo(OrchMode.LISTENING, dropPending = true) +
                    OrchCmd.StopAll +
                    OrchCmd.StartStt(startSilenceTimeout = silenceTimeoutMs)
        }
    }

    private fun goTo(
        newMode: OrchMode,
        dropPending: Boolean = false
    ): List<OrchCommand> {
        mode = newMode
        if (dropPending) pendingCallId = null
        return emptyList()
    }

    /**
     * Событие пришло в корректном состоянии (например, SttStarted после StartStt) —
     * это не ошибка и не требует команд, но фиксируется для observability.
     */
    private fun alreadyOk(@Suppress("UNUSED_PARAMETER") event: OrchEvent): List<OrchCommand> = emptyList()

    /**
     * Late/duplicate/invalid event в данном режиме. Не должно ничего ломать.
     */
    private fun alreadyIn(event: OrchEvent): List<OrchCommand> {
        // No commands, no crash, no mode change.
        return emptyList()
    }

    private fun handleConfirmationResponse(text: String): List<OrchCommand> {
        return when {
            equalsYes(text) -> onEvent(OrchEvent.ConfirmationYes)
            equalsNo(text) -> onEvent(OrchEvent.ConfirmationNo)
            else ->
                // Не «да/нет» — считаем, что пользователь задал новый запрос.
                goTo(OrchMode.AI_THINKING) +
                    OrchCmd.StopStt +
                    OrchCmd.CancelConfirmationTimer +
                    OrchCmd.ExecuteAi(text)
        }
    }

    private fun equalsYes(text: String): Boolean =
        text.trim().lowercase() in setOf("да", "yes", "ага", "подтверждаю", "ок", "давай", "хорошо")

    private fun equalsNo(text: String): Boolean =
        text.trim().lowercase() in setOf("нет", "no", "не надо", "отмена", "отменить", "не нужно", "стоп")
}

/**
 * Упрощённая диаграмма режимов оркестратора (не зависит от Android).
 * Имена совпадают с боевым [OrchestratorMode], чтобы упростить адаптер.
 */
enum class OrchMode {
    STANDBY,
    VERIFYING_KEYWORD,
    LISTENING,
    AI_THINKING,
    TTS_SPEAKING,
    AWAITING_CONFIRMATION,
    CONTINUOUS_CONVERSATION,
    LIVE_INTERPRETER,
    PAUSED
}

/** Входящее событие от адаптера (STT/TTS/AI/UI/System). */
sealed class OrchestratorEvent {
    data object Reset : OrchestratorEvent()
    data object ServiceStart : OrchestratorEvent()
    data object ServiceStop : OrchestratorEvent()

    data object WakeWordDetected : OrchestratorEvent()
    data object KeywordVerified : OrchestratorEvent()
    data object KeywordRejected : OrchestratorEvent()

    data object SttStarted : OrchestratorEvent()
    data class SttPartial(val text: String) : OrchestratorEvent()
    data class SttFinal(val text: String) : OrchestratorEvent()
    data class SttError(val reason: String) : OrchestratorEvent()
    data object SilenceTimeout : OrchestratorEvent()

    data object AiStarted : OrchestratorEvent()
    data class AiDirectAnswer(val text: String) : OrchestratorEvent()
    data class AiConfirmationRequired(val callId: String, val prompt: String) : OrchestratorEvent()
    data class AiError(val userMessage: String) : OrchestratorEvent()

    data object TtsStarted : OrchestratorEvent()
    data object TtsCompleted : OrchestratorEvent()
    data object TtsError : OrchestratorEvent()

    data object ConfirmationYes : OrchestratorEvent()
    data object ConfirmationNo : OrchestratorEvent()
    data object ConfirmationTimeout : OrchestratorEvent()

    data object FollowUpWindowExpired : OrchestratorEvent()

    data object LiveInterpreterStart : OrchestratorEvent()
    data object LiveInterpreterStop : OrchestratorEvent()

    data object PhoneCallPause : OrchestratorEvent()
    data object PhoneCallResume : OrchestratorEvent()

    /** Пользователь перебил (тап по экрану / «Джарвис» посреди ответа). */
    data object UserInterruption : OrchestratorEvent()
}

/** Команда адаптеру (реальный VoiceInteractionOrchestrator исполняет их на Android). */
sealed class OrchCommand {
    data object StartWakeWordDetection : OrchCommand()
    data object StopWakeWordDetection : OrchCommand()
    data object StartKeywordVerification : OrchCommand()
    data class StartStt(val startSilenceTimeout: Long) : OrchCommand()
    data object StopStt : OrchCommand()
    data class StartTts(val text: String) : OrchCommand()
    data object StopTts : OrchCommand()
    data object StopAll : OrchCommand()
    data class ExecuteAi(val query: String) : OrchCommand()
    data class ExecuteConfirmedTool(val callId: String?) : OrchCommand()
    data class StartConfirmationTimer(val timeoutMs: Long) : OrchCommand()
    data object CancelConfirmationTimer : OrchCommand()
    data class StartFollowUpWindow(val windowMs: Long) : OrchCommand()
    data object CancelSilenceTimer : OrchCommand()
    data class UpdatePartialText(val text: String) : OrchCommand()
    data class TranslatePartial(val text: String) : OrchCommand()
    data class NotifyError(val reason: String) : OrchCommand()
    data object SilenceFeedback : OrchCommand()
}

