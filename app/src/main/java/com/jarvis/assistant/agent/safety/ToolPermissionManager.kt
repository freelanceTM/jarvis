package com.jarvis.assistant.agent.safety

import android.content.Context
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Проверяет, разрешено ли автоматическое выполнение инструмента
     */
    fun isExecutionAllowed(tool: JarvisTool, call: ToolCall): Boolean {
        return when (tool.riskLevel) {
            ToolRisk.SAFE, ToolRisk.LOW -> true
            ToolRisk.CONFIRMATION_REQUIRED, ToolRisk.HIGH, ToolRisk.CRITICAL -> false
        }
    }

    /**
     * Формирует понятный текст запроса подтверждения для пользователя
     */
    fun buildConfirmationPrompt(tool: JarvisTool, call: ToolCall): String {
        return "Внимание: действие '${tool.description}' требует подтверждения. Подтвердить выполнение, сэр?"
    }
}
