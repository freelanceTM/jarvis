package com.jarvis.assistant.agent.localai

import com.jarvis.assistant.agent.decision.ExecutionRequest
import java.util.Locale

/**
 * Результат работы локальной модели (Этап 2).
 *
 * Три состояния намеренно разделены (пункт 19 ТЗ):
 *  - [Success]     — модель сгенерировала ответ;
 *  - [Unsupported] — модель НЕ БЕРЁТСЯ за запрос (нужен web, модель не
 *                    установлена, запрос — device-команда). Это НЕ ошибка:
 *                    ExecutionDecisionEngine спокойно уходит в Cloud/Agent;
 *  - [Error]       — реальный сбой (инициализация упала, runtime бросил).
 */
sealed class LocalAiResult {

    data class Success(
        val text: String,
        val metrics: InferenceMetrics = InferenceMetrics()
    ) : LocalAiResult()

    data class Unsupported(val reason: String) : LocalAiResult()

    data class Error(val message: String) : LocalAiResult()
}

/**
 * Параметры генерации (пункт 9 ТЗ) — не хардкодятся внутри runtime.
 *
 * Значения по умолчанию рассчитаны на ГОЛОСОВОЙ ассистент: ответ уходит в TTS,
 * поэтому он должен быть коротким. 256 токенов ≈ 2-4 предложения на русском
 * (кириллица токенизируется плотнее латиницы) — это осознанно немного.
 */
data class GenerationConfig(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val randomSeed: Int = 0
) {
    init {
        require(maxTokens in 1..2048) { "maxTokens вне разумного диапазона: $maxTokens" }
        require(temperature.isFinite() && temperature in 0f..2f) {
            "temperature должна быть конечной и в диапазоне 0..2"
        }
        require(topP.isFinite() && topP in 0f..1f) { "topP должен быть в диапазоне 0..1" }
        require(topK in 1..1_000) { "topK должен быть в диапазоне 1..1000" }
    }

    companion object {
        /** Профиль для голоса: максимально короткий ответ. */
        val VOICE = GenerationConfig(maxTokens = 192, temperature = 0.6f)

        /** Профиль для чата: можно чуть длиннее. */
        val CHAT = GenerationConfig(maxTokens = 320, temperature = 0.7f)

        fun forRequest(request: ExecutionRequest): GenerationConfig =
            when (request.source) {
                com.jarvis.assistant.agent.decision.RequestSource.VOICE -> VOICE
                com.jarvis.assistant.agent.decision.RequestSource.CHAT -> CHAT
            }
    }
}

/**
 * Диагностика инференса (пункт 20 ТЗ).
 *
 * Намеренно простая data-структура, а не telemetry-система: значения только
 * логируются и доступны для отладки.
 */
data class InferenceMetrics(
    val promptChars: Int = 0,
    val responseChars: Int = 0,
    val latencyMs: Long = 0L,
    /** Время до первого токена; -1, если streaming не использовался. */
    val timeToFirstTokenMs: Long = -1L,
    val approxTokensPerSecond: Float = 0f
) {
    /** Строка для лога — без содержимого запроса и ответа. */
    fun toLogString(): String = buildString {
        append("latencyMs=").append(latencyMs)
        if (timeToFirstTokenMs >= 0) append(" | ttftMs=").append(timeToFirstTokenMs)
        append(" | promptChars=").append(promptChars)
        append(" | responseChars=").append(responseChars)
        append(" | ~tok/s=").append(String.format(Locale.ROOT, "%.1f", approxTokensPerSecond))
    }
}

/** Результат одной генерации на уровне runtime. */
data class LocalGeneration(
    val text: String,
    val metrics: InferenceMetrics
)

/**
 * Состояние локальной модели — для логов, настроек и честного UI.
 */
sealed class LocalModelState {

    /** Модель ещё не загружалась (lazy init). */
    data object NotInitialized : LocalModelState()

    data object Loading : LocalModelState()

    data class Ready(val modelId: String, val loadTimeMs: Long) : LocalModelState()

    /**
     * Файла модели нет на устройстве. Это ОЖИДАЕМОЕ состояние: модель весит
     * ~529 МБ и не входит в APK (см. docs/LOCAL_AI.md).
     */
    data class NotInstalled(val expectedPath: String) : LocalModelState()

    /** Модель есть, но инициализация упала — это уже ошибка. */
    data class Failed(val reason: String) : LocalModelState()
}

/**
 * Описание модели, с которой работает локальный слой.
 *
 * Вынесено в данные, чтобы заменить модель можно было без правки кода
 * (пункт: «простота дальнейшего обновления модели»).
 */
data class LocalModelSpec(
    val modelId: String,
    val fileName: String,
    val approxSizeMb: Int,
    val contextTokens: Int,
    /** Минимум свободной RAM, при котором вообще есть смысл грузить модель. */
    val minRuntimeMemoryMb: Int
) {
    companion object {
        /**
         * Gemma 3 1B IT, int4 QAT, формат MediaPipe `.task`.
         *
         * Обоснование выбора — docs/LOCAL_AI.md. Кратко: 529 МБ, ~1.1-1.2 ГБ RSS,
         * 47-56 tok/s decode на мобильном GPU/CPU, 140+ языков (русский —
         * штатно поддерживаемый), лицензия Gemma Terms of Use.
         */
        val GEMMA3_1B_IT_INT4 = LocalModelSpec(
            modelId = "gemma3-1b-it-int4",
            fileName = "gemma3-1b-it-int4.task",
            approxSizeMb = 529,
            contextTokens = 2048,
            minRuntimeMemoryMb = 1536
        )
    }
}
