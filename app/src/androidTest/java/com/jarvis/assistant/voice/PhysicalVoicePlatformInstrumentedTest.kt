package com.jarvis.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import com.jarvis.assistant.voice.tts.TtsState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhysicalVoiceTestEntryPoint {
    fun bluetoothAudioRouter(): BluetoothAudioRouter
    fun speechRecognizerManager(): SpeechRecognizerManager
    fun textToSpeechManager(): TextToSpeechManager
}

/** Manual physical-hardware tests, gated so ordinary connected jobs never fake evidence. */
@RunWith(AndroidJUnit4::class)
class PhysicalVoicePlatformInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val entryPoint get() = EntryPointAccessors.fromApplication(
        context.applicationContext,
        PhysicalVoiceTestEntryPoint::class.java
    )

    @Test
    fun connectedPhysicalHeadsetBecomesTheCommunicationDeviceAndCleansUp() {
        assumeTrue("requires requireBluetoothHeadset=true", args.getString("requireBluetoothHeadset") == "true")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(
                "BLUETOOTH_CONNECT must be granted before the physical test",
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        val router = entryPoint.bluetoothAudioRouter()
        assertTrue("no compatible physical headset detected", router.checkHeadsetConnection())
        val audio = context.getSystemService(AudioManager::class.java)
        try {
            router.routeAudioToEarbud()
            assertTrue("headset routing did not become active", eventually(10_000) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audio.communicationDevice?.type in setOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET
                    )
                } else {
                    @Suppress("DEPRECATION")
                    audio.isBluetoothScoOn
                }
            })
        } finally {
            router.routeAudioToSpeaker()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(eventually(5_000) { audio.communicationDevice == null })
        }
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test
    fun realTtsCompletesAndShutsDown() {
        assumeTrue("requires requireVoicePlatform=true", args.getString("requireVoicePlatform") == "true")
        val tts = entryPoint.textToSpeechManager()
        assertTrue("TTS engine did not initialize", eventually(15_000) {
            tts.ttsState.value == TtsState.Ready || tts.ttsState.value == TtsState.Error
        })
        assertTrue("TTS engine initialization failed", tts.isInitialized.value)
        tts.speak("Jarvis staging voice test")
        assertTrue("TTS utterance did not complete", eventually(20_000) {
            tts.ttsState.value == TtsState.Done || tts.ttsState.value == TtsState.Error
        })
        assertEquals(TtsState.Done, tts.ttsState.value)
        tts.shutdown()
        assertEquals(TtsState.Idle, tts.ttsState.value)
        assertTrue(!tts.isInitialized.value)
        tts.restart()
        assertTrue("TTS restart did not recover", eventually(15_000) { tts.isInitialized.value })
        tts.shutdown()
    }

    @Test
    fun realSpeechRecognizerReturnsTheSpokenTestPhraseAndCleansUp() {
        assumeTrue("requires requireVoicePlatform=true", args.getString("requireVoicePlatform") == "true")
        val expected = args.getString("expectedSpeech").orEmpty().trim()
        assertTrue("expectedSpeech must contain the safe phrase the operator will speak", expected.length >= 3)
        assertTrue(
            "RECORD_AUDIO must be granted before the physical test",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        assertTrue("no platform recognition service installed", SpeechRecognizer.isRecognitionAvailable(context))

        val stt = entryPoint.speechRecognizerManager()
        instrumentation.runOnMainSync { stt.startListening("en-US", continuous = false) }
        assertTrue("STT produced no final result", eventually(30_000) {
            stt.speechState.value is SpeechRecognitionEvent.FinalResult ||
                stt.speechState.value is SpeechRecognitionEvent.RecognitionError
        })
        val event = stt.speechState.value
        assertTrue(
            "STT failed with ${(event as? SpeechRecognitionEvent.RecognitionError)?.errorCode}",
            event is SpeechRecognitionEvent.FinalResult &&
                event.recognizedText.contains(expected, ignoreCase = true)
        )
        instrumentation.runOnMainSync { stt.destroy() }
        assertEquals(SpeechRecognitionEvent.Idle, stt.speechState.value)
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }
}
