package com.jarvis.assistant.ai

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message

/**
 * Контракт облачного AI на стороне Android.
 *
 * Этап 3: единственная реализация — [JarvisApiAiClient], которая обращается
 * ТОЛЬКО к JARVIS API. Клиент не знает, какой провайдер (Groq/Gemini/
 * OpenRouter) выполнит запрос, и не хранит их ключи. Выбор модели —
 * исключительно server-side (пункт 29 ТЗ), поэтому в контракте НЕТ
 * параметра выбора модели клиентом.
 *
 * ```
 * AIClient → JARVIS API → AI Router → Provider Manager → Provider
 * ```
 *
 * @param systemPrompt контекст ассистента (Tool Discovery и т. п.).
 *        Сервер ДОПОЛНЯЕТ им свой базовый prompt, а не заменяет.
 */
interface AIClient {
    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String>
}
