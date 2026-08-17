package com.jarvis.assistant.agent.memory.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Честность embedding-слоя v0.2:
 * провайдеры существуют как контракт на будущее, но НЕ выдают векторы,
 * пока модель/эндпоинт реально не готовы. Никаких «примерных» векторов.
 */
class EmbeddingProviderTest {

    @Test
    fun `local provider honestly reports not ready`() = runBlocking {
        val provider = LocalEmbeddingProvider()

        assertFalse(provider.isReady())
        assertNull(provider.embed("привет"))
        assertTrue(provider.unavailabilityReason.isNotBlank())
    }

    @Test
    fun `remote provider honestly reports not ready`() = runBlocking {
        val provider = RemoteEmbeddingProvider()

        assertFalse(provider.isReady())
        assertNull(provider.embed("hello"))
        assertTrue(provider.unavailabilityReason.isNotBlank())
    }

    @Test
    fun `providers declare their dimensions for future wiring`() {
        assertEquals(384, LocalEmbeddingProvider().dimensions)
        assertEquals(1536, RemoteEmbeddingProvider().dimensions)
        assertTrue(LocalEmbeddingProvider().modelId.isNotBlank())
        assertTrue(RemoteEmbeddingProvider().modelId.isNotBlank())
    }

    @Test
    fun `embedding providers are distinct implementations of the contract`() = runBlocking {
        val providers: List<EmbeddingProvider> = listOf(
            LocalEmbeddingProvider(),
            RemoteEmbeddingProvider()
        )

        assertEquals(2, providers.size)
        providers.forEach { provider ->
            assertFalse(
                "Провайдер ${provider.modelId} не должен быть готов в v0.2",
                provider.isReady()
            )
        }
    }
}
