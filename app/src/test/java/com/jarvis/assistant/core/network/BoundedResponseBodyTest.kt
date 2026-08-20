package com.jarvis.assistant.core.network

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BoundedResponseBodyTest {

    @Test
    fun `small utf8 body is read`() {
        val body = "Привет".toResponseBody("application/json".toMediaTypeOrNull())
        assertEquals("Привет", body.readUtf8Bounded(64))
    }

    @Test
    fun `known oversized body is rejected before allocation`() {
        val body = "x".repeat(65).toResponseBody()
        expectTooLarge { body.readUtf8Bounded(64) }
    }

    @Test
    fun `unknown length streamed body is still bounded`() {
        val body = unknownLengthBody("x".repeat(65))
        expectTooLarge { body.readUtf8Bounded(64) }
    }

    @Test
    fun `limit is bytes not utf16 characters`() {
        val body = "яя".toResponseBody()
        expectTooLarge { body.readUtf8Bounded(3) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non positive limit is rejected`() {
        "ok".toResponseBody().readUtf8Bounded(0)
    }

    private fun unknownLengthBody(value: String): ResponseBody = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(value)
        override fun contentType() = "text/plain".toMediaTypeOrNull()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = buffer
    }

    private fun expectTooLarge(block: () -> Unit) {
        try {
            block()
            fail("Expected ResponseBodyTooLargeException")
        } catch (_: ResponseBodyTooLargeException) {
            // expected
        }
    }
}
