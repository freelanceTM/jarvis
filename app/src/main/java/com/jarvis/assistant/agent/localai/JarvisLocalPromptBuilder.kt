package com.jarvis.assistant.agent.localai

import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.RequestSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сборка промпта для локальной модели.
 *
 * Формат — chat-template Gemma 3 (`<start_of_turn>` / `<end_of_turn>`).
 * MediaPipe для Gemma-моделей НЕ подставляет служебные токены сам, поэтому
 * шаблон формируется здесь. Если модель заменят на другую — меняется только
 * этот класс.
 *
 * System prompt намеренно жёстко ограничивает модель (пункты 10-12 ТЗ):
 * она не должна утверждать, что выполнила действие на устройстве, и не должна
 * выдумывать актуальные данные.
 */
@Singleton
class JarvisLocalPromptBuilder @Inject constructor() : LocalPromptBuilder {

    private companion object {
        const val TURN_START = "<start_of_turn>"
        const val TURN_END = "<end_of_turn>"

        val SYSTEM_PROMPT = """
            Ты JARVIS — локальный офлайн AI-ассистент на устройстве пользователя.

            Правила:
            1. Отвечай кратко и по существу: 1-3 предложения. Ответ будет озвучен вслух.
            2. Отвечай на языке пользователя (обычно русский).
            3. Ты НЕ управляешь устройством. Никогда не утверждай, что открыл приложение,
               позвонил, отправил сообщение или изменил настройки — этим занимается
               отдельная система инструментов.
            4. У тебя НЕТ доступа в интернет. Не выдумывай новости, курсы валют, погоду,
               цены и другие актуальные данные. Если вопрос требует свежей информации,
               прямо скажи, что нужен доступ в сеть.
            5. Если не знаешь ответа — честно скажи об этом.
            6. Без markdown, списков и спецсимволов — только обычный текст.
        """.trimIndent()
    }

    override fun build(request: ExecutionRequest): String {
        val styleHint = when (request.source) {
            RequestSource.VOICE -> "Ответ будет озвучен голосом: говори особенно кратко."
            RequestSource.CHAT -> "Ответ отобразится текстом в чате."
        }

        // Контекст (пункт 14 ТЗ): system prompt + retrieval-память + запрос.
        // История диалога локальной модели НЕ отправляется (контекст 2048
        // токенов); из памяти — только retrieved-блок ≤800 символов, поэтому
        // офлайн-модель тоже знает long-term факты («как зовут дочь?»).
        return buildString {
            append(TURN_START).append("user\n")
            append(SYSTEM_PROMPT).append("\n")
            append(styleHint).append("\n")
            val memory = request.memoryContext.trim()
            if (memory.isNotBlank()) {
                append(memory).append("\n\n")
            }
            append(request.text.trim()).append(TURN_END).append("\n")
            append(TURN_START).append("model\n")
        }
    }
}
