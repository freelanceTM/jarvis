package com.jarvis.assistant.agent.executor

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val permissionManager: ToolPermissionManager
) {
    /**
     * Выполняет одиночный вызов инструмента с проверкой безопасности
     */
    suspend fun execute(call: ToolCall): ToolResult {
        val tool = registry.getTool(call.name)
            ?: return ToolResult.Error(
                message = "Инструмент '${call.name}' не найден в реестре JARVIS",
                code = "TOOL_NOT_FOUND"
            )

        if (!permissionManager.isExecutionAllowed(tool, call)) {
            val confirmationPrompt = permissionManager.buildConfirmationPrompt(tool, call)
            return ToolResult.RequiresConfirmation(
                message = confirmationPrompt,
                pendingCall = call
            )
        }

        return registry.execute(call.name, call.arguments)
    }

    /**
     * Выполняет цепочку инструментов последовательно (Multi-tool execution)
     */
    suspend fun executeAll(calls: List<ToolCall>): List<ToolResult> {
        val results = mutableListOf<ToolResult>()
        for (call in calls) {
            val result = execute(call)
            results.add(result)
            // Если промежуточный шаг требует подтверждения, прерываем цепочку
            if (result is ToolResult.RequiresConfirmation) {
                break
            }
        }
        return results
    }
}
