package com.jarvis.assistant.agent.decision

import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyClassifierTest {
    @Test
    fun `normal prompt is classified normal`() {
        for (text in listOf(
            "расскажи о космосе",
            "как безопасно сменить пароль",
            "что такое CVV и где его нельзя публиковать",
            "какие бывают симптомы гриппа",
            "объясни шифрование приватным ключом"
        )) {
            assertEquals("text=$text", PrivacyLevel.NORMAL, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `obvious credentials tokens keys and database URLs are sensitive`() {
        val samples = listOf(
            "мой пароль от банка: Hunter2!",
            "PIN-код = 4821",
            "seed phrase: alpha-beta-gamma-delta",
            "Authorization: Bearer abcdefghijklmnop",
            "sk-abcdefghijklmnopqrstuvwxyz123456",
            "gsk_abcdefghijklmnopqrstuvwxyz",
            "AIzaabcdefghijklmnopqrstuvwxyz123456",
            "ghp_abcdefghijklmnopqrstuvwxyz123456",
            "AKIAIOSFODNN7EXAMPLE",
            "jrv_abcdefghijklmnopqrstuvwxyz123456",
            "-----BEGIN PRIVATE KEY----- secret",
            "postgresql://admin:actual-password@db.internal:5432/prod"
        )
        samples.forEach { text ->
            assertEquals("text=$text", PrivacyLevel.SENSITIVE, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `payment government and owned medical data are sensitive`() {
        for (text in listOf(
            "код из СМС 938201",
            "номер карты 4111 1111 1111 1111",
            "IBAN GB82WEST12345698765432",
            "passport number AB 1234567",
            "SSN: 123-45-6789",
            "мой диагноз — гипертония"
        )) {
            assertEquals("text=$text", PrivacyLevel.SENSITIVE, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `private contacts documents and business data are private`() {
        for (text in listOf(
            "мой домашний адрес — улица Ленина 10",
            "my email is user@example.com",
            "contact support@example.com",
            "{\"recipient\":\"Иван\"}",
            "call +31612345678",
            "my current device location",
            "{\"latitude\":52.518611,\"longitude\":5.471422}",
            "прочитай мою личную переписку",
            "данные моего паспорта",
            "confidential business strategy for acquisition"
        )) {
            assertEquals("text=$text", PrivacyLevel.PRIVATE, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `unicode whitespace url encoding escapes and wrappers cannot bypass detection`() {
        val samples = listOf(
            "ＰＡＳＳＷＯＲＤ = secret-value",
            "password\n:\tsecret-value",
            "password%3Dsecret-value",
            "{\"password\":\"secret-value\"}",
            "```env\nAPI_KEY=secret-value-123\n```",
            "s k - a b c d e f g h i j k l m n o p",
            "pass\u200Bword = secret-value",
            "password\\u003dsecret-value"
        )
        samples.forEach { text ->
            assertEquals("text=$text", PrivacyLevel.SENSITIVE, PrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `sensitive content in conversation history strengthens current request`() {
        val request = ExecutionRequest(
            text = "продолжи предыдущую мысль",
            source = RequestSource.CHAT,
            history = listOf(
                Message(role = MessageRole.USER, text = "password=history-secret")
            )
        )
        assertEquals(PrivacyLevel.UNKNOWN, request.privacyLevel)
        assertEquals(PrivacyLevel.SENSITIVE, request.effectivePrivacyLevel)
        assertFalse(request.isCloudAllowed)
    }

    @Test
    fun `empty malformed and very long input become unknown not normal`() {
        assertEquals(PrivacyLevel.UNKNOWN, PrivacyClassifier.classify("   "))
        assertEquals(PrivacyLevel.UNKNOWN, PrivacyClassifier.classify("hello\u0000world"))
        assertEquals(PrivacyLevel.UNKNOWN, PrivacyClassifier.classify("a".repeat(32_769)))
    }

    @Test
    fun `classifier exception and invalid result fail closed`() {
        val throwing = RequestPrivacyClassifier { throw IllegalStateException("classifier unavailable") }
        val failed = PrivacyClassifier.classifySafely(PrivacyContent("ordinary text"), throwing)
        assertEquals(PrivacyLevel.UNKNOWN, failed.level)
        assertTrue(PrivacyReason.CLASSIFIER_FAILURE in failed.reasons)

        val invalid = RequestPrivacyClassifier {
            PrivacyClassification(PrivacyLevel.UNKNOWN, emptySet(), complete = true)
        }
        assertEquals(
            PrivacyLevel.UNKNOWN,
            PrivacyClassifier.classifySafely(PrivacyContent("ordinary text"), invalid).level
        )
    }

    @Test
    fun `declared normal cannot downgrade automatic detection`() {
        val request = ExecutionRequest(
            text = "мой пароль от почты: qwerty-123",
            source = RequestSource.CHAT,
            privacyLevel = PrivacyLevel.NORMAL
        )
        assertEquals(PrivacyLevel.SENSITIVE, request.effectivePrivacyLevel)
        assertFalse(request.isCloudAllowed)
        assertFalse(request.loggableText.contains("qwerty"))
    }

    @Test
    fun `unknown cannot be overridden by cloud consent`() {
        val request = ExecutionRequest(
            text = "ordinary text",
            source = RequestSource.CHAT,
            privacyLevel = PrivacyLevel.NORMAL,
            cloudExplicitlyAllowed = true,
            privacyClassification = PrivacyClassification.unknown(PrivacyReason.CLASSIFIER_FAILURE)
        )
        assertEquals(PrivacyLevel.UNKNOWN, request.effectivePrivacyLevel)
        assertFalse(request.isCloudAllowed)
    }

    @Test
    fun `mixed and multiple findings choose sensitive without exposing values in reasons`() {
        val result = PrivacyClassifier.classifySafely(
            PrivacyContent("my home address is A; password=actual-secret; Bearer abcdefghijk")
        )
        assertEquals(PrivacyLevel.SENSITIVE, result.level)
        assertTrue(PrivacyReason.CREDENTIAL in result.reasons)
        assertTrue(PrivacyReason.AUTH_TOKEN in result.reasons)
        assertTrue(result.reasons.all { "actual-secret" !in it.name })
    }
}
