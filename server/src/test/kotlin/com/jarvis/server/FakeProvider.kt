package com.jarvis.server

import com.jarvis.server.provider.AiProvider
import com.jarvis.server.provider.ProviderCapabilities
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderResult
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Управляемый фейковый провайдер (пункт 34 ТЗ).
 *
 * Позволяет детерминированно воспроизвести success / timeout / 5xx / 429 /
 * invalid key, не обращаясь к реальным API.
 */
class FakeAiProvider(
    override val id: ProviderId,
    private val script: List<ProviderResult>,
    override val capabilities: ProviderCapabilities = ProviderCapabilities(),
    private val configured: Boolean = true,
    private val delayMs: Long = 0
) : AiProvider {

    val calls = AtomicInteger(0)

    override fun isConfigured(): Boolean = configured

    override suspend fun execute(request: ProviderRequest): ProviderResult {
        calls.incrementAndGet()
        if (delayMs > 0) delay(delayMs)
        return script[minOf(calls.get() - 1, script.lastIndex)]
    }

    companion object {
        fun ok(id: ProviderId, text: String = "ответ", model: String = "test-model") =
            FakeAiProvider(id, listOf(ProviderResult.Success(text, model, 10, 20, 30)))

        fun failing(id: ProviderId, kind: ProviderFailureKind, status: Int? = null) =
            FakeAiProvider(id, listOf(ProviderResult.Failure(kind, kind.name, status)))

        /** Сначала N сбоев, затем успех — для проверки retry. */
        fun failThenOk(id: ProviderId, kind: ProviderFailureKind, failures: Int) =
            FakeAiProvider(
                id,
                List(failures) { ProviderResult.Failure(kind, kind.name) } +
                    ProviderResult.Success("восстановился", "test-model")
            )
    }
}
