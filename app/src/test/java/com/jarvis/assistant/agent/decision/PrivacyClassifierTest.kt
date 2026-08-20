package com.jarvis.assistant.agent.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyClassifierTest {

    @Test
    fun `credentials card and owned medical data are sensitive`() {
        val samples = listOf(
            "мой пароль от банка: Hunter2!",
            "PIN-код = 4821",
            "код из СМС 938201",
            "номер карты 4111 1111 1111 1111",
            "мой диагноз — гипертония",
            "my password is correct-horse-battery-staple",
            "seed phrase: alpha beta gamma delta"
        )

        samples.forEach { text ->
            assertEquals("text=$text", PrivacyLevel.SENSITIVE, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `owned address correspondence and documents are private`() {
        val samples = listOf(
            "мой домашний адрес — улица Ленина 10",
            "прочитай мою личную переписку",
            "данные моего паспорта"
        )

        samples.forEach { text ->
            assertEquals("text=$text", PrivacyLevel.PRIVATE, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `educational security and medical questions are not false positives`() {
        val samples = listOf(
            "как безопасно сменить пароль",
            "что такое CVV и где его нельзя публиковать",
            "какие бывают симптомы гриппа",
            "как банки защищают номера карт",
            "объясни шифрование приватным ключом"
        )

        samples.forEach { text ->
            assertEquals("text=$text", PrivacyLevel.NORMAL, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `automatic classification strengthens request and redacts logs`() {
        val request = ExecutionRequest(
            text = "мой пароль от почты: qwerty-123",
            source = RequestSource.CHAT
        )

        assertEquals(PrivacyLevel.NORMAL, request.privacyLevel)
        assertEquals(PrivacyLevel.SENSITIVE, request.effectivePrivacyLevel)
        assertFalse(request.isCloudAllowed)
        assertFalse(request.loggableText.contains("qwerty"))
        assertTrue(request.loggableText.startsWith("<redacted:"))
    }

    @Test
    fun `explicit stronger level wins and cloud override stays explicit`() {
        val explicit = ExecutionRequest(
            text = "обычный текст",
            source = RequestSource.CHAT,
            privacyLevel = PrivacyLevel.SENSITIVE,
            cloudExplicitlyAllowed = true
        )

        assertEquals(PrivacyLevel.SENSITIVE, explicit.effectivePrivacyLevel)
        assertTrue(explicit.isCloudAllowed)
    }
}
