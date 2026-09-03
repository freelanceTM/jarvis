package com.jarvis.assistant.voice.tts

import com.jarvis.assistant.voice.tts.TextToSpeechManager.FocusReaction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EAR-MODE (audio focus): политика TTS при чужом фокусе.
 *
 * LOSS — фокус забран насовсем (навигация, другой медиаплеер, звонок):
 * речь останавливается, мы не переговариваемся с чужим звуком.
 * LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK / GAIN — продолжаем:
 * секундная чужая подсказка не должна убивать шёпот перевода в ухе.
 */
class TextToSpeechFocusPolicyTest {

    @Test
    fun `permanent loss stops speech`() {
        assertEquals(
            FocusReaction.STOP_SPEECH,
            TextToSpeechManager.mapFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
        )
    }

    @Test
    fun `transient loss keeps speaking`() {
        assertEquals(
            FocusReaction.CONTINUE,
            TextToSpeechManager.mapFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
    }

    @Test
    fun `transient-duck loss keeps speaking`() {
        assertEquals(
            FocusReaction.CONTINUE,
            TextToSpeechManager.mapFocusChange(
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            )
        )
    }

    @Test
    fun `focus gain keeps speaking`() {
        assertEquals(
            FocusReaction.CONTINUE,
            TextToSpeechManager.mapFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        )
    }
}
