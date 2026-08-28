package com.jarvis.assistant.data.remote

import com.jarvis.assistant.core.network.ResponseBodyTooLargeException
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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

    /** CR-03: при передаче истории диалога она попадает в JSON как массив сообщений. */
    @Test
    fun `history is serialized into request body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"text":"ok","executionType":"CLOUD_AI","requestId":"r"}"""
            )
        )
        val result = client.execute(
            text = "вопрос",
            source = "CHAT",
            privacyLevel = "NORMAL",
            requiresWeb = false,
            history = listOf(
                MessageDto("user", "привет"),
                MessageDto("assistant", "здравствуйте")
            )
        )
        assertTrue(result is Resource.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("history.user present", body.contains(""""role":"user","content":"привет""""))
        assertTrue("history.assistant present", body.contains(""""role":"assistant","content":"здравствуйте""""))
    }

    /** CR-03: по умолчанию история отсутствует в теле (поведение до CR-03). */
    @Test
    fun `no history field when default`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"text":"ok","executionType":"CLOUD_AI","requestId":"r"}"""
            )
        )
        client.execute("hello", "CHAT", "NORMAL", false)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains(""""history""""))
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

    // -------------------------------------------------------------- CR-05

    /**
     * CR-05: отмена корутины вызывающего кода реально отменяет OkHttp Call.
     * Мы убеждаемся в этом косвенно:
     *   1) Запрос «висит» на сервере (без тела ответа) бесконечно.
     *   2) Родительская корутина отменяется; after-cancel мы ждём
     *      освобождение диспатчера; сервер в итоге получает cancel без
     *      ответа (takeRequest с нулевым timeout подтверждает, что запрос
     *      дошёл; requestCount подтверждает что клиент больше не пытался
     *      сделать второй запрос).
     * Главное проверяемое свойство: вызов не висит 30 секунд и не бросает
     *      Timeout, а завершается сразу после cancel'а корутины.
     */
    @Test
    fun `cancelling caller coroutine cancels underlying OkHttp call promptly`() {
        // Сервер принимает запрос и молчит (NO_RESPONSE): клиент обязан ждать
        // ответа до самой отмены. BodyDelay здесь не годится — отложенная запись
        // держит неинтерраптибельную задачу MockWebServer всё время задержки и
        // роняет её же shutdown() в tearDown (square/okhttp#3497). При
        // NO_RESPONSE закрытие сокета после call.cancel() разблокирует задачу
        // сервера чтением EOF, и shutdown() проходит чисто.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var job: Job
        job = testScope.launch {
            client.execute("q", "CHAT", "NORMAL", false)
        }
        // Дождёмся, пока MockWebServer увидел запрос.
        val gotRequest = CountDownLatch(1)
        Thread {
            try {
                server.takeRequest(10, TimeUnit.SECONDS)
                gotRequest.countDown()
            } catch (_: Throwable) {}
        }.start()
        assertTrue("server did not receive request in time", gotRequest.await(5, TimeUnit.SECONDS))

        // Отменяем корутину и ждём завершения job'а — это должно произойти
        // много раньше, чем OkHttp отвалит по собственным таймаутам.
        testScope.cancel()
        val finishedAt = System.nanoTime()
        runBlocking { job.join() }
        val elapsedMs = (System.nanoTime() - finishedAt) / 1_000_000
        assertTrue(
            "expected cancellation to finish promptly (< 5s), took ${elapsedMs}ms",
            elapsedMs < 5_000
        )
    }

    /** CR-06: клиент всегда шлёт X-Request-Deadline (epoch ms). */
    @Test
    fun `client sends X-Request-Deadline header with epoch ms`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"text":"ok","executionType":"CLOUD_AI","requestId":"r"}"""
            )
        )
        val before = System.currentTimeMillis()
        client.execute("hello", "CHAT", "NORMAL", false)
        val after = System.currentTimeMillis()
        val headerVal = server.takeRequest().getHeader("X-Request-Deadline")?.toLongOrNull()
        assertTrue("X-Request-Deadline must be present and numeric", headerVal != null)
        val expectedMin = before + TimeUnit.SECONDS.toMillis(JarvisApiClient.CALL_TIMEOUT_SECONDS)
        val expectedMax = after + TimeUnit.SECONDS.toMillis(JarvisApiClient.CALL_TIMEOUT_SECONDS) + 500
        assertTrue(
            "deadline $headerVal should be in [$expectedMin,$expectedMax]",
            headerVal in expectedMin..expectedMax
        )
    }

    private companion object {
        const val VALID_TOKEN = "test-access-token-abcdefghijklmnopqrstuvwxyz"
    }
}
