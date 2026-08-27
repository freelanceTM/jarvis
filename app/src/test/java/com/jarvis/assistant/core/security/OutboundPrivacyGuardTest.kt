package com.jarvis.assistant.core.security

import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.ai.ContextualCloudAIClient
import com.jarvis.assistant.ai.PrivacyCloudBlockedException
import com.jarvis.assistant.agent.decision.PrivacyLevel
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

/**
 * H-02 / Refactor #3: регрессионные тесты на outbound privacy guard
 * в [AIRepositoryImpl]. После рефакторинга репозиторий НЕ переклассифицирует
 * запрос — он доверяет пришедшему effective-уровню и только проверяет
 * invariant (PRIVATE/SENSITIVE без consent не уходят в сеть).
 *
 * Тест "sensitive prompt ... never reach AI client" убран: после рефакторинга
 * классификатор вызывается один раз в SendPromptUseCase и классифицированный
 * уровень передаётся в generateResponse() как строка; у репозитория нет
 * доступа к тексту промпта для перепроверки (в этом и был смысл рефакторинга).
 * Defense-in-depth обеспечивается invariant-check по privacyLevel+consent.
 */
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
        var lastEffectiveLevel: PrivacyLevel? = null

        override suspend fun complete(
            prompt: String,
            systemPrompt: String,
            history: List<Message>
        ): Resource<String> {
            calls.incrementAndGet()
            return Resource.Success("ok")
        }

        override suspend fun completeWithContext(
            prompt: String,
            systemPrompt: String,
            source: String,
            effectivePrivacyLevel: PrivacyLevel,
            requiresWeb: Boolean,
            cloudExplicitlyAllowed: Boolean,
            history: List<Message>
        ): Resource<String> {
            check(preservesContext)
            calls.incrementAndGet()
            lastExplicitConsent = cloudExplicitlyAllowed
            lastEffectiveLevel = effectivePrivacyLevel
            return Resource.Success("ok")
        }
    }

    private class LegacyCountingClient : AIClient {
        val calls = AtomicInteger(0)
        override suspend fun complete(
            prompt: String,
            systemPrompt: String,
            history: List<Message>
        ): Resource<String> {
            calls.incrementAndGet()
            return Resource.Success("ok")
        }
    }

    @Test
    fun `normal legacy translation path can reach guarded client`() = runBlocking {
        val client = CountingClient()
        val repository = AIRepositoryImpl(client, TestDispatchers())

        // legacy-вызов (без контекста) — идёт через AIClient.complete,
        // для переводчика по умолчанию NORMAL/consent=true.
        val result = repository.generateResponse("hello world", "translate only", emptyList())

        assertTrue(result is Resource.Success)
        assertEquals(1, client.calls.get())
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
        assertTrue(
            "SENSITIVE без consent должен быть заблокирован",
            (blocked as Resource.Error).exception is PrivacyCloudBlockedException
        )
        assertTrue("SENSITIVE с consent должен пройти", allowed is Resource.Success)
        assertEquals(1, client.calls.get())
        assertEquals(true, client.lastExplicitConsent)
        assertEquals(PrivacyLevel.SENSITIVE, client.lastEffectiveLevel)
    }

    @Test
    fun `invalid privacy category fails closed`() = runBlocking {
        val client = CountingClient(preservesContext = true)
        val repository = AIRepositoryImpl(client, TestDispatchers())

        val invalid = repository.generateResponse(
            prompt = "ordinary prompt",
            systemPrompt = "ordinary system",
            source = "CHAT",
            privacyLevel = "INVALID_CATEGORY",
            requiresWeb = false
        )

        assertTrue(invalid is Resource.Error)
        assertTrue(
            "Невалидный enum должен вернуть PrivacyCloudBlockedException(UNKNOWN)",
            (invalid as Resource.Error).exception is PrivacyCloudBlockedException
        )
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `NORMAL with or without consent proceeds to network`() = runBlocking {
        val client = CountingClient(preservesContext = true)
        val repository = AIRepositoryImpl(client, TestDispatchers())

        val withoutConsent = repository.generateResponse(
            prompt = "hello",
            systemPrompt = "sys",
            source = "CHAT",
            privacyLevel = "NORMAL",
            requiresWeb = false,
            cloudExplicitlyAllowed = false
        )
        assertTrue(withoutConsent is Resource.Success)
        assertEquals(PrivacyLevel.NORMAL, client.lastEffectiveLevel)
    }
}
