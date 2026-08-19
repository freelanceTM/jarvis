package com.jarvis.assistant.agent.localai

import android.util.Log
import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальный AI поверх [LocalModelManager] / [LocalModelRuntime].
 *
 * Ответственность класса — РЕШИТЬ, берётся ли локальная модель за запрос,
 * и корректно классифицировать исход. Сам инференс делегируется runtime.
 *
 * Гарантии:
 *  - inference никогда не идёт на Main Thread (используется существующий
 *    [CoroutineDispatchers]);
 *  - `requiresWeb` локально не выполняется (пункт 12 ТЗ);
 *  - device-команды локально не выполняются (пункт 11 ТЗ);
 *  - отмена корутины пробрасывается наружу, а не превращается в Error
 *    (пункт 18 ТЗ);
 *  - текст приватного запроса не логируется (пункт 20 ТЗ).
 */
@Singleton
class OnDeviceLocalAi @Inject constructor(
    private val modelManager: LocalModelManager,
    private val promptBuilder: LocalPromptBuilder,
    private val dispatchers: CoroutineDispatchers
) : LocalAi {

    private companion object {
        const val TAG = "LocalAI"

        /**
         * Очень длинный запрос локальной 1B-модели не по силам: контекст 2048
         * токенов, а качество на длинных входах резко падает. Отдаём в облако.
         */
        const val MAX_LOCAL_INPUT_CHARS = 1200
    }

    override suspend fun execute(request: ExecutionRequest): LocalAiResult {
        // ---------------------------------------------------------- guard: web
        // Локальная модель офлайн и не знает актуальных данных.
        if (request.requiresWeb) {
            Log.d(TAG, "unsupported: requiresWeb=true — актуальные данные локально недоступны")
            return LocalAiResult.Unsupported("Local model has no web access")
        }

        // ------------------------------------------------------- guard: device
        // Страховка второго уровня: device-команды сюда доходить не должны —
        // их забирает FastCommandRouter → DEVICE_TOOL. Если запрос всё же
        // помечен как управление устройством, локальная модель за него
        // НЕ берётся, чтобы не сгенерировать "готово, открыл Telegram".
        if (request.requiresDeviceControl) {
            Log.d(TAG, "unsupported: requiresDeviceControl=true — это работа ToolExecutor")
            return LocalAiResult.Unsupported("Local model does not execute device commands")
        }

        if (request.text.isBlank()) {
            return LocalAiResult.Unsupported("Empty request")
        }

        if (request.text.length > MAX_LOCAL_INPUT_CHARS) {
            Log.d(TAG, "unsupported: слишком длинный вход (${request.text.length} символов)")
            return LocalAiResult.Unsupported("Request too long for local model")
        }

        // ------------------------------------------------------ model lifecycle
        val runtime = try {
            modelManager.runtimeOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "model initialization failed", e)
            return LocalAiResult.Error("Model failed to initialize")
        }

        if (runtime == null) {
            // Модель не установлена — это ОЖИДАЕМОЕ состояние (файл ~529 МБ
            // не входит в APK), поэтому Unsupported, а не Error: движок
            // спокойно уйдёт в Cloud AI.
            return when (val state = modelManager.state) {
                is LocalModelState.NotInstalled -> {
                    Log.i(TAG, "unsupported: модель не установлена (${state.expectedPath})")
                    LocalAiResult.Unsupported("Local model is not installed on this device")
                }

                is LocalModelState.Failed -> {
                    Log.w(TAG, "error: инициализация модели провалена (${state.reason})")
                    LocalAiResult.Error("Model failed to initialize: ${state.reason}")
                }

                else -> {
                    Log.w(TAG, "error: runtime недоступен, состояние=$state")
                    LocalAiResult.Error("Local model is unavailable")
                }
            }
        }

        // ------------------------------------------------------------ inference
        val config = GenerationConfig.forRequest(request)
        val prompt = promptBuilder.build(request)

        Log.d(
            TAG,
            "inference started | runtime=${runtime.runtimeId} | source=${request.source} | " +
                "privacy=${request.privacyLevel} | maxTokens=${config.maxTokens}"
        )

        return try {
            // Инференс — на Default (CPU-bound), не на Main.
            val generation = withContext(dispatchers.default) {
                runtime.generate(prompt = prompt, config = config)
            }

            val text = generation.text.trim()
            if (text.isEmpty()) {
                Log.w(TAG, "inference completed, но ответ пуст")
                return LocalAiResult.Error("Local model returned an empty response")
            }

            Log.d(TAG, "inference completed | ${generation.metrics.toLogString()}")
            LocalAiResult.Success(text = text, metrics = generation.metrics)
        } catch (e: CancellationException) {
            // Отмена — не ошибка: пробрасываем, чтобы корутина свернулась штатно.
            Log.d(TAG, "inference cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "inference failed", e)
            LocalAiResult.Error("Local inference failed")
        }
    }
}
