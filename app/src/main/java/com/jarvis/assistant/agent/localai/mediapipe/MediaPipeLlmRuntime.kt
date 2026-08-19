@file:Suppress("DEPRECATION")

package com.jarvis.assistant.agent.localai.mediapipe

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.jarvis.assistant.agent.localai.GenerationConfig
import com.jarvis.assistant.agent.localai.InferenceMetrics
import com.jarvis.assistant.agent.localai.LocalGeneration
import com.jarvis.assistant.agent.localai.LocalModelRuntime
import com.jarvis.assistant.agent.localai.LocalModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Фабрика runtime. Вынесена отдельно, чтобы [MediaPipeModelManager] можно было
 * тестировать без нативных библиотек MediaPipe.
 */
interface MediaPipeRuntimeFactory {
    fun create(modelPath: String, spec: LocalModelSpec): LocalModelRuntime
}

/**
 * Реальная фабрика: создаёт [MediaPipeLlmRuntime] поверх нативного движка.
 *
 * `LlmInference.createFromOptions` — тяжёлая блокирующая операция (загрузка
 * ~529 МБ весов), поэтому вызывается только из background-контекста
 * (см. MediaPipeModelManager.initialize).
 */
@Singleton
class DefaultMediaPipeRuntimeFactory @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaPipeRuntimeFactory {

    override fun create(modelPath: String, spec: LocalModelSpec): LocalModelRuntime {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(spec.contextTokens)
            // Backend.DEFAULT: MediaPipe сам выберет GPU при наличии рабочего
            // OpenCL и откатится на CPU. Жёстко фиксировать GPU нельзя —
            // на части устройств инициализация GPU-делегата падает.
            .setPreferredBackend(LlmInference.Backend.DEFAULT)
            .build()

        val engine = LlmInference.createFromOptions(context, options)
        return MediaPipeLlmRuntime(engine)
    }
}

/**
 * Инференс через MediaPipe LLM Inference API (`com.google.mediapipe:tasks-genai`).
 *
 * ВАЖНО про @file:Suppress("DEPRECATION"):
 * начиная с tasks-genai 0.10.33 Google пометил `LlmInference`,
 * `LlmInferenceSession` и `ProgressListener` как @Deprecated — идёт миграция
 * на LiteRT-LM. Но на момент Этапа 2 LiteRT-LM НЕ опубликован на Google Maven
 * как готовая LLM-зависимость (артефакта `litert-lm` там нет), то есть цели
 * миграции пока не существует. API полностью работоспособен, поэтому
 * предупреждение подавлено осознанно и локально — в одном этом файле,
 * чтобы не глушить deprecation во всём проекте (у проекта включён
 * lint warningsAsErrors).
 *
 * TODO(Этап 3+): перейти на LiteRT-LM, когда появится стабильный Maven-артефакт.
 * Менять нужно будет ТОЛЬКО этот файл — контракт LocalModelRuntime не изменится.
 *
 * Поддерживает реальную отмену: MediaPipe предоставляет
 * `LlmInferenceSession.cancelGenerateResponseAsync()`, который вызывается при
 * отмене корутины (пункт 18 ТЗ).
 *
 * Streaming уже задействован внутри (через ProgressListener) — это даёт честный
 * time-to-first-token в метриках. Наружу пока отдаётся собранный текст, но
 * колбэк [onToken] позволит включить потоковый вывод в UI без изменения
 * ExecutionDecisionEngine.
 *
 * Потокобезопасность: сессия создаётся на каждый запрос и закрывается в
 * finally. Нативный движок один и переиспользуется.
 */
class MediaPipeLlmRuntime(
    private val engine: LlmInference
) : LocalModelRuntime, AutoCloseable {

    private companion object {
        const val TAG = "LocalAI"

        /** Грубая оценка: для кириллицы ~2.5 символа на токен. */
        const val CHARS_PER_TOKEN = 2.5f
    }

    override val runtimeId: String = "mediapipe-llm"

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        onToken: ((String) -> Unit)?
    ): LocalGeneration {
        val startedAt = System.currentTimeMillis()
        val firstTokenAt = java.util.concurrent.atomic.AtomicLong(-1L)

        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(config.temperature)
            .setTopK(config.topK)
            .setTopP(config.topP)
            .setRandomSeed(config.randomSeed)
            .build()

        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

        try {
            session.addQueryChunk(prompt)

            val text = suspendCancellableCoroutine { continuation ->
                val builder = StringBuilder()
                val finished = AtomicBoolean(false)

                // Отмена корутины → отменяем нативную генерацию.
                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) {
                        try {
                            session.cancelGenerateResponseAsync()
                            Log.d(TAG, "inference cancelled by caller")
                        } catch (e: Exception) {
                            Log.w(TAG, "cancelGenerateResponseAsync failed", e)
                        }
                    }
                }

                try {
                    session.generateResponseAsync { partial, done ->
                        if (partial != null) {
                            if (firstTokenAt.get() < 0) {
                                firstTokenAt.set(System.currentTimeMillis())
                            }
                            builder.append(partial)
                            onToken?.invoke(partial)
                        }

                        if (done && finished.compareAndSet(false, true)) {
                            continuation.resume(builder.toString())
                        }
                    }
                } catch (e: Throwable) {
                    if (finished.compareAndSet(false, true)) {
                        continuation.resumeWithException(e)
                    }
                }
            }

            val finishedAt = System.currentTimeMillis()
            val latencyMs = finishedAt - startedAt
            val ttft = firstTokenAt.get().let { if (it < 0) -1L else it - startedAt }
            val approxTokens = text.length / CHARS_PER_TOKEN
            val tokensPerSecond = if (latencyMs > 0) approxTokens / (latencyMs / 1000f) else 0f

            return LocalGeneration(
                text = text,
                metrics = InferenceMetrics(
                    promptChars = prompt.length,
                    responseChars = text.length,
                    latencyMs = latencyMs,
                    timeToFirstTokenMs = ttft,
                    approxTokensPerSecond = tokensPerSecond
                )
            )
        } finally {
            try {
                session.close()
            } catch (e: Exception) {
                Log.w(TAG, "session close failed", e)
            }
        }
    }

    override fun close() {
        try {
            engine.close()
        } catch (e: Exception) {
            Log.w(TAG, "engine close failed", e)
        }
    }
}
