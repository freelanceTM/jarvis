package com.jarvis.assistant.agent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Регрессионные тесты семантики медиа-команд.
 *
 * Главный баг, который они фиксируют: «включи музыку» раньше превращалось
 * в NEXT_TRACK.
 */
class MediaIntentParserTest {

    @Test
    fun `включи музыку is PLAY_MEDIA not NEXT_TRACK`() {
        assertEquals(MediaIntent.PLAY_MEDIA, MediaIntentParser.parse("включи музыку"))
    }

    @Test
    fun `play synonyms map to PLAY_MEDIA`() {
        listOf(
            "включи музыку",
            "поставь музыку",
            "запусти музыку",
            "вруби музыку",
            "включи трек",
            "продолжи музыку"
        ).forEach { phrase ->
            assertEquals("Phrase: $phrase", MediaIntent.PLAY_MEDIA, MediaIntentParser.parse(phrase))
        }
    }

    @Test
    fun `next track phrases map to NEXT_TRACK`() {
        listOf("следующий трек", "следующая песня", "дальше", "перемотай вперед")
            .forEach { phrase ->
                assertEquals("Phrase: $phrase", MediaIntent.NEXT_TRACK, MediaIntentParser.parse(phrase))
            }
    }

    @Test
    fun `pause phrases map to PAUSE_MEDIA`() {
        listOf("поставь на паузу", "пауза", "приостанови")
            .forEach { phrase ->
                assertEquals("Phrase: $phrase", MediaIntent.PAUSE_MEDIA, MediaIntentParser.parse(phrase))
            }
    }

    @Test
    fun `previous track phrases map to PREVIOUS_TRACK`() {
        assertEquals(MediaIntent.PREVIOUS_TRACK, MediaIntentParser.parse("предыдущий трек"))
        assertEquals(MediaIntent.PREVIOUS_TRACK, MediaIntentParser.parse("верни трек"))
    }

    @Test
    fun `stop phrases map to STOP_MEDIA`() {
        assertEquals(MediaIntent.STOP_MEDIA, MediaIntentParser.parse("выключи музыку"))
        assertEquals(MediaIntent.STOP_MEDIA, MediaIntentParser.parse("останови музыку"))
    }

    @Test
    fun `specific beats generic when both keywords present`() {
        // "включи следующий трек" содержит и "включи", и "следующий трек"
        assertEquals(MediaIntent.NEXT_TRACK, MediaIntentParser.parse("включи следующий трек"))
    }

    @Test
    fun `non media phrase returns null`() {
        assertNull(MediaIntentParser.parse("какая сегодня погода"))
        assertNull(MediaIntentParser.parse("позвони маме"))
    }

    @Test
    fun `normalizeAction accepts canonical tool actions`() {
        assertEquals(MediaIntent.NEXT_TRACK, MediaIntentParser.normalizeAction("next"))
        assertEquals(MediaIntent.PREVIOUS_TRACK, MediaIntentParser.normalizeAction("prev"))
        assertEquals(MediaIntent.PLAY_MEDIA, MediaIntentParser.normalizeAction("play"))
        assertEquals(MediaIntent.TOGGLE_PLAY_PAUSE, MediaIntentParser.normalizeAction("play_pause"))
    }

    @Test
    fun `normalizeAction rejects unknown action`() {
        assertNull(MediaIntentParser.normalizeAction("самоуничтожение"))
    }
}
