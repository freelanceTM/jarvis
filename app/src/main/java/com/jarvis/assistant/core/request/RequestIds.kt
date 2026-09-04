package com.jarvis.assistant.core.request

import java.security.SecureRandom
import java.util.Random

/**
 * OBSERVABILITY: единый request ID на весь путь запроса
 *
 * ```
 * Voice → Router → Tool → AI → Server → Provider
 * ```
 *
 * Формат: `omx_` + ULID (26 символов Crockford Base32: 48 бит
 * millisecond-timestamp + 80 бит случайности). Пример: `omx_01J8XK3…`.
 *
 * Свойства:
 *  - лексикографическая сортировка = хронологическая (фиксированная ширина,
 *    big-endian время) — удобно в логах и `ORDER BY request_id`;
 *  - уникальность без координации (80 случайных бит на миллисекунду);
 *  - длина 30 символов — укладывается в серверный лимит 64
 *    (`JarvisApiHandler`: клиентский id принимается только ≤ 64 символов).
 *
 * Идентификатор генерируется ОДИН РАЗ на пользовательский запрос
 * (`SendPromptUseCase` / `VoiceInteractionOrchestrator.processUserQuery`) и
 * протаскивается через [com.jarvis.assistant.agent.decision.ExecutionRequest.requestId]
 * → Router/Tool логи → `JarvisApiClient.execute` → сервер пишет его в
 * `ai_usage_records.request_id` (UNIQUE с client_id — заодно идемпотентность
 * ретраев) и возвращает эхом в ответе.
 */
object RequestIds {

    /** Префикс продуктового request id. */
    const val PREFIX = "omx_"

    /** Crockford Base32: без I, L, O, U (не путаются при чтении с экрана). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** ULID: 10 символов на 48-битное время + 16 символов на 80 бит случайности. */
    private const val TIME_CHARS = 10
    private const val RANDOM_CHARS = 16

    /** Полная длина id: omx_ + 26 символов = 30. */
    const val LENGTH = PREFIX.length + TIME_CHARS + RANDOM_CHARS

    private val rng = SecureRandom()

    /**
     * Новый id. [nowMs] и [random] инжектируются для детерминированных тестов.
     */
    fun newId(nowMs: Long = System.currentTimeMillis(), random: Random = rng): String {
        val sb = StringBuilder(LENGTH)
        sb.append(PREFIX)

        // 48 бит времени, старшие символы вперёд.
        var t = nowMs and ((1L shl 48) - 1)
        for (i in TIME_CHARS - 1 downTo 0) {
            sb.append(ALPHABET[((t ushr (i * 5)) and 0x1fL).toInt()])
        }

        // 80 бит случайности (10 байт) → 16 символов.
        val rnd = ByteArray(10)
        random.nextBytes(rnd)
        for (i in 0 until RANDOM_CHARS) {
            val bitIndex = i * 5
            val byteIdx = bitIndex / 8
            val off = bitIndex % 8
            val window = ((rnd[byteIdx].toLong() and 0xff) shl 8) or
                (if (byteIdx + 1 < rnd.size) (rnd[byteIdx + 1].toLong() and 0xff) else 0L)
            sb.append(ALPHABET[((window ushr (11 - off)) and 0x1fL).toInt()])
        }
        return sb.toString()
    }

    /** Похоже ли на наш id (мягкая проверка для логов/метрик, не безопасность). */
    fun looksLikeOmnixId(value: String): Boolean =
        value.length == LENGTH && value.startsWith(PREFIX) &&
            value.substring(PREFIX.length).all { it in ALPHABET }
}
