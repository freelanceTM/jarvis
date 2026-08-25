package com.jarvis.server

import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.provider.BaseHttpProvider
import com.jarvis.server.provider.GeminiProvider
import com.jarvis.server.provider.GroqProvider
import com.jarvis.server.provider.HttpTransport
import com.jarvis.server.provider.HttpTransportResponse
import com.jarvis.server.provider.OpenRouterProvider
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderMessage
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderResult
import com.jarvis.server.provider.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpProvidersTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private data class Captured(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val connectTimeoutMs: Long,
        val requestTimeoutMs: Long
    )

    private class FakeTransport(
        private val response: HttpTransportResponse? = null,
        private val failure: Throwable? = null
    ) : HttpTransport {
        var captured: Captured? = null

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            captured = Captured(url, headers, body, connectTimeoutMs, requestTimeoutMs)
            failure?.let { throw it }
            return response ?: error("no scripted response")
        }
    }

    private fun config(id: ProviderId, enabled: Boolean = true, key: String? = "secret") =
        ProviderConfig(
            id = id,
            enabled = enabled,
            priority = 1,
            apiKey = key,
            model = if (id == ProviderId.GEMINI) "gemini-test" else "oai-test",
            baseUrl = if (id == ProviderId.GEMINI) "https://gemini.invalid/models" else "https://oai.invalid/chat",
            connectTimeoutMs = 123,
            requestTimeoutMs = 456
        )

    private fun request() = ProviderRequest(
        requestId = "r-1",
        prompt = "hello",
        systemPrompt = "system rules",
        maxTokens = 77,
        temperature = 0.25
    )

    @Test
    fun `groq includes history in system-user-assistant order`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            )
        )
        val req = request().copy(
            history = listOf(
                ProviderMessage("user", "hi"),
                ProviderMessage("assistant", "hello"),
                ProviderMessage("user", "how are you")
            )
        )
        GroqProvider(config(ProviderId.GROQ), transport, json).execute(req)
        val body = json.parseToJsonElement(transport.captured!!.body).jsonObject
        val messages = body["messages"]!!.jsonArray
        // 1 system + 3 history + 1 current user = 5
        assertEquals(5, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("system rules", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("how are you", messages[3].jsonObject["content"]!!.jsonPrimitive.content)
        // last = current prompt
        assertEquals("user", messages[4].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[4].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `groq trims history to 24 KB budget dropping oldest first`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            )
        )
        val big = "x".repeat(5_000) // 5000 bytes in UTF-8 per message
        val req = request().copy(
            history = listOf(
                ProviderMessage("user", "first-$big"),
                ProviderMessage("assistant", "second-$big"),
                ProviderMessage("user", "third-$big"),
                ProviderMessage("assistant", "fourth-$big"),
                ProviderMessage("user", "fifth-$big"),
                ProviderMessage("assistant", "sixth-$big")
            )
        )
        GroqProvider(config(ProviderId.GROQ), transport, json).execute(req)
        val messages = json.parseToJsonElement(transport.captured!!.body)
            .jsonObject["messages"]!!.jsonArray
        // 1 system + latest history fits in 24KB + 1 current prompt
        // The first (oldest) message(s) must be dropped.
        val roleContents = messages.map {
            val c = it.jsonObject["content"]!!.jsonPrimitive.content
            c.take(15)
        }
        // "first-" must be gone
        assertTrue(roleContents.none { it.startsWith("first-") })
        // Latest history ("sixth-") must remain, and current prompt "hello" is last.
        assertTrue(roleContents.any { it.startsWith("sixth-") })
        assertEquals("hello", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `groq with empty history preserves old behavior (system + user)`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            )
        )
        GroqProvider(config(ProviderId.GROQ), transport, json).execute(request())
        val messages = json.parseToJsonElement(transport.captured!!.body)
            .jsonObject["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini maps assistant role to model and preserves history order`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""
            )
        )
        val req = request().copy(
            history = listOf(
                ProviderMessage("user", "hi"),
                ProviderMessage("assistant", "hello"),
                ProviderMessage("system", "be nice")
            )
        )
        GeminiProvider(config(ProviderId.GEMINI), transport, json).execute(req)
        val body = json.parseToJsonElement(transport.captured!!.body).jsonObject
        val contents = body["contents"]!!.jsonArray
        // 3 turns (hi user, hello model, how-are-you user? — wait: system folded into instruction)
        // history has 2 user/assistant turns + 1 current = 3 contents entries
        assertEquals(3, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", contents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("user", contents[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        // system from history must be folded into systemInstruction
        val sys = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(sys.contains("system rules"))
        assertTrue(sys.contains("be nice"))
    }

    @Test
    fun `gemini includes google_search_retrieval tool only when requiresWeb=true`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""
            )
        )
        val cfg = config(ProviderId.GEMINI)

        // Without web — tools либо отсутствует, либо пустой.
        GeminiProvider(cfg, transport, json).execute(request().copy(requiresWeb = false))
        val bodyNoWeb = json.parseToJsonElement(transport.captured!!.body).jsonObject
        val toolsNoWeb = bodyNoWeb["tools"]?.jsonArray
        val hasSearchNoWeb = toolsNoWeb?.any {
            it.jsonObject.containsKey("google_search_retrieval")
        } ?: false
        assertFalse("requiresWeb=false should not add google_search_retrieval", hasSearchNoWeb)

        val transport2 = FakeTransport(
            HttpTransportResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""
            )
        )
        GeminiProvider(cfg, transport2, json).execute(request().copy(requiresWeb = true))
        val bodyWeb = json.parseToJsonElement(transport2.captured!!.body).jsonObject
        val toolsWeb = bodyWeb["tools"]!!.jsonArray
        assertTrue(
            "requiresWeb=true must add google_search_retrieval tool",
            toolsWeb.any { it.jsonObject.containsKey("google_search_retrieval") }
        )
    }

    @Test
    fun `gemini advertises supportsWeb=true`() {
        assertTrue(
            "Gemini must declare supportsWeb for CR-16 routing",
            GeminiProvider(
                config(ProviderId.GEMINI),
                FakeTransport(HttpTransportResponse(200, "{}")),
                json
            ).capabilities.supportsWeb
        )
    }

    @Test
    fun `HISTORY_BUDGET_BYTES constant is 24 KB`() {
        assertEquals(24 * 1024, BaseHttpProvider.HISTORY_BUDGET_BYTES)
    }

    @Test
    fun `groq sends normalized OpenAI request and parses usage`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"  answer  "}}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5},"ignored":true}"""
            )
        )
        val result = GroqProvider(config(ProviderId.GROQ), transport, json).execute(request())

        val success = result as ProviderResult.Success
        assertEquals("answer", success.text)
        assertEquals(2L, success.inputTokens)
        assertEquals(3L, success.outputTokens)
        assertEquals(5L, success.totalTokens)

        val sent = transport.captured!!
        assertEquals("https://oai.invalid/chat", sent.url)
        assertEquals("Bearer secret", sent.headers["Authorization"])
        assertEquals(123L, sent.connectTimeoutMs)
        assertEquals(456L, sent.requestTimeoutMs)
        val body = json.parseToJsonElement(sent.body).jsonObject
        assertEquals("oai-test", body["model"]!!.jsonPrimitive.content)
        assertEquals(77, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val messages = body["messages"]!!.jsonArray
        assertEquals("system rules", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter adds attribution without exposing it in result`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(200, """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        )
        val result = OpenRouterProvider(config(ProviderId.OPENROUTER), transport, json).execute(request())

        assertTrue(result is ProviderResult.Success)
        assertEquals("JARVIS", transport.captured!!.headers["X-Title"])
        assertEquals("https://jarvis.ai", transport.captured!!.headers["HTTP-Referer"])
    }

    @Test
    fun `gemini uses provider format endpoint and joins response parts`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"world"}]}}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":6,"totalTokenCount":10}}"""
            )
        )
        val result = GeminiProvider(config(ProviderId.GEMINI), transport, json).execute(request())

        val success = result as ProviderResult.Success
        assertEquals("hello world", success.text)
        assertEquals(10L, success.totalTokens)
        val sent = transport.captured!!
        assertEquals("https://gemini.invalid/models/gemini-test:generateContent", sent.url)
        assertEquals("secret", sent.headers["x-goog-api-key"])
        val body = json.parseToJsonElement(sent.body).jsonObject
        assertEquals("hello", body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("system rules", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all relevant HTTP statuses are normalized`() = runBlocking {
        val expected = mapOf(
            400 to ProviderFailureKind.BAD_REQUEST,
            401 to ProviderFailureKind.AUTH,
            403 to ProviderFailureKind.AUTH,
            404 to ProviderFailureKind.BAD_REQUEST,
            408 to ProviderFailureKind.TIMEOUT,
            418 to ProviderFailureKind.UNKNOWN,
            422 to ProviderFailureKind.BAD_REQUEST,
            429 to ProviderFailureKind.RATE_LIMITED,
            500 to ProviderFailureKind.SERVER_ERROR,
            599 to ProviderFailureKind.SERVER_ERROR
        )
        for ((status, kind) in expected) {
            val provider = GroqProvider(
                config(ProviderId.GROQ),
                FakeTransport(HttpTransportResponse(status, "upstream secret body must be ignored")),
                json
            )
            val failure = provider.execute(request()) as ProviderResult.Failure
            assertEquals("status=$status", kind, failure.kind)
            assertFalse(failure.detail.contains("secret"))
            assertEquals(status, failure.httpStatus)
        }
    }

    @Test
    fun `disabled and missing-key providers never touch transport`() = runBlocking {
        for (cfg in listOf(config(ProviderId.GROQ, enabled = false), config(ProviderId.GROQ, key = null))) {
            val transport = FakeTransport(HttpTransportResponse(200, "{}"))
            val result = GroqProvider(cfg, transport, json).execute(request()) as ProviderResult.Failure
            assertEquals(ProviderFailureKind.NOT_CONFIGURED, result.kind)
            assertEquals(null, transport.captured)
        }
    }

    @Test
    fun `malformed and empty successful upstream responses fail safely`() = runBlocking {
        for (body in listOf("{not-json", "{}", """{"choices":[{"message":{"role":"assistant","content":"   "}}]}""")) {
            val result = GroqProvider(
                config(ProviderId.GROQ),
                FakeTransport(HttpTransportResponse(200, body)),
                json
            ).execute(request())
            assertTrue(result is ProviderResult.Failure)
            assertEquals(ProviderFailureKind.UNKNOWN, (result as ProviderResult.Failure).kind)
        }
    }

    @Test
    fun `transport failures preserve normalized kind and cancellation propagates`() = runBlocking {
        val timeout = GroqProvider(
            config(ProviderId.GROQ),
            FakeTransport(failure = TransportException(ProviderFailureKind.TIMEOUT, "socket timeout")),
            json
        ).execute(request()) as ProviderResult.Failure
        assertEquals(ProviderFailureKind.TIMEOUT, timeout.kind)

        var cancelled = false
        try {
            GroqProvider(
                config(ProviderId.GROQ),
                FakeTransport(failure = CancellationException("cancel")),
                json
            ).execute(request())
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
