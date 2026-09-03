package com.jarvis.assistant.agent.tools.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слой 3 Accessibility Lockdown: контентный санитайзер.
 *
 * Сценарии: 2FA-код показан ВНУТРИ обычного приложения (пакетный фильтр его
 * не знает), номер карты в чеке/заметке. Правила консервативны в сторону
 * маскировки: ложное срабатывание — одна «••••», пропуск — утечка в LLM.
 */
class ScreenTextSanitizerTest {

    @Test
    fun `six digit otp code in ordinary app is masked`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("Ваш код входа: 482913")
        assertEquals(1, masked)
        assertFalse(safe.contains("482913"))
        assertTrue(safe.contains(ScreenTextSanitizer.MASK))
    }

    @Test
    fun `alphanumeric short code near code context is masked`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("Steam Guard код RXT4Q действует 5 минут")
        assertTrue(masked >= 1)
        assertFalse(safe.contains("RXT4Q"))
    }

    @Test
    fun `card number with spaces is masked`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("Карта 4276 1600 1234 5678 активна")
        assertEquals(1, masked)
        assertFalse(safe.contains("4276"))
        assertFalse(safe.contains("5678"))
    }

    @Test
    fun `card number with dashes is masked`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("4276-1600-1234-5678")
        assertEquals(1, masked)
        assertFalse(safe.contains("4276"))
    }

    @Test
    fun `time money year and phone are NOT masked`() {
        val text = "Встреча в 18:00, сумма 50 000 рублей, год 2020, звонить +7 999 100 20 30"
        val (safe, masked) = ScreenTextSanitizer.sanitize(text)
        assertEquals(0, masked)
        assertEquals(text, safe)
        assertTrue(safe.contains("18:00"))
        assertTrue(safe.contains("50 000"))
        assertTrue(safe.contains("2020"))
    }

    @Test
    fun `plain words with digits are not masked`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("iPhone 15 стоит в магазине А4")
        assertEquals(0, masked)
        assertEquals("iPhone 15 стоит в магазине А4", safe)
    }

    @Test
    fun `eight digit code is masked`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("confirmation 12345678")
        assertEquals(1, masked)
        assertFalse(safe.contains("12345678"))
    }

    @Test
    fun `five digit number without context is not masked`() {
        // 4–5 цифр без код-контекста — это чаще всего суммы/номера, не OTP.
        val (safe, masked) = ScreenTextSanitizer.sanitize("заказ 48291 готов")
        assertEquals(0, masked)
        assertTrue(safe.contains("48291"))
    }

    @Test
    fun `password field marker text is masked in code context`() {
        val (safe, masked) = ScreenTextSanitizer.sanitize("пароль ab12cd")
        assertTrue(masked >= 1)
        assertFalse(safe.contains("ab12cd"))
    }
}
