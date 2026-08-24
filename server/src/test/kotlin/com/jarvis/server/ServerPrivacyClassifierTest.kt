package com.jarvis.server

import com.jarvis.server.api.ApiPrivacyLevel
import com.jarvis.server.privacy.PrivacyClassification
import com.jarvis.server.privacy.PrivacyContent
import com.jarvis.server.privacy.PrivacyReason
import com.jarvis.server.privacy.PromptPrivacyClassifier
import com.jarvis.server.privacy.ServerPrivacyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPrivacyClassifierTest {
    @Test
    fun `normal content is normal`() {
        assertEquals(ApiPrivacyLevel.NORMAL, PromptPrivacyClassifier.classify("расскажи о космосе"))
        assertEquals(ApiPrivacyLevel.NORMAL, PromptPrivacyClassifier.classify("как сменить пароль"))
    }

    @Test
    fun `credentials tokens private keys and database URLs are sensitive`() {
        for (text in listOf(
            "password=actual-secret",
            "Bearer abcdefghijklmnop",
            "sk-abcdefghijklmnopqrstuvwxyz123456",
            "-----BEGIN RSA PRIVATE KEY-----",
            "jdbc:postgresql://admin:db-password@db.internal/prod"
        )) {
            assertEquals("text=$text", ApiPrivacyLevel.SENSITIVE, PromptPrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `payments identifiers medical and private documents are classified`() {
        assertEquals(ApiPrivacyLevel.SENSITIVE, PromptPrivacyClassifier.classify("номер карты 4111 1111 1111 1111"))
        assertEquals(ApiPrivacyLevel.SENSITIVE, PromptPrivacyClassifier.classify("passport number AB 1234567"))
        assertEquals(ApiPrivacyLevel.SENSITIVE, PromptPrivacyClassifier.classify("мой диагноз — гипертония"))
        assertEquals(ApiPrivacyLevel.PRIVATE, PromptPrivacyClassifier.classify("прочитай мою личную переписку"))
        assertEquals(ApiPrivacyLevel.PRIVATE, PromptPrivacyClassifier.classify("{\"recipient\":\"Иван\"}"))
        assertEquals(ApiPrivacyLevel.PRIVATE, PromptPrivacyClassifier.classify("call +31612345678"))
        assertEquals(ApiPrivacyLevel.PRIVATE, PromptPrivacyClassifier.classify("my current device location"))
        assertEquals(ApiPrivacyLevel.PRIVATE, PromptPrivacyClassifier.classify("{\"latitude\":52.518611}"))
        assertEquals(ApiPrivacyLevel.PRIVATE, PromptPrivacyClassifier.classify("confidential business strategy"))
    }

    @Test
    fun `encoded unicode split and escaped secrets cannot bypass`() {
        for (text in listOf(
            "ＰＡＳＳＷＯＲＤ = secret-value",
            "password%3Dsecret-value",
            "password\\u003dsecret-value",
            "s k - a b c d e f g h i j k l m n o p",
            "{\"api_key\":\"secret-value-123\"}"
        )) {
            assertEquals("text=$text", ApiPrivacyLevel.SENSITIVE, PromptPrivacyClassifier.classify(text))
        }
    }

    @Test
    fun `related system or history content participates in classification`() {
        val result = PromptPrivacyClassifier.classifySafely(
            PrivacyContent("ordinary request", listOf("password=context-secret"))
        )
        assertEquals(ApiPrivacyLevel.SENSITIVE, result.level)
    }

    @Test
    fun `empty malformed and overlong input are unknown`() {
        assertEquals(ApiPrivacyLevel.UNKNOWN, PromptPrivacyClassifier.classify(""))
        assertEquals(ApiPrivacyLevel.UNKNOWN, PromptPrivacyClassifier.classify("a\u0000b"))
        assertEquals(ApiPrivacyLevel.UNKNOWN, PromptPrivacyClassifier.classify("a".repeat(32_769)))
    }

    @Test
    fun `exception and invalid category fail closed`() {
        val throwing = ServerPrivacyClassifier { throw IllegalStateException("unavailable") }
        val failed = PromptPrivacyClassifier.classifySafely(PrivacyContent("ordinary"), throwing)
        assertEquals(ApiPrivacyLevel.UNKNOWN, failed.level)
        assertTrue(PrivacyReason.CLASSIFIER_FAILURE in failed.reasons)

        val invalid = ServerPrivacyClassifier {
            PrivacyClassification(ApiPrivacyLevel.UNKNOWN, emptySet(), complete = true)
        }
        assertEquals(
            ApiPrivacyLevel.UNKNOWN,
            PromptPrivacyClassifier.classifySafely(PrivacyContent("ordinary"), invalid).level
        )
    }

    @Test
    fun `declared metadata can strengthen but never weaken or repair failure`() {
        val sensitive = PromptPrivacyClassifier.classifySafely(PrivacyContent("password=secret-value"))
        assertEquals(
            ApiPrivacyLevel.SENSITIVE,
            PromptPrivacyClassifier.effective(ApiPrivacyLevel.NORMAL, sensitive)
        )
        val unknown = PrivacyClassification.unknown(PrivacyReason.CLASSIFIER_FAILURE)
        assertEquals(
            ApiPrivacyLevel.UNKNOWN,
            PromptPrivacyClassifier.effective(ApiPrivacyLevel.NORMAL, unknown)
        )
        assertFalse(unknown.complete)
    }
}
