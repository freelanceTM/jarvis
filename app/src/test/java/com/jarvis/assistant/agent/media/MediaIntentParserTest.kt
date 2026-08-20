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
            "включи трек"
        ).forEach { phrase ->
            assertEquals("Phrase: $phrase", MediaIntent.PLAY_MEDIA, MediaIntentParser.parse(phrase))
        }
    }

    @Test
    fun `resume phrases map to RESUME_MEDIA not PLAY`() {
        listOf(
            "продолжи музыку",
            "продолжи воспроизведение",
            "возобнови музыку",
            "продолжай",
            "continue"
        ).forEach { phrase ->
            assertEquals("Phrase: $phrase", MediaIntent.RESUME_MEDIA, MediaIntentParser.parse(phrase))
        }
    }

    @Test
    fun `media volume up maps to VOLUME_UP`() {
        listOf(
            "сделай музыку громче",
            "прибавь звук музыки",
            "музыку громче",
            "прибавь громкость трека"
        ).forEach { phrase ->
            assertEquals("Phrase: $phrase", MediaIntent.VOLUME_UP, MediaIntentParser.parse(phrase))
        }
    }

    @Test
    fun `media volume down maps to VOLUME_DOWN`() {
        listOf(
            "сделай музыку тише",
            "убавь звук музыки",
            "музыку тише",
            "уменьши громкость трека"
        ).forEach { phrase ->
            assertEquals("Phrase: $phrase", MediaIntent.VOLUME_DOWN, MediaIntentParser.parse(phrase))
        }
    }

    @Test
    fun `generic volume without media context is not a media intent`() {
        // «сделай громче» без медиа-контекста — это device.volume, а не media.
        assertNull(MediaIntentParser.parse("сделай громче"))
        assertNull(MediaIntentParser.parse("прибавь громкость"))
        assertNull(MediaIntentParser.parse("тише"))
    }

    @Test
    fun `resume after pause is RESUME not PAUSE`() {
        assertEquals(MediaIntent.RESUME_MEDIA, MediaIntentParser.parse("продолжи после паузы"))
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
    fun `generic words inside non-media questions do not trigger playback actions`() {
        listOf(
            "Когда следующий матч сборной?",
            "Проанализируй следующий договор",
            "Какой был предыдущий результат?",
            "Продолжи анализ отчёта",
            "Что такое пауза в литературе?",
            "Explain the display pipeline"
        ).forEach { phrase ->
            assertNull("Phrase: $phrase", MediaIntentParser.parse(phrase))
        }
    }

    @Test
    fun `normalizeAction accepts canonical tool actions`() {
        assertEquals(MediaIntent.NEXT_TRACK, MediaIntentParser.normalizeAction("next"))
        assertEquals(MediaIntent.PREVIOUS_TRACK, MediaIntentParser.normalizeAction("prev"))
        assertEquals(MediaIntent.PLAY_MEDIA, MediaIntentParser.normalizeAction("play"))
        assertEquals(MediaIntent.TOGGLE_PLAY_PAUSE, MediaIntentParser.normalizeAction("play_pause"))
        assertEquals(MediaIntent.RESUME_MEDIA, MediaIntentParser.normalizeAction("resume"))
        assertEquals(MediaIntent.RESUME_MEDIA, MediaIntentParser.normalizeAction("continue"))
        assertEquals(MediaIntent.VOLUME_UP, MediaIntentParser.normalizeAction("volume_up"))
        assertEquals(MediaIntent.VOLUME_UP, MediaIntentParser.normalizeAction("up"))
        assertEquals(MediaIntent.VOLUME_DOWN, MediaIntentParser.normalizeAction("volume_down"))
        assertEquals(MediaIntent.VOLUME_DOWN, MediaIntentParser.normalizeAction("down"))
    }

    @Test
    fun `full intent model covers all eight required intents`() {
        assertEquals(
            listOf("play", "pause", "resume", "play_pause", "next", "previous", "stop", "volume_up", "volume_down"),
            MediaIntent.entries.map { it.action }
        )
    }

    @Test
    fun `normalizeAction rejects unknown action`() {
        assertNull(MediaIntentParser.normalizeAction("самоуничтожение"))
    }
}
