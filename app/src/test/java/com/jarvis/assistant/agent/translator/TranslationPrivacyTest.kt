package com.jarvis.assistant.agent.translator

import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.data.repository.AIRepositoryImpl
import com.jarvis.assistant.domain.models.Message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TranslationPrivacyTest {
    private class OnlineMonitor : NetworkMonitor {
        override val isOnline: Flow<Boolean> = flowOf(true)
        override fun isCurrentlyOnline(): Boolean = true
    }

    private class TestDispatchers : CoroutineDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class CountingClient : AIClient {
        val calls = AtomicInteger(0)
        override suspend fun complete(
            prompt: String,
            systemPrompt: String,
            history: List<Message>,
            modelOverride: String?
        ): Resource<String> {
            calls.incrementAndGet()
            return Resource.Success("translated")
        }
    }

    @Test
    fun `translation classifies before its cloud client boundary`() = runBlocking {
        val client = CountingClient()
        val provider = LlmTranslationProvider(
            AIRepositoryImpl(client, TestDispatchers()),
            OnlineMonitor()
        )

        val blocked = provider.translate("password=translation-secret", "en", "ru")
        val allowed = provider.translate("good morning", "en", "ru")

        assertTrue(blocked is TranslationResult.Error)
        assertTrue(allowed is TranslationResult.Success)
        assertEquals(1, client.calls.get())
    }
}
