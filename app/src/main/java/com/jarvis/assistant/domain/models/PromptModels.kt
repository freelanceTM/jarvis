package com.jarvis.assistant.domain.models

import com.jarvis.assistant.agent.model.ToolCall

/**
 * Типобезопасный результат обработки запроса в JARVIS Core
 */
sealed interface PromptExecutionResult {
    data class DirectAnswer(
        val text: String
    ) : PromptExecutionResult

    data class ConfirmationRequired(
        val toolCall: ToolCall,
        val promptMessage: String
    ) : PromptExecutionResult
}
