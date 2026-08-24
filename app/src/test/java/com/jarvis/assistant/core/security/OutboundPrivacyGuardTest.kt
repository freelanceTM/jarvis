package com.jarvis.assistant.core.security

import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.ai.ContextualCloudAIClient
import com.jarvis.assistant.ai.PrivacyCloudBlockedException
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.data.repository.AIRepositoryImpl
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OutboundPrivacyGuardTest {
    private class TestDispatchers : CoroutineDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class CountingClient(
        private val preservesContext: Boolean = false
    ) : AIClient, ContextualCloudAIClient {
        val calls = AtomicInteger(0)
        var lastExplicitConsent: Boolean? = null

        override suspend fun complete(
            prompt: String,
            systemPrompt: String,
            history: List<Message>,
            modelOverride: String?
        ): Resource<String> {
            calls.incrementAndGet()
            return Resource.Success("ok")
        }

        override suspend fun completeWithContext(
            prompt: String,
            systemPrompt: String,
            source: String,
            privacyLevel: String,
            requiresWeb: Boolean,
            cloudExplicitlyAllowed: Boolean,
            relatedContent: List<String>
        ): Resource<String> {
            check(preservesContext)
            calls.incrementAndGet()
            lastExplicitConsent = cloudExplicitlyAllowed
            return Resource.Success("ok")
        }
    }

    private class LegacyCountingClient : AIClient {
        val calls = AtomicInteger(0)
        override suspend fun complete(
            prompt: String,
            systemPrompt: String,
            history: List<Message>,
            modelOverride: String?
        ): Resource<String> {
            calls.incrementAndGet()
            return Resource.Success("ok")
        }
    }

    @Test
    fun `normal legacy translation path can reach guarded client`() = runBlocking {
        val client = CountingClient()
        val repository = AIRepositoryImpl(client, TestDispatchers())

        val result = repository.generateResponse("hello world", "translate only", emptyList())

        assertTrue(result is Resource.Success)
        assertEquals(1, client.calls.get())
    }

    @Test
    fun `sensitive prompt system context and history never reach AI client`() = runBlocking {
        val samples = listOf(
            Triple("password=prompt-secret", "ordinary system", emptyList()),
            Triple("ordinary prompt", "Bearer system-context-token", emptyList()),
            Triple(
                "ordinary prompt",
                "ordinary system",
                listOf(Message(role = MessageRole.USER, text = "sk-historysecret123456789"))
            )
        )
        for ((prompt, system, history) in samples) {
            val client = CountingClient()
            val result = AIRepositoryImpl(client, TestDispatchers())
                .generateResponse(prompt, system, history)
            assertTrue(result is Resource.Error)
            assertTrue((result as Resource.Error).exception is PrivacyCloudBlockedException)
            assertEquals(0, client.calls.get())
        }
    }

    @Test
    fun `restricted cloud requires explicit per-request consent at repository boundary`() = runBlocking {
        val client = CountingClient(preservesContext = true)
        val repository = AIRepositoryImpl(client, TestDispatchers())

        val blocked = repository.generateResponse(
            prompt = "password=actual-secret",
            systemPrompt = "ordinary system",
            source = "CHAT",
            privacyLevel = "SENSITIVE",
            requiresWeb = false,
            cloudExplicitlyAllowed = false
        )
        val allowed = repository.generateResponse(
            prompt = "password=actual-secret",
            systemPrompt = "ordinary system",
            source = "CHAT",
            privacyLevel = "SENSITIVE",
            requiresWeb = false,
            cloudExplicitlyAllowed = true
        )

        assertTrue(blocked is Resource.Error)
        assertTrue(allowed is Resource.Success)
        assertEquals(1, client.calls.get())
        assertEquals(true, client.lastExplicitConsent)
    }

    @Test
    fun `invalid privacy category and metadata-dropping fallback fail closed`() = runBlocking {
        val client = LegacyCountingClient()
        val repository = AIRepositoryImpl(client, TestDispatchers())

        val invalid = repository.generateResponse(
            prompt = "ordinary prompt",
            systemPrompt = "ordinary system",
            source = "CHAT",
            privacyLevel = "INVALID_CATEGORY",
            requiresWeb = false
        )
        val fallback = repository.generateResponse(
            prompt = "ordinary prompt",
            systemPrompt = "ordinary system",
            source = "CHAT",
            privacyLevel = "NORMAL",
            requiresWeb = false
        )

        assertTrue(invalid is Resource.Error)
        assertTrue(fallback is Resource.Error)
        assertEquals(0, client.calls.get())
    }
}
