package com.jarvis.assistant.agent.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-17/S-03/N-01: sanity-регрессии для [PrivacyClassifier] на чистой JVM.
 */
class PrivacyClassifierRegressionTest {

    // PrivacyClassifier — object (singleton), инстанцировать не нужно.
    private val classifier = PrivacyClassifier

    @Test
    fun `ordinary safe text is classified NORMAL`() {
        val result = classifier.classifySafely(PrivacyContent("какая сегодня погода в Москве"))
        assertTrue(result.complete)
        assertEquals(PrivacyLevel.NORMAL, result.level)
    }

    @Test
    fun `email and phone are classified PRIVATE`() {
        val emailResult = classifier.classifySafely(PrivacyContent("напиши мне на user@example.com пожалуйста"))
        assertEquals(PrivacyLevel.PRIVATE, emailResult.level)
        assertTrue(emailResult.reasons.contains(PrivacyReason.PRIVATE_CONTACT))

        val phoneResult = classifier.classifySafely(PrivacyContent("мой номер +79161234567 запомни"))
        assertEquals(PrivacyLevel.PRIVATE, phoneResult.level)
    }

    @Test
    fun `api key and openai sk token are SENSITIVE`() {
        val raw = classifier.classifySafely(PrivacyContent("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZabcd123456"))
        assertEquals(PrivacyLevel.SENSITIVE, raw.level)
        assertTrue(raw.reasons.contains(PrivacyReason.AUTH_TOKEN))

        val assign = classifier.classifySafely(PrivacyContent("мой пароль SuperSecret123"))
        assertEquals(PrivacyLevel.SENSITIVE, assign.level)
    }

    @Test
    fun `effective level is strongest of declared and automatic`() {
        val autoNormal = PrivacyClassification(PrivacyLevel.NORMAL, setOf(PrivacyReason.NONE), complete = true)
        val autoPrivate = PrivacyClassification(PrivacyLevel.PRIVATE, setOf(PrivacyReason.PRIVATE_CONTACT), complete = true)
        val autoSensitive = PrivacyClassification(PrivacyLevel.SENSITIVE, setOf(PrivacyReason.AUTH_TOKEN), complete = true)

        assertEquals(PrivacyLevel.PRIVATE,
            PrivacyClassifier.effective(PrivacyLevel.NORMAL, autoPrivate))
        assertEquals(PrivacyLevel.PRIVATE,
            PrivacyClassifier.effective(PrivacyLevel.PRIVATE, autoNormal))
        assertEquals(PrivacyLevel.SENSITIVE,
            PrivacyClassifier.effective(PrivacyLevel.NORMAL, autoSensitive))
        assertEquals(PrivacyLevel.SENSITIVE,
            PrivacyClassifier.effective(PrivacyLevel.SENSITIVE, autoPrivate))
    }

    @Test
    fun `unknown declared with incomplete auto returns UNKNOWN fail-closed`() {
        val incomplete = PrivacyClassification(PrivacyLevel.UNKNOWN, setOf(PrivacyReason.CLASSIFIER_FAILURE), complete = false)
        assertEquals(PrivacyLevel.UNKNOWN, PrivacyClassifier.effective(PrivacyLevel.UNKNOWN, incomplete))
        assertEquals(PrivacyLevel.UNKNOWN,
            classifier.classifySafely(PrivacyContent("")).level)
    }

    @Test
    fun `sensitive pattern with surrounding whitespace still detected`() {
        val trailing = classifier.classifySafely(PrivacyContent("  sk-ABCDEFGHIJKLMNOPQRSTUVWXYZabcd123456  "))
        assertEquals(PrivacyLevel.SENSITIVE, trailing.level)
    }

    @Test
    fun `classifier never throws on weird unicode`() {
        val weird = classifier.classifySafely(PrivacyContent("\u0000\u0001🎉你好мир🔥"))
        assertTrue(weird.reasons.isNotEmpty())
        assertNotEquals(null, weird.level)
        // no credential patterns => not SENSITIVE
        assertFalse(weird.level == PrivacyLevel.SENSITIVE)
    }
}
