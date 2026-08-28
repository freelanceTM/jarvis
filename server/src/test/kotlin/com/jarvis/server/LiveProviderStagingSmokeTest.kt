package com.jarvis.server

import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.provider.GeminiProvider
import com.jarvis.server.provider.GroqProvider
import com.jarvis.server.provider.OkHttpTransport
import com.jarvis.server.provider.OpenRouterProvider
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excluded from ordinary tests; invoke only via :server:liveProviderSmokeTest. */
class LiveProviderStagingSmokeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val transport = OkHttpTransport()

    @Test
    fun `authorized staging accounts return parseable responses`() = runBlocking {
        require(System.getenv("RUN_LIVE_PROVIDER_SMOKE") == "true") {
            "RUN_LIVE_PROVIDER_SMOKE=true is required"
        }
        val providers = listOf(
            GroqProvider(config(ProviderId.GROQ), transport, json),
            GeminiProvider(config(ProviderId.GEMINI), transport, json),
            OpenRouterProvider(config(ProviderId.OPENROUTER), transport, json)
        )
        for (provider in providers) {
            val result = provider.execute(
                ProviderRequest(
                    requestId = "staging-smoke-${provider.id.name.lowercase()}",
                    prompt = "Reply with the single word OK.",
                    systemPrompt = "This is an authorized low-volume staging smoke test.",
                    maxTokens = 8,
                    temperature = 0.0
                )
            )
            assertTrue(
                "${provider.id} staging smoke failed with normalized result ${result.javaClass.simpleName}",
                result is ProviderResult.Success && result.text.isNotBlank()
            )
        }
    }

    private fun config(id: ProviderId): ProviderConfig {
        val prefix = id.name
        val key = requireSecret("${prefix}_STAGING_API_KEY")
        val model = requireSetting("${prefix}_STAGING_MODEL")
        val baseUrl = when (id) {
            ProviderId.GROQ -> System.getenv("GROQ_STAGING_BASE_URL")
                ?: "https://api.groq.com/openai/v1/chat/completions"
            ProviderId.GEMINI -> System.getenv("GEMINI_STAGING_BASE_URL")
                ?: "https://generativelanguage.googleapis.com/v1beta/models"
            ProviderId.OPENROUTER -> System.getenv("OPENROUTER_STAGING_BASE_URL")
                ?: "https://openrouter.ai/api/v1/chat/completions"
        }
        require(baseUrl.startsWith("https://")) { "$prefix staging base URL must use HTTPS" }
        return ProviderConfig(
            id = id,
            enabled = true,
            priority = 1,
            apiKey = key,
            model = model,
            baseUrl = baseUrl,
            connectTimeoutMs = 5_000,
            requestTimeoutMs = 20_000
        )
    }

    private fun requireSecret(name: String): String = requireNotNull(System.getenv(name)) {
        "$name must be supplied by a protected staging secret store"
    }.also { value ->
        require(value.length >= 12 && value.none(Char::isWhitespace)) { "$name has invalid shape" }
    }

    private fun requireSetting(name: String): String = requireNotNull(System.getenv(name)) {
        "$name is required for the authorized staging account"
    }.also { require(it.isNotBlank() && it.length <= 128) { "$name is invalid" } }
}
