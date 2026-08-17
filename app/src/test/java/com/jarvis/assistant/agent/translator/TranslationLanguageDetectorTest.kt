package com.jarvis.assistant.agent.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Эвристический детектор языка (режим AUTO переводчика).
 */
class TranslationLanguageDetectorTest {

    @Test
    fun `cyrillic text is detected as russian`() {
        assertEquals("ru", TranslationLanguageDetector.detect("Привет, как дела?"))
        assertEquals("ru", TranslationLanguageDetector.detect("Сегодня хорошая погода в Ашхабаде"))
    }

    @Test
    fun `latin text without markers is english`() {
        assertEquals("en", TranslationLanguageDetector.detect("Hello, how are you?"))
        assertEquals("en", TranslationLanguageDetector.detect("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun `turkmen markers are detected`() {
        assertEquals("tk", TranslationLanguageDetector.detect("Salam, işler nähili?"))
        assertEquals("tk", TranslationLanguageDetector.detect("Men seni söýýärin"))
    }

    @Test
    fun `turkish markers are detected`() {
        assertEquals("tr", TranslationLanguageDetector.detect("Merhaba, nasılsın?"))
        assertEquals("tr", TranslationLanguageDetector.detect("Bugün hava çok güzel"))
    }

    @Test
    fun `german sharp s is detected`() {
        assertEquals("de", TranslationLanguageDetector.detect("Straße und Fußball"))
        assertEquals("de", TranslationLanguageDetector.detect("Grüße aus Berlin"))
    }

    @Test
    fun `latin text without language markers falls back to english honestly`() {
        // Без маркеров письма определить язык невозможно — честный дефолт en.
        assertEquals("en", TranslationLanguageDetector.detect("Guten Tag, wie geht es Ihnen?"))
    }

    @Test
    fun `chinese characters are detected`() {
        assertEquals("zh", TranslationLanguageDetector.detect("你好，今天天气很好"))
    }

    @Test
    fun `arabic script is detected`() {
        assertEquals("ar", TranslationLanguageDetector.detect("مرحبا، كيف حالك"))
    }

    @Test
    fun `empty or non-letter text returns null`() {
        assertNull(TranslationLanguageDetector.detect(""))
        assertNull(TranslationLanguageDetector.detect("   "))
        assertNull(TranslationLanguageDetector.detect("1234567890 !!!"))
    }
}
