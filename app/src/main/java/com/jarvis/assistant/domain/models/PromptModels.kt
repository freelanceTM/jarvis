package com.jarvis.assistant.domain.models

import com.jarvis.assistant.agent.model.ToolCall

/**
 * Типобезопасный результат обработки запроса в JARVIS Core
 */
sealed interface PromptExecutionResult {
    data class DirectAnswer(
        val text: String,
        /** Accessibility Lockdown: текст прочитан с экрана — в историю идёт placeholder. */
        val containsScreenContent: Boolean = false
    ) : PromptExecutionResult

    data class ConfirmationRequired(
        val toolCall: ToolCall,
        val promptMessage: String
    ) : PromptExecutionResult
}
