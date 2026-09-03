package com.jarvis.assistant.agent.translator

import com.jarvis.assistant.agent.localai.GenerationConfig
import com.jarvis.assistant.agent.localai.InferenceMetrics
import com.jarvis.assistant.agent.localai.LocalGeneration
import com.jarvis.assistant.agent.localai.LocalModelManager
import com.jarvis.assistant.agent.localai.LocalModelRuntime
import com.jarvis.assistant.agent.localai.LocalModelState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Local-first перевод (полоса LOCAL AI ExecutionRouter): короткие реплики
 * переводятся on-device моделью; длинные тексты и «модель не готова» честно
 * уступают полосе CLOUD — ни один путь не изображает успех.
 */
class LocalLlmTranslationProviderTest {

    private class FakeRuntime(
        private val response: String = "  \"Привет, мир\"  ",
        private val failure: Exception? = null
    ) : LocalModelRuntime {
        override val runtimeId = "fake-local"
        var lastPrompt: String? = null
            private set

        override suspend fun generate(
            prompt: String,
            config: GenerationConfig,
            onToken: ((String) -> Unit)?
        ): LocalGeneration {
            lastPrompt = prompt
            failure?.let { throw it }
            return LocalGeneration(response, InferenceMetrics())
        }
    }

    private class FakeModelManager(
        private val ready: Boolean = true,
        private val runtime: LocalModelRuntime? = FakeRuntime()
    ) : LocalModelManager {
        override val state: LocalModelState =
            if (ready) LocalModelState.Ready("gemma-test", 1L) else LocalModelState.NotInstalled("/models/gemma")
        override suspend fun initialize(): LocalModelState = state
        override fun isReady(): Boolean = ready
        override suspend fun runtimeOrNull(): LocalModelRuntime? = runtime
        override suspend fun unload() = Unit
    }

    private fun provider(
        ready: Boolean = true,
        runtime: LocalModelRuntime? = FakeRuntime()
    ) = LocalLlmTranslationProvider(FakeModelManager(ready, runtime), TestDispatchers())

    @Test
    fun `provider is local and does not require network`() {
        val p = provider()
        assertTrue(p.isOffline)
        assertEquals("local_llm", p.providerId)
    }

    @Test
    fun `short text is translated on device with quotes stripped`() = runBlocking {
        val runtime = FakeRuntime(response = "  \"Привет, мир\"  ")
        val p = provider(runtime = runtime)

        val result = p.translate("Hello world", "en", "ru")

        assertTrue("Expected Success, got $result", result is TranslationResult.Success)
        assertEquals("Привет, мир", (result as TranslationResult.Success).translatedText)
        assertEquals("local_llm", result.providerId)
        // Промпт содержит строгие правила и текст без пояснений.
        assertTrue(runtime.lastPrompt!!.contains("Только") || runtime.lastPrompt!!.contains("ТОЛЬКО"))
        assertTrue(runtime.lastPrompt!!.contains("Hello world"))
    }

    @Test
    fun `long document honestly yields to cloud lane`() = runBlocking {
        val longText = "а".repeat(LocalLlmTranslationProvider.MAX_LOCAL_CHARS + 1)
        val runtime = FakeRuntime()
        val result = provider(runtime = runtime).translate(longText, "ru", "en")

        assertTrue(result is TranslationResult.ModelUnavailable)
        // Инференс НЕ запускался — длинный текст не должен доходить до модели.
        assertEquals(null, (runtime as FakeRuntime).lastPrompt)
    }

    @Test
    fun `model not installed yields to cloud lane`() = runBlocking {
        val result = provider(ready = false, runtime = null).translate("Hello", "en", "ru")
        assertTrue(result is TranslationResult.ModelUnavailable)
    }

    @Test
    fun `runtime failure is an honest error never a fabricated success`() = runBlocking {
        val runtime = FakeRuntime(failure = IOException("inference crashed"))
        val result = provider(runtime = runtime).translate("Hello", "en", "ru")
        assertTrue(result is TranslationResult.Error)
        assertFalse(result is TranslationResult.Success)
    }

    @Test
    fun `unsupported language pair is not claimed`() {
        val p = provider()
        assertFalse(p.supports("en", "ja"))
        assertTrue(p.supports("auto", "ru"))
    }

    /** Тестовый диспетчер: исполняет синхронно в том же потоке. */
    private class TestDispatchers : com.jarvis.assistant.core.dispatcher.CoroutineDispatchers {
        override val main = kotlinx.coroutines.Dispatchers.Unconfined
        override val io = kotlinx.coroutines.Dispatchers.Unconfined
        override val default = kotlinx.coroutines.Dispatchers.Unconfined
        override val unconfined = kotlinx.coroutines.Dispatchers.Unconfined
    }
}
