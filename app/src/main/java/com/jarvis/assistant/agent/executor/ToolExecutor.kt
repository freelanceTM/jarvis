package com.jarvis.assistant.agent.executor

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val permissionManager: ToolPermissionManager
) {
    /**
     * Выполняет одиночный вызов инструмента с проверкой безопасности и таймаутом
     */
    suspend fun execute(call: ToolCall): ToolExecutionResult = withContext(Dispatchers.IO) {
        val tool = registry.getTool(call.toolId)
            ?: return@withContext ToolExecutionResult.failure(
                summary = "Инструмент '${call.toolId}' не зарегистрирован в системе",
                error = "TOOL_NOT_FOUND"
            )

        if (!permissionManager.isExecutionAllowed(tool, call)) {
            val confirmationPrompt = permissionManager.buildConfirmationPrompt(tool, call)
            return@withContext ToolExecutionResult.requiresConfirmation(
                message = confirmationPrompt,
                pendingCall = call
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            withTimeout(tool.executionTimeoutMs) {
                val result = tool.execute(call.arguments)
                val duration = System.currentTimeMillis() - startTime
                result.copy(executionTimeMs = duration)
            }
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult.timeout(tool.name, tool.executionTimeoutMs)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ToolExecutionResult.failure(
                summary = e.localizedMessage ?: "Ошибка выполнения ${tool.name}",
                error = e.javaClass.simpleName,
                executionTimeMs = duration
            )
        }
    }

    /**
     * Выполняет цепочку инструментов с поддержкой параллелизма и транзакционного отката (Rollback)
     */
    suspend fun executeAll(calls: List<ToolCall>): List<ToolExecutionResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ToolExecutionResult>()
        val executedHistory = mutableListOf<Pair<JarvisTool, ToolExecutionResult>>()

        for (call in calls) {
            val tool = registry.getTool(call.toolId)
            val result = execute(call)
            results.add(result)

            if (result.isSuccess && tool != null) {
                executedHistory.add(tool to result)
            } else if (!result.isSuccess) {
                // Если шаг упал с ошибкой -> запускаем транзакционный откат (Rollback)
                performRollback(executedHistory)
                break
            }

            if (result.status == ToolExecutionStatus.REQUIRES_USER_CONFIRMATION) {
                break
            }
        }

        results
    }

    /**
     * Транзакционный откат выполненных действий в обратном порядке при сбое
     */
    private suspend fun performRollback(executedHistory: List<Pair<JarvisTool, ToolExecutionResult>>) {
        for (i in executedHistory.indices.reversed()) {
            val (tool, result) = executedHistory[i]
            if (result.rollbackData != null) {
                try {
                    tool.rollback(result.data ?: kotlinx.serialization.json.JsonObject(emptyMap()), result.rollbackData)
                } catch (_: Exception) { }
            }
        }
    }
}
