package com.jarvis.assistant.core.confirmation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Распознавание ответов на Confirmation Gate: «да/нет» в чате и голосом.
 */
class ConfirmationIntentTest {

    @Test
    fun `explicit yes phrases are recognized`() {
        listOf(
            "да",
            "Да!",
            "подтверждаю",
            "давай",
            "окей",
            "ок",
            "выполняй",
            "разрешаю",
            "звони",
            "набирай",
            "отправляй",
            "согласен",
            "делай",
            "конечно",
            "ага",
            "добро",
            "Да, отправляй"
        ).forEach { phrase ->
            assertTrue("isYes должен распознать '$phrase'", ConfirmationIntent.isYes(phrase))
        }
    }

    @Test
    fun `explicit no phrases are recognized`() {
        listOf(
            "нет",
            "Нет!",
            "отмена",
            "стоп",
            "отменить",
            "не надо",
            "не нужно",
            "отбой",
            "не стоит",
            "передумал",
            "хватит",
            "Нет, не отправляй"
        ).forEach { phrase ->
            assertTrue("isNo должен распознать '$phrase'", ConfirmationIntent.isNo(phrase))
        }
    }

    @Test
    fun `yes phrases are not no and vice versa`() {
        assertFalse(ConfirmationIntent.isNo("да"))
        assertFalse(ConfirmationIntent.isNo("подтверждаю"))
        assertFalse(ConfirmationIntent.isYes("нет"))
        assertFalse(ConfirmationIntent.isYes("отмена"))
    }

    @Test
    fun `neutral text is not a definitive answer`() {
        listOf("", "привет", "который час", "расскажи анекдот", "погода на завтра")
            .forEach { phrase ->
                assertFalse("'$phrase' не должен быть да/нет", ConfirmationIntent.isDefinitive(phrase))
            }
    }

    @Test
    fun `substring traps - pogoda and internet are not answers`() {
        // «погода» содержит подстроку «да», «интернет» — «нет»: это НЕ ответы.
        assertFalse(ConfirmationIntent.isYes("погода на завтра"))
        assertFalse(ConfirmationIntent.isNo("погода на завтра"))
        assertFalse(ConfirmationIntent.isYes("интернет"))
        assertFalse(ConfirmationIntent.isNo("интернет"))
        assertFalse(ConfirmationIntent.isYes("далматинец"))
        assertFalse(ConfirmationIntent.isNo("нетто"))
    }

    @Test
    fun `word-boundary yes and no with punctuation`() {
        assertTrue(ConfirmationIntent.isYes("да!"))
        assertTrue(ConfirmationIntent.isYes("Да, отправляй"))
        assertTrue(ConfirmationIntent.isYes("сделай это, да"))
        assertTrue(ConfirmationIntent.isNo("нет!"))
        assertTrue(ConfirmationIntent.isNo("нет, не надо"))
    }

    @Test
    fun `definitive combines yes and no`() {
        assertTrue(ConfirmationIntent.isDefinitive("да"))
        assertTrue(ConfirmationIntent.isDefinitive("нет"))
        assertFalse(ConfirmationIntent.isDefinitive("расскажи анекдот"))
    }

    @Test
    fun `case and whitespace are ignored`() {
        assertTrue(ConfirmationIntent.isYes("  ДА  "))
        assertTrue(ConfirmationIntent.isNo("  НЕТ  "))
        assertTrue(ConfirmationIntent.isYes("Да"))
    }
}
