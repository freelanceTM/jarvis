package com.jarvis.assistant.data.remote

import com.jarvis.assistant.core.network.ResponseBodyTooLargeException
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JarvisApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var security: FakeSecurityManager
    private lateinit var client: JarvisApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        security = FakeSecurityManager(VALID_TOKEN)
        val rewritingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val localUrl = server.url(original.url.encodedPath)
                chain.proceed(original.newBuilder().url(localUrl).build())
            }
            .build()
        client = JarvisApiClient(
            rewritingClient,
            security,
            Json { ignoreUnknownKeys = true; isLenient = false }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `invalid token fails before any network request`() = runBlocking {
        security.saveAccessToken("short")

        val result = client.execute("hello", "CHAT", "NORMAL", requiresWeb = false)

        assertTrue(result is Resource.Error)
        assertEquals("INVALID_ACCESS_TOKEN", (result as Resource.Error).exception.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `successful response sends contextual contract and bearer token`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"text":"server answer","executionType":"CLOUD_AI","requestId":"server-id"}"""
            )
        )

        val result = client.execute(
            text = "ordinary question",
            source = "CHAT",
            privacyLevel = "NORMAL",
            requiresWeb = true,
            systemContext = "safe context",
            cloudExplicitlyAllowed = false
        )
        val request = server.takeRequest()

        assertEquals(Resource.Success("server answer"), result)
        assertEquals("/v1/ai/execute", request.path)
        assertEquals("Bearer $VALID_TOKEN", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"text\":\"ordinary question\""))
        assertTrue(body.contains("\"requiresWeb\":true"))
        assertTrue(body.contains("\"systemContext\":\"safe context\""))
        assertFalse(body.contains("modelOverride"))
    }

    @Test
    fun `normalized API error preserves machine code and safe user message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"success":false,"error":{"code":"RATE_LIMITED","message":"internal detail","requestId":"r-1"}}"""
            )
        )

        val result = client.execute("hello", "CHAT", "NORMAL", false)

        assertTrue(result is Resource.Error)
        val error = result as Resource.Error
        assertEquals("RATE_LIMITED", (error.exception as JarvisApiException).code)
        assertTrue(error.message.orEmpty().contains("Слишком много запросов"))
        assertFalse(error.message.orEmpty().contains("internal detail"))
    }

    @Test
    fun `redirect is not followed and is returned as an HTTP error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://example.invalid/steal")
        )

        val result = client.execute("hello", "CHAT", "NORMAL", false)

        assertTrue(result is Resource.Error)
        assertEquals("HTTP_302", ((result as Resource.Error).exception as JarvisApiException).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `oversized and malformed successful responses fail closed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(1024 * 1024 + 1)))
        val oversized = client.execute("hello", "CHAT", "NORMAL", false)
        assertTrue(oversized is Resource.Error)
        assertTrue((oversized as Resource.Error).exception is ResponseBodyTooLargeException)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{not-json"))
        val malformed = client.execute("hello", "CHAT", "NORMAL", false)
        assertTrue(malformed is Resource.Error)
        assertFalse((malformed as Resource.Error).message.orEmpty().contains("not-json"))
    }

    private class FakeSecurityManager(initial: String) : SecurityManager {
        private val token = MutableStateFlow(initial)
        override fun getAccessToken(): String = token.value
        override fun saveAccessToken(token: String) { this.token.value = token }
        override fun clearAccessToken() { token.value = "" }
        override fun hasValidAccessToken(): Boolean = token.value.length >= 32
        override val accessTokenFlow: Flow<String> = token
    }

    private companion object {
        const val VALID_TOKEN = "test-access-token-abcdefghijklmnopqrstuvwxyz"
    }
}
