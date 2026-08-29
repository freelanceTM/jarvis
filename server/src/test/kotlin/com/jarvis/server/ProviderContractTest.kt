package com.jarvis.server

import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.provider.GeminiProvider
import com.jarvis.server.provider.GroqProvider
import com.jarvis.server.provider.HttpTransport
import com.jarvis.server.provider.HttpTransportResponse
import com.jarvis.server.provider.OpenRouterProvider
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderMessage
import com.jarvis.server.provider.ProviderRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-4: контрактные тесты провайдеров по записанным фикстурам.
 *
 * Фикстуры в `src/test/resources/provider-contracts/` фиксируют РЕАЛЬНУЮ
 * форму ответов Groq / OpenRouter (OpenAI-совместимую) и Gemini. Если
 * провайдер изменит схему, изменится парсинг — эти тесты упадут ДО прода
 * (сейчас дрейф схемы обнаруживается только в рантайме).
 *
 * Что проверяется:
 *  - success-фикстуры парсятся в [ProviderResult.Success] с корректным
 *    текстом и usage-токенами; неизвестные поля игнорируются;
 *  - error-фикстуры (429/401) классифицируются в ожидаемые
 *    [ProviderFailureKind];
 *  - деградации схемы (пустые choices, safety-block без candidates,
 *    неожиданный JSON) дают честный Failure, а не исключение наверх.
 */
class ProviderContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun config(id: ProviderId, model: String) = ProviderConfig(
        id = id, enabled = true, priority = 1, apiKey = "test-key",
        model = model, baseUrl = "https://example.invalid/v1",
        connectTimeoutMs = 1000, requestTimeoutMs = 2000
    )

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/provider-contracts/$name")!!
            .readBytes().decodeToString()

    private class FixedTransport(private val response: HttpTransportResponse) : HttpTransport {
        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse = response
    }

    private fun transport(status: Int, body: String) = FixedTransport(HttpTransportResponse(status, body))

    private fun request(
        prompt: String = "Объясни квантовую запутанность",
        history: List<ProviderMessage> = emptyList()
    ) = ProviderRequest(
        requestId = "test-req",
        prompt = prompt,
        systemPrompt = "Ты JARVIS.",
        history = history,
        maxTokens = 512,
        temperature = 0.6
    )

    // ----------------------------------------------------------------- Groq

    @Test
    fun `groq success fixture parses text and usage tokens`() {
        val provider = GroqProvider(
            config(ProviderId.GROQ, "llama-3.3-70b-versatile"),
            transport(200, fixture("groq-chat-success.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue("expected Success, got $result", result is com.jarvis.server.provider.ProviderResult.Success)
        result as com.jarvis.server.provider.ProviderResult.Success
        assertTrue(result.text.startsWith("Квантовая запутанность"))
        assertEquals(148L, result.inputTokens)
        assertEquals(64L, result.outputTokens)
        assertEquals(212L, result.totalTokens)
    }

    @Test
    fun `groq 429 fixture classifies as RATE_LIMITED`() {
        val provider = GroqProvider(
            config(ProviderId.GROQ, "llama-3.3-70b-versatile"),
            transport(429, fixture("groq-rate-limit-429.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Failure)
        result as com.jarvis.server.provider.ProviderResult.Failure
        assertEquals(ProviderFailureKind.RATE_LIMITED, result.kind)
        assertEquals(429, result.httpStatus)
    }

    @Test
    fun `groq 401 fixture classifies as AUTH`() {
        val provider = GroqProvider(
            config(ProviderId.GROQ, "llama-3.3-70b-versatile"),
            transport(401, fixture("groq-unauthorized-401.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Failure)
        result as com.jarvis.server.provider.ProviderResult.Failure
        assertEquals(ProviderFailureKind.AUTH, result.kind)
    }

    // ----------------------------------------------------------- OpenRouter

    @Test
    fun `openrouter success fixture parses despite extra provider fields`() {
        val provider = OpenRouterProvider(
            config(ProviderId.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"),
            transport(200, fixture("openrouter-success.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Success)
        result as com.jarvis.server.provider.ProviderResult.Success
        assertEquals("Короткий ответ: да.", result.text)
        assertEquals(27L, result.totalTokens)
    }

    @Test
    fun `openrouter empty choices fixture fails honestly without crash`() {
        val provider = OpenRouterProvider(
            config(ProviderId.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"),
            transport(200, fixture("openrouter-empty-choices.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Failure)
        result as com.jarvis.server.provider.ProviderResult.Failure
        assertEquals(ProviderFailureKind.UNKNOWN, result.kind)
        assertEquals("empty completion", result.detail)
    }

    @Test
    fun `openrouter schema drift (choices as object) fails honestly`() {
        // Схема изменилась: choices — объект вместо массива. Парсер НЕ должен
        // бросать исключение наверх — контракт требует Failure.
        val drifted = """{"choices":{"0":{"message":{"content":"text"}}}}"""
        val provider = OpenRouterProvider(
            config(ProviderId.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"),
            transport(200, drifted),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Failure)
        result as com.jarvis.server.provider.ProviderResult.Failure
        assertEquals(ProviderFailureKind.UNKNOWN, result.kind)
    }

    // --------------------------------------------------------------- Gemini

    @Test
    fun `gemini success fixture joins parts and maps usage metadata`() {
        val provider = GeminiProvider(
            config(ProviderId.GEMINI, "gemini-1.5-flash"),
            transport(200, fixture("gemini-success.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Success)
        result as com.jarvis.server.provider.ProviderResult.Success
        assertEquals("Первый фрагмент ответа. Второй фрагмент ответа.", result.text)
        assertEquals(137L, result.inputTokens)
        assertEquals(48L, result.outputTokens)
        assertEquals(185L, result.totalTokens)
    }

    @Test
    fun `gemini safety block fixture (no candidates) fails honestly`() {
        val provider = GeminiProvider(
            config(ProviderId.GEMINI, "gemini-1.5-flash"),
            transport(200, fixture("gemini-safety-block.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Failure)
        result as com.jarvis.server.provider.ProviderResult.Failure
        assertEquals(ProviderFailureKind.UNKNOWN, result.kind)
        assertEquals("empty candidates", result.detail)
    }

    @Test
    fun `gemini 429 fixture classifies as RATE_LIMITED`() {
        val provider = GeminiProvider(
            config(ProviderId.GEMINI, "gemini-1.5-flash"),
            transport(429, fixture("gemini-rate-limit-429.json")),
            json
        )

        val result = runBlocking { provider.execute(request()) }

        assertTrue(result is com.jarvis.server.provider.ProviderResult.Failure)
        result as com.jarvis.server.provider.ProviderResult.Failure
        assertEquals(ProviderFailureKind.RATE_LIMITED, result.kind)
    }

    // ----------------------------------------------------- request shaping

    @Test
    fun `groq request body keeps canonical message order system-history-user`() {
        lateinit var sentBody: String
        val capturing = object : HttpTransport {
            override suspend fun post(
                url: String,
                headers: Map<String, String>,
                body: String,
                connectTimeoutMs: Long,
                requestTimeoutMs: Long
            ): HttpTransportResponse {
                sentBody = body
                return HttpTransportResponse(200, fixture("groq-chat-success.json"))
            }
        }
        val provider = GroqProvider(
            config(ProviderId.GROQ, "llama-3.3-70b-versatile"),
            capturing,
            json
        )

        runBlocking {
            provider.execute(
                request(
                    prompt = "текущий вопрос",
                    history = listOf(
                        ProviderMessage("user", "предыдущий вопрос"),
                        ProviderMessage("assistant", "предыдущий ответ")
                    )
                )
            )
        }

        val systemIdx = sentBody.indexOf(""""system"""")
        val historyIdx = sentBody.indexOf("предыдущий вопрос")
        val userIdx = sentBody.indexOf("текущий вопрос")
        assertTrue(systemIdx in 0 until historyIdx)
        assertTrue(historyIdx < userIdx)
    }
}
