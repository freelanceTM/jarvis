package com.jarvis.assistant.voice.orchestrator.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 / AR-07: table-driven unit tests для [OrchestratorEngine].
 *
 * Проверяют, что:
 *  - основ happy-path (IDLE → LISTENING → AI → TTS → CONTINUOUS → IDLE);
 *  - некорректные переходы (дубликаты / late events / events в неправильном
 *    состоянии) не ломают state machine;
 *  - CR-04/N-02: подтверждение привязано к pendingCallId, расы не уводят
 *    машину в некорректное состояние;
 *  - таймауты и cancel корректно возвращают в STANDBY/говорят пользователю;
 *  - interruption в любой момент останавливает всё и возвращает в LISTENING.
 *
 * Все тесты — чистые JVM тесты без Android/Robolectric и без sleep.
 */
class OrchestratorEngineTest {

    // Маленький DSL для читаемых table-driven сценариев.
    private data class Case(
        val name: String,
        val steps: List<OrchestratorEvent>,
        val expectedMode: OrchMode,
        val expectedLastCommand: (() -> Boolean)? = null,
        val expectedPendingCall: String? = null,
        val expectedCommandsContain: List<Class<out OrchCommand>>? = null,
        val expectedCommandsDoNotContain: List<Class<out OrchCommand>>? = null
    )

    private fun runCase(case: Case) {
        val engine = OrchestratorEngine(clock = { 1L })
        lateinit var last: List<OrchCommand>
        case.steps.forEach { ev -> last = engine.onEvent(ev) }
        assertEquals("Case '${case.name}': mode", case.expectedMode, engine.snapshot().mode)
        assertEquals("Case '${case.name}': pendingCall", case.expectedPendingCall, engine.snapshot().pendingToolCallId)
        case.expectedCommandsContain?.forEach { cls ->
            assertTrue(
                "Case '${case.name}': expected command ${cls.simpleName}",
                last.any { cls.isInstance(it) }
            )
        }
        case.expectedCommandsDoNotContain?.forEach { cls ->
            assertFalse(
                "Case '${case.name}': unexpected command ${cls.simpleName}",
                last.any { cls.isInstance(it) }
            )
        }
        case.expectedLastCommand?.let { predicate ->
            assertTrue("Case '${case.name}': last-command predicate failed", predicate())
        }
    }

    @Test
    fun `table driven scenarios`() {
        val cases = listOf(
            // ------------------------------------------------ STT happy path
            Case(
                name = "wake → verify → listen starts STT",
                steps = listOf(
                    OrchEvent.ServiceStart,
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified
                ),
                expectedMode = OrchMode.LISTENING,
                expectedCommandsContain = listOf(
                    OrchCmd.StartStt::class.java,
                    OrchCmd.StopWakeWordDetection::class.java
                ),
                expectedCommandsDoNotContain = listOf(OrchCmd.ExecuteAi::class.java)
            ),
            Case(
                name = "keyword rejected returns to standby",
                steps = listOf(OrchEvent.WakeWordDetected, OrchEvent.KeywordRejected),
                expectedMode = OrchMode.STANDBY
            ),
            Case(
                name = "STT final empty returns to standby + silence feedback",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("   ")
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(
                    OrchCmd.StopStt::class.java,
                    OrchCmd.SilenceFeedback::class.java
                )
            ),
            Case(
                name = "STT final valid text triggers AI execution",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("включи фонарик")
                ),
                expectedMode = OrchMode.AI_THINKING,
                expectedCommandsContain = listOf(
                    OrchCmd.ExecuteAi::class.java,
                    OrchCmd.StopStt::class.java
                )
            ),
            Case(
                name = "STT error during listening returns to standby",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttError("recognizer_busy")
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(
                    OrchCmd.NotifyError::class.java,
                    OrchCmd.StopStt::class.java
                )
            ),
            Case(
                name = "silence timeout returns to standby with feedback",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SilenceTimeout
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(OrchCmd.SilenceFeedback::class.java, OrchCmd.StopStt::class.java)
            ),
            Case(
                name = "duplicate SttStarted is a no-op not a transition",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttStarted,
                    OrchEvent.SttStarted
                ),
                expectedMode = OrchMode.LISTENING
            ),
            Case(
                name = "STT started in wrong mode is ignored (no crash)",
                steps = listOf(OrchEvent.SttStarted, OrchEvent.SttFinal("text in standby")),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsDoNotContain = listOf(OrchCmd.ExecuteAi::class.java)
            ),

