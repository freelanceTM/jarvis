package com.jarvis.assistant.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTokenPolicyTest {

    @Test
    fun `server compatible token length boundaries are enforced`() {
        assertFalse(AccessTokenPolicy.isValid("x".repeat(31)))
        assertTrue(AccessTokenPolicy.isValid("x".repeat(32)))
        assertTrue(AccessTokenPolicy.isValid("x".repeat(256)))
        assertFalse(AccessTokenPolicy.isValid("x".repeat(257)))
    }

    @Test
    fun `blank whitespace and control characters are rejected`() {
        assertFalse(AccessTokenPolicy.isValid(""))
        assertFalse(AccessTokenPolicy.isValid(" ".repeat(32)))
        assertFalse(AccessTokenPolicy.isValid("a".repeat(31) + "\n"))
        assertFalse(AccessTokenPolicy.isValid("a".repeat(16) + " " + "b".repeat(16)))
    }

    @Test
    fun `opaque url safe token is accepted`() {
        assertTrue(AccessTokenPolicy.isValid("Ab9_-.$~".repeat(4)))
    }
}
