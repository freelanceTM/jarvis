package com.jarvis.assistant.agent.tools.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverNumberTest {

    @Test
    fun `phone-like input accepts common formatting and normalizes it`() {
        val values = mapOf(
            "+7 (999) 123-45-67" to "+79991234567",
            "123 4567" to "1234567",
            "+993 65 12 34 56" to "+99365123456"
        )
        for ((raw, expected) in values) {
            assertTrue(ContactResolver.looksLikePhoneNumber(raw))
            val normalized = ContactResolver.normalizeNumber(raw)
            assertEquals(expected, normalized)
            assertTrue(ContactResolver.isValidNormalizedNumber(normalized))
        }
    }

    @Test
    fun `names command injection shapes and invalid phone boundaries are rejected`() {
        listOf("маме", "Иван", "123", "+1234567890123456", "++++1234", "1234;DROP TABLE")
            .forEach { raw ->
                val accepted = ContactResolver.looksLikePhoneNumber(raw) &&
                    ContactResolver.isValidNormalizedNumber(ContactResolver.normalizeNumber(raw))
                assertFalse("raw=$raw", accepted)
            }
    }
}
