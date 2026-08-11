package com.jarvis.assistant.ai

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message

/**
 * Базовый контракт AI-клиента JARVIS.
 * Реализация находится в [UniversalAIClient] с поддержкой OpenRouter, Groq, OpenAI и Direct Gemini.
 */
interface AIClient {
    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>,
        modelOverride: String? = null
    ): Resource<String>
}