            // ------------------------------------------------ AI/TTS happy path
            Case(
                name = "AI direct answer moves to TTS and speaks",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("сколько времени"),
                    OrchEvent.AiDirectAnswer("сейчас 15:42")
                ),
                expectedMode = OrchMode.TTS_SPEAKING,
                expectedCommandsContain = listOf(OrchCmd.StartTts::class.java)
            ),
            Case(
                name = "TTS completed opens continuous conversation window",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("привет"),
                    OrchEvent.AiDirectAnswer("привет сэр"),
                    OrchEvent.TtsStarted,
                    OrchEvent.TtsCompleted
                ),
                expectedMode = OrchMode.CONTINUOUS_CONVERSATION,
                expectedCommandsContain = listOf(OrchCmd.StartStt::class.java)
            ),
            Case(
                name = "follow-up expiry returns to STANDBY + wake detection",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("привет"),
                    OrchEvent.AiDirectAnswer("привет сэр"),
                    OrchEvent.TtsCompleted,
                    OrchEvent.FollowUpWindowExpired
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(
                    OrchCmd.StopStt::class.java,
                    OrchCmd.StartWakeWordDetection::class.java
                )
            ),
            Case(
                name = "TTS error returns to standby without crash",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("привет"),
                    OrchEvent.AiDirectAnswer("привет"),
                    OrchEvent.TtsError
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(OrchCmd.NotifyError::class.java)
            ),

            // ------------------------------------------------ Confirmation (CR-04/N-02)
            Case(
                name = "AI requires confirmation → AWAITING_CONFIRMATION, callId stored",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони маме"),
                    OrchEvent.AiConfirmationRequired(callId = "call-1", prompt = "Вы подтверждаете звонок?")
                ),
                expectedMode = OrchMode.AWAITING_CONFIRMATION,
                expectedPendingCall = "call-1",
                expectedCommandsContain = listOf(
                    OrchCmd.StartTts::class.java,
                    OrchCmd.StartConfirmationTimer::class.java
                )
            ),
            Case(
                name = "Confirmation yes triggers ExecuteConfirmedTool(callId)",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони маме"),
                    OrchEvent.AiConfirmationRequired(callId = "call-2", prompt = "Подтвердите?"),
                    OrchEvent.ConfirmationYes
                ),
                expectedMode = OrchMode.AI_THINKING,
                expectedPendingCall = null,
                expectedCommandsContain = listOf(OrchCmd.ExecuteConfirmedTool::class.java, OrchCmd.CancelConfirmationTimer::class.java),
                expectedLastCommand = { true }
            ),
            Case(
                name = "Confirmation no cancels and says so",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони"),
                    OrchEvent.AiConfirmationRequired(callId = "c3", prompt = "Подтвердите?"),
                    OrchEvent.ConfirmationNo
                ),
                expectedMode = OrchMode.TTS_SPEAKING,
                expectedPendingCall = null
            ),
            Case(
                name = "Confirmation timeout speaks expiry message",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони"),
                    OrchEvent.AiConfirmationRequired(callId = "c4", prompt = "?"),
                    OrchEvent.ConfirmationTimeout
                ),
                expectedMode = OrchMode.TTS_SPEAKING,
                expectedPendingCall = null
            ),
            Case(
                name = "SttFinal 'да' while AWAITING_CONFIRMATION is treated as yes",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони"),
                    OrchEvent.AiConfirmationRequired(callId = "c5", prompt = "?"),
                    OrchEvent.TtsCompleted,
                    OrchEvent.SttFinal("да")
                ),
                expectedMode = OrchMode.AI_THINKING,
                expectedPendingCall = null,
                expectedCommandsContain = listOf(OrchCmd.ExecuteConfirmedTool::class.java)
            ),
            Case(
                name = "SttFinal 'нет' while AWAITING_CONFIRMATION cancels",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони"),
                    OrchEvent.AiConfirmationRequired(callId = "c6", prompt = "?"),
                    OrchEvent.TtsCompleted,
                    OrchEvent.SttFinal("нет")
                ),
                expectedMode = OrchMode.TTS_SPEAKING,
                expectedPendingCall = null
            ),
            Case(
                name = "late SttFinal after cancellation does nothing",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.Reset,
                    OrchEvent.SttFinal("very late")
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsDoNotContain = listOf(OrchCmd.ExecuteAi::class.java)
            ),

            // ------------------------------------------------ Interruption / phone
            Case(
                name = "User interruption mid-TTS returns to LISTENING",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("привет"),
                    OrchEvent.AiDirectAnswer("здравствуйте"),
                    OrchEvent.TtsStarted,
                    OrchEvent.UserInterruption
                ),
                expectedMode = OrchMode.LISTENING,
                expectedCommandsContain = listOf(OrchCmd.StopAll::class.java, OrchCmd.StartStt::class.java)
            ),
            Case(
                name = "Phone call pause drops pending and stops everything",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.SttFinal("позвони"),
                    OrchEvent.AiConfirmationRequired(callId = "c7", prompt = "?"),
                    OrchEvent.PhoneCallPause
                ),
                expectedMode = OrchMode.PAUSED,
                expectedPendingCall = null,
                expectedCommandsContain = listOf(OrchCmd.StopAll::class.java)
            ),
            Case(
                name = "Phone call resume returns to STANDBY",
                steps = listOf(OrchEvent.PhoneCallPause, OrchEvent.PhoneCallResume),
                expectedMode = OrchMode.STANDBY
            ),
            Case(
                name = "Service stop goes to PAUSED and stops all",
                steps = listOf(OrchEvent.ServiceStart, OrchEvent.WakeWordDetected, OrchEvent.ServiceStop),
                expectedMode = OrchMode.PAUSED
            ),

            // ------------------------------------------------ Live interpreter
            Case(
                name = "Live interpreter start transitions to LIVE_INTERPRETER",
                steps = listOf(OrchEvent.LiveInterpreterStart),
                expectedMode = OrchMode.LIVE_INTERPRETER,
                expectedCommandsContain = listOf(OrchCmd.StartStt::class.java, OrchCmd.StopWakeWordDetection::class.java)
            ),
            Case(
                name = "Live interpreter stop returns to STANDBY",
                steps = listOf(OrchEvent.LiveInterpreterStart, OrchEvent.LiveInterpreterStop),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(OrchCmd.StopAll::class.java)
            ),
            Case(
                name = "STT final in live interpreter translates rather than executes AI",
                steps = listOf(
                    OrchEvent.LiveInterpreterStart,
                    OrchEvent.SttFinal("hello")
                ),
                expectedMode = OrchMode.LIVE_INTERPRETER,
                expectedCommandsContain = listOf(OrchCmd.TranslatePartial::class.java),
                expectedCommandsDoNotContain = listOf(OrchCmd.ExecuteAi::class.java)
            ),

            // ------------------------------------------------ Reset
            Case(
                name = "Reset from any state goes to STANDBY",
                steps = listOf(
                    OrchEvent.WakeWordDetected,
                    OrchEvent.KeywordVerified,
                    OrchEvent.Reset
                ),
                expectedMode = OrchMode.STANDBY,
                expectedCommandsContain = listOf(OrchCmd.StopAll::class.java),
                expectedPendingCall = null
            )
        )

        cases.forEach(::runCase)
    }

    @Test
    fun `pending callId is cleared after confirmation yes`() {
        val engine = OrchestratorEngine(clock = { 1L })
        engine.onEvent(OrchEvent.WakeWordDetected)
        engine.onEvent(OrchEvent.KeywordVerified)
        engine.onEvent(OrchEvent.SttFinal("позвони"))
        engine.onEvent(OrchEvent.AiConfirmationRequired("call-x", "OK?"))
        assertEquals(OrchMode.AWAITING_CONFIRMATION, engine.snapshot().mode)
        assertEquals("call-x", engine.snapshot().pendingToolCallId)
        engine.onEvent(OrchEvent.ConfirmationYes)
        assertNull(engine.snapshot().pendingToolCallId)
    }

    @Test
    fun `wake word detection ignored while already listening or speaking`() {
        val engine = OrchestratorEngine(clock = { 1L })
        engine.onEvent(OrchEvent.WakeWordDetected)
        engine.onEvent(OrchEvent.KeywordVerified)
        // Ещё раз WakeWordDetected в LISTENING — не должен поменять режим
        val cmds = engine.onEvent(OrchEvent.WakeWordDetected)
        assertEquals(OrchMode.LISTENING, engine.snapshot().mode)
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun `late SttError after reset does not change mode`() {
        val engine = OrchestratorEngine(clock = { 1L })
        engine.onEvent(OrchEvent.WakeWordDetected)
        engine.onEvent(OrchEvent.KeywordVerified)
        engine.onEvent(OrchEvent.Reset)
        engine.onEvent(OrchEvent.SttError("late"))
        assertEquals(OrchMode.STANDBY, engine.snapshot().mode)
    }
}
