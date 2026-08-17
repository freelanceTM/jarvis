package com.jarvis.assistant.agent.executor

import android.util.Log
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/** Вызов инструмента, ожидающий подтверждения пользователя (элемент очереди). */
data class PendingConfirmationRequest(
    val toolCall: ToolCall,
    val promptMessage: String,

    /**
     * Одноразовый криптографический токен подтверждения (пункт аудита #5).
     *
     * Генерируется при постановке в очередь и передаётся в UI/голосовой флоу
     * ТОЛЬКО через [peekPendingConfirmation]. [executeWithBypass] выполняет
     * вызов только если токен совпадает — подделать «подтверждение» извне
     * нельзя (вызывающий не знает токен, пока не получил его из очереди).
     */
    val confirmationToken: String = UUID.randomUUID().toString()
)

@Singleton
class ToolExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val permissionManager: ToolPermissionManager
) {
    companion object {
        private const val TAG = "ToolExecutor"

        /** Предел ожидающих подтверждения вызовов — защита от переполнения. */
        const val MAX_PENDING_CONFIRMATIONS = 8
    }

    /**
     * Очередь вызовов, ожидающих подтверждения (пункт аудита #4 — HIGH).
     *
     * Заменяет одиночное поле `pendingConfirmationCall`: новый confirmation-запрос
     * БОЛЬШЕ НЕ перезаписывает незавершённый предыдущий — он встаёт в конец
     * очереди. Подтверждение/отмена извлекают КОНКРЕТНЫЙ вызов (по callId),
     * не трогая остальные.
     *
     * Thread-safe: ConcurrentLinkedQueue.
     */
    private val pendingConfirmations = ConcurrentLinkedQueue<PendingConfirmationRequest>()

    /** @return первый ожидающий подтверждения вызов (голова очереди) или null. */
    fun peekPendingConfirmation(): PendingConfirmationRequest? = pendingConfirmations.peek()

    fun hasPendingConfirmations(): Boolean = !pendingConfirmations.isEmpty()

    /** @return запись по callId (для UI: получить токен не-головного элемента) или null. */
    fun findPendingConfirmation(callId: String): PendingConfirmationRequest? =
        pendingConfirmations.firstOrNull { it.toolCall.callId == callId }

    /** Количество ожидающих подтверждения вызовов (для диагностики). */
    fun pendingConfirmationCount(): Int = pendingConfirmations.size

    /**
     * Добавляет вызов в очередь подтверждений.
     *
     * @return true, если вызов добавлен; false — дубликат или очередь переполнена
     *         (в этом случае вызывающий слой обязан НЕ выдавать запрос на подтверждение).
     */
    private fun enqueuePendingConfirmation(call: ToolCall, prompt: String): Boolean {
        if (pendingConfirmations.any { it.toolCall.callId == call.callId }) {
            Log.w(TAG, "Call ${call.callId} уже ожидает подтверждения — дубликат отклонён")
            return false
        }
        if (pendingConfirmations.size >= MAX_PENDING_CONFIRMATIONS) {
            Log.w(TAG, "Очередь подтверждений переполнена ($MAX_PENDING_CONFIRMATIONS) — вызов ${call.callId} отклонён")
            return false
        }
        pendingConfirmations.add(PendingConfirmationRequest(toolCall = call, promptMessage = prompt))
        Log.d(TAG, "Confirmation enqueued: ${call.toolId} (${call.callId}), queue=${pendingConfirmations.size}")
        return true
    }

    /**
     * Извлекает вызов из очереди подтверждений (подтверждение или отмена).
     *
     * @return true, если вызов был в очереди и извлечён.
     */
    /**
     * Потребляет запись из очереди: ищет вызов по callId И совпадение токена.
     *
     * @return true, если запись найдена с ВАЛИДНЫМ токеном (или токен совпал)
     *         и удалена; false — запись отсутствует или токен не совпал.
     */
    private fun consumePendingConfirmation(call: ToolCall, confirmationToken: String?): Boolean {
        if (confirmationToken == null) return false
        val iterator = pendingConfirmations.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.toolCall.callId == call.callId) {
                return if (request.confirmationToken == confirmationToken) {
                    iterator.remove()
                    true
                } else {
                    Log.w(TAG, "Токен подтверждения НЕ совпал для ${call.toolId} (${call.callId})")
                    false
                }
            }
        }
        return false
    }

    /**
     * Обязательный audit-лог bypass-вызовов (пункт аудита #5).
     * Формат: [AUDIT] timestamp | source | toolId | callId | tokenValid | outcome
     */
    private fun logBypassAudit(source: String, call: ToolCall, tokenValid: Boolean) {
        val outcome = if (tokenValid) "EXECUTED" else "REJECTED"
        Log.i(
            TAG,
            "[AUDIT] ${System.currentTimeMillis()} | source=$source | tool=${call.toolId} | " +
                "callId=${call.callId} | tokenValid=$tokenValid | outcome=$outcome"
        )
    }

    fun removePendingConfirmation(call: ToolCall): Boolean {
        // Итератор ConcurrentLinkedQueue weakly-consistent и поддерживает remove().
        val iterator = pendingConfirmations.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().toolCall.callId == call.callId) {
                iterator.remove()
                return true
            }
        }
        Log.w(TAG, "removePendingConfirmation: call ${call.callId} (${call.toolId}) не найден в очереди")
        return false
    }

    /** Очищает ВСЮ очередь (полный сброс — смена режима, выход из приложения). */
    fun clearPendingConfirmation() {
        val size = pendingConfirmations.size
        pendingConfirmations.clear()
        if (size > 0) Log.d(TAG, "Pending confirmations cleared ($size)")
    }

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
            // Пункт аудита #4: встаём в очередь, а не перезаписываем предыдущий.
            // Если очередь переполнена или вызов уже в ней — честный отказ.
            if (!enqueuePendingConfirmation(call, confirmationPrompt)) {
                return@withContext ToolExecutionResult.failure(
                    summary = "Слишком много действий ожидают подтверждения. Сначала подтвердите или отмените предыдущие, сэр.",
                    error = "CONFIRMATION_QUEUE_FULL"
                )
            }
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
     * Выполняет инструмент БЕЗ повторной проверки PermissionManager (после голосового/UI-подтверждения пользователя)
     *
     * Пункт аудита #5 (HIGH): каждый bypass-вызов
     *  1) фиксируется в audit-логе (timestamp, toolCall, источник, валидность токена);
     *  2) выполняется ТОЛЬКО при совпадении одноразового токена подтверждения,
     *     полученного из очереди через [peekPendingConfirmation].
     *
     * @param confirmationToken одноразовый токен из PendingConfirmationRequest.
     *                          null/неверный → вызов ОТКЛОНЯЕТСЯ (failure).
     * @param source            источник вызова ("voice_orchestrator", "chat_ui", ...).
     */
    suspend fun executeWithBypass(
        call: ToolCall,
        confirmationToken: String?,
        source: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        // 1. Audit-лог: фиксируем ПОПЫТКУ до проверки токена.
        val tokenValid = consumePendingConfirmation(call, confirmationToken)
        logBypassAudit(source = source, call = call, tokenValid = tokenValid)

        // 2. Токен не совпал — отказ. Пункт аудита #5: bypass без реального
        //    подтверждения НЕ выполняется.
        if (!tokenValid) {
            return@withContext ToolExecutionResult.failure(
                summary = "Действие не подтверждено: выполнение без валидного подтверждения запрещено, сэр.",
                error = "CONFIRMATION_TOKEN_INVALID"
            )
        }

        val tool = registry.getTool(call.toolId)
            ?: return@withContext ToolExecutionResult.failure(
                summary = "Инструмент '${call.toolId}' не найден",
                error = "TOOL_NOT_FOUND"
            )

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
