package com.jarvis.assistant.agent.localai

import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Этап 2 — unit-тесты локального AI-слоя.
 *
 * Проверяется логика решения и классификации исходов БЕЗ MediaPipe и без
 * реальной модели: runtime и model manager заменены управляемыми дублёрами.
 */
class OnDeviceLocalAiTest {

    // ------------------------------------------------------------------ fakes

    private class TestDispatchers(
        private val dispatcher: CoroutineDispatcher
    ) : CoroutineDispatchers {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val unconfined: CoroutineDispatcher = dispatcher
    }

    private class FakeRuntime(
        private val response: String = "Локальный ответ",
        private val throwOnGenerate: Throwable? = null,
        private val delayMs: Long = 0L
    ) : LocalModelRuntime {
        override val runtimeId: String = "fake-runtime"

        val calls = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        var lastPrompt: String? = null
            private set
        var lastConfig: GenerationConfig? = null
            private set

        override suspend fun generate(
            prompt: String,
            config: GenerationConfig,
            onToken: ((String) -> Unit)?
        ): LocalGeneration {
            calls.incrementAndGet()
            lastPrompt = prompt
            lastConfig = config

            throwOnGenerate?.let { throw it }

            if (delayMs > 0) {
                try {
                    delay(delayMs)
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }

            return LocalGeneration(
                text = response,
                metrics = InferenceMetrics(responseChars = response.length, latencyMs = 5)
            )
        }
    }

    private class FakeModelManager(
        private val runtime: LocalModelRuntime?,
        override var state: LocalModelState = LocalModelState.Ready("fake", 10),
        private val throwOnInit: Throwable? = null
    ) : LocalModelManager {
        val initCalls = AtomicInteger(0)

        override suspend fun initialize(): LocalModelState {
            initCalls.incrementAndGet()
            throwOnInit?.let { throw it }
            return state
        }

        override fun isReady(): Boolean = runtime != null

        override suspend fun runtimeOrNull(): LocalModelRuntime? {
            initCalls.incrementAndGet()
            throwOnInit?.let { throw it }
            return runtime
        }

        override suspend fun unload() = Unit
    }

    private fun buildLocalAi(
        manager: LocalModelManager,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) = OnDeviceLocalAi(
        modelManager = manager,
        promptBuilder = JarvisLocalPromptBuilder(),
        dispatchers = TestDispatchers(dispatcher)
    )

    private fun request(
        text: String = "Что значит квантовая запутанность?",
        source: RequestSource = RequestSource.VOICE,
        requiresWeb: Boolean = false,
        requiresDeviceControl: Boolean = false,
        privacy: PrivacyLevel = PrivacyLevel.NORMAL
    ) = ExecutionRequest(
        text = text,
        source = source,
        requiresWeb = requiresWeb,
        requiresDeviceControl = requiresDeviceControl,
        privacyLevel = privacy
    )

    // ------------------------------------------------------------------ tests

    /** Базовый путь: запрос → LocalAi → Success. */
    @Test
    fun `knowledge question is answered locally`() = runBlocking {
        val runtime = FakeRuntime("Это квантовая корреляция двух частиц.")
        val localAi = buildLocalAi(FakeModelManager(runtime))

        val result = localAi.execute(request())

        assertTrue("Ожидался Success, получено: $result", result is LocalAiResult.Success)
        assertEquals("Это квантовая корреляция двух частиц.", (result as LocalAiResult.Success).text)
        assertEquals(1, runtime.calls.get())
    }

    /** requiresWeb = true → Unsupported, инференс не запускается. */
    @Test
    fun `web request is unsupported and never reaches runtime`() = runBlocking {
        val runtime = FakeRuntime()
        val localAi = buildLocalAi(FakeModelManager(runtime))

        val result = localAi.execute(request("Какая сегодня цена биткоина", requiresWeb = true))

        assertTrue(result is LocalAiResult.Unsupported)
        assertEquals("Модель не должна вызываться", 0, runtime.calls.get())
    }

    /** Device-команда → Unsupported: локальная модель не управляет устройством. */
    @Test
    fun `device control request is unsupported`() = runBlocking {
        val runtime = FakeRuntime()
        val localAi = buildLocalAi(FakeModelManager(runtime))

        val result = localAi.execute(request("Открой Telegram", requiresDeviceControl = true))

        assertTrue(result is LocalAiResult.Unsupported)
        assertEquals(0, runtime.calls.get())
    }

    /** PRIVATE обрабатывается локально — в этом смысл локальной модели. */
    @Test
    fun `private request is processed locally`() = runBlocking {
        val runtime = FakeRuntime("Отвечаю локально.")
        val localAi = buildLocalAi(FakeModelManager(runtime))

        val result = localAi.execute(
            request("Мои личные заметки о здоровье", privacy = PrivacyLevel.PRIVATE)
        )

        assertTrue("PRIVATE должен обрабатываться локально: $result", result is LocalAiResult.Success)
        assertEquals(1, runtime.calls.get())
    }

    /** Исключение runtime → Error (а не падение и не Unsupported). */
    @Test
    fun `runtime exception becomes error`() = runBlocking {
        val runtime = FakeRuntime(throwOnGenerate = IllegalStateException("native crash"))
        val localAi = buildLocalAi(FakeModelManager(runtime))

        val result = localAi.execute(request())

        assertTrue("Ожидался Error, получено: $result", result is LocalAiResult.Error)
        val message = (result as LocalAiResult.Error).message
        assertTrue("Не должно быть деталей исключения: $message", !message.contains("native crash"))
    }

    /** Модель не установлена → Unsupported (движок уйдёт в облако). */
    @Test
    fun `missing model yields unsupported not error`() = runBlocking {
        val manager = FakeModelManager(
            runtime = null,
            state = LocalModelState.NotInstalled("/data/.../gemma3-1b-it-int4.task")
        )
        val localAi = buildLocalAi(manager)

        val result = localAi.execute(request())

        assertTrue("Отсутствие модели — не ошибка: $result", result is LocalAiResult.Unsupported)
    }

    /** Провал инициализации → Error. */
    @Test
    fun `model initialization failure yields error`() = runBlocking {
        val manager = FakeModelManager(
            runtime = null,
            state = LocalModelState.Failed("UnsatisfiedLinkError")
        )
        val localAi = buildLocalAi(manager)

        val result = localAi.execute(request())

        assertTrue("Ожидался Error, получено: $result", result is LocalAiResult.Error)
    }

    /** Исключение при инициализации не пробрасывается наружу. */
    @Test
    fun `initialization exception is converted to error`() = runBlocking {
        val manager = FakeModelManager(
            runtime = null,
            throwOnInit = OutOfMemoryError("no memory").let { IllegalStateException(it) }
        )
        val localAi = buildLocalAi(manager)

        val result = localAi.execute(request())

        assertTrue(result is LocalAiResult.Error)
    }

    /** Пустой ответ модели — это Error, а не «успех с пустотой». */
    @Test
    fun `empty model output is an error`() = runBlocking {
        val localAi = buildLocalAi(FakeModelManager(FakeRuntime("   ")))

        val result = localAi.execute(request())

        assertTrue(result is LocalAiResult.Error)
    }

    /** Слишком длинный вход не отдаётся 1B-модели. */
    @Test
    fun `overly long input is unsupported`() = runBlocking {
        val runtime = FakeRuntime()
        val localAi = buildLocalAi(FakeModelManager(runtime))

        val result = localAi.execute(request("а".repeat(5000)))

        assertTrue(result is LocalAiResult.Unsupported)
        assertEquals(0, runtime.calls.get())
    }

    /** Отмена корутины прерывает инференс и не превращается в Error. */
    @Test
    fun `cancellation propagates and stops inference`() = runBlocking {
        val runtime = FakeRuntime(delayMs = 10_000)
        val localAi = buildLocalAi(FakeModelManager(runtime))
        val started = java.util.concurrent.CountDownLatch(1)
        var caught: Throwable? = null

        val job = launch(Dispatchers.Default) {
            try {
                started.countDown()
                localAi.execute(request())
            } catch (e: CancellationException) {
                caught = e
            }
        }

        // Дожидаемся реального старта генерации, затем отменяем.
        started.await()
        while (runtime.calls.get() == 0) Thread.sleep(5)
        job.cancelAndJoin()

        assertEquals("Генерация должна была начаться", 1, runtime.calls.get())
        assertTrue("Инференс должен быть отменён", runtime.cancelled.get())
        assertTrue("CancellationException должен пробрасываться", caught is CancellationException)
    }

    /** Голос получает более короткий бюджет токенов, чем чат. */
    @Test
    fun `voice requests use shorter generation budget than chat`() = runBlocking {
        val voiceRuntime = FakeRuntime()
        buildLocalAi(FakeModelManager(voiceRuntime)).execute(request(source = RequestSource.VOICE))

        val chatRuntime = FakeRuntime()
        buildLocalAi(FakeModelManager(chatRuntime)).execute(request(source = RequestSource.CHAT))

        val voiceTokens = voiceRuntime.lastConfig!!.maxTokens
        val chatTokens = chatRuntime.lastConfig!!.maxTokens
        assertTrue("voice=$voiceTokens должен быть < chat=$chatTokens", voiceTokens < chatTokens)
    }

    /** Промпт содержит запреты на device-действия и на выдумывание из сети. */
    @Test
    fun `prompt instructs model not to fake actions or web data`() {
        val prompt = JarvisLocalPromptBuilder().build(request("тест"))

        assertTrue(prompt.contains("НЕ управляешь устройством"))
        assertTrue(prompt.contains("НЕТ доступа в интернет"))
        assertTrue("Текст запроса должен попасть в промпт", prompt.contains("тест"))
        assertTrue("Должен быть chat-шаблон Gemma", prompt.contains("<start_of_turn>"))
    }

    /** Инференс не должен идти на Main-потоке. */
    @Test
    fun `inference does not run on main dispatcher`() = runBlocking {
        val threadNames = mutableListOf<String>()
        val runtime = object : LocalModelRuntime {
            override val runtimeId = "thread-probe"
            override suspend fun generate(
                prompt: String,
                config: GenerationConfig,
                onToken: ((String) -> Unit)?
            ): LocalGeneration {
                threadNames += Thread.currentThread().name
                return LocalGeneration("ok", InferenceMetrics())
            }
        }

        // default = отдельный пул, main здесь намеренно другой диспетчер.
        val dispatchers = object : CoroutineDispatchers {
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
        }

        val localAi = OnDeviceLocalAi(
            modelManager = FakeModelManager(runtime),
            promptBuilder = JarvisLocalPromptBuilder(),
            dispatchers = dispatchers
        )

        withContext(Dispatchers.Unconfined) {
            localAi.execute(request())
        }

        assertEquals(1, threadNames.size)
        assertTrue(
            "Инференс ушёл не в фоновый пул: ${threadNames.first()}",
            threadNames.first().contains("DefaultDispatcher") ||
                threadNames.first().contains("worker")
        )
    }
}
