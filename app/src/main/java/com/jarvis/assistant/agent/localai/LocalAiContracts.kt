package com.jarvis.assistant.agent.localai

import com.jarvis.assistant.agent.decision.ExecutionRequest

/**
 * Контракты локального слоя (Этап 2).
 *
 * Слои намеренно разделены (пункт 5 ТЗ):
 *
 * ```
 * ExecutionDecisionEngine
 *         ↓
 *      LocalAi            — «стоит ли отвечать локально и что ответить»
 *         ↓
 *  LocalModelRuntime      — «сгенерируй текст по промпту»
 *         ↓
 *  MediaPipe / llama.cpp / ONNX ...
 * ```
 *
 * Благодаря этому конкретный runtime и модель заменяются без изменения
 * ExecutionDecisionEngine.
 */

/**
 * Локальный AI как execution backend.
 *
 * Реализация НЕ выполняет действий на устройстве и не ходит в сеть —
 * только генерация текста (пункты 11 и 12 ТЗ).
 */
interface LocalAi {

    suspend fun execute(request: ExecutionRequest): LocalAiResult
}

/**
 * Слой инференса.
 *
 * [onToken] опционален и добавлен заранее: когда понадобится streaming в UI,
 * его можно включить, не меняя ни [LocalAi], ни ExecutionDecisionEngine
 * (пункт 8 ТЗ).
 */
interface LocalModelRuntime {

    /** Идентификатор runtime для логов («mediapipe-llm», «noop», ...). */
    val runtimeId: String

    /**
     * Генерация ответа.
     *
     * Реализация ОБЯЗАНА уважать отмену корутины: при cancel текущая
     * генерация должна быть остановлена (пункт 18 ТЗ).
     */
    suspend fun generate(
        prompt: String,
        config: GenerationConfig,
        onToken: ((String) -> Unit)? = null
    ): LocalGeneration
}

/**
 * Жизненный цикл модели (пункт 6 ТЗ).
 *
 * Модель грузится ЛЕНИВО (при первом запросе), остаётся в памяти между
 * запросами и может быть выгружена при memory pressure.
 */
interface LocalModelManager {

    val state: LocalModelState

    /** Идемпотентная инициализация: повторные вызовы не грузят модель заново. */
    suspend fun initialize(): LocalModelState

    fun isReady(): Boolean

    /** Возвращает готовый runtime или null, если модель недоступна. */
    suspend fun runtimeOrNull(): LocalModelRuntime?

    /** Освобождает нативные ресурсы (memory pressure, выход из приложения). */
    suspend fun unload()
}

/**
 * Сборка промпта (пункт 10 ТЗ) — отделена от runtime.
 */
interface LocalPromptBuilder {

    fun build(request: ExecutionRequest): String
}
