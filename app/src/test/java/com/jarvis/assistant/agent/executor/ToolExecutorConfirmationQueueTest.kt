package com.jarvis.assistant.agent.executor

import com.jarvis.assistant.agent.capability.FakeCapabilityRegistry
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт аудита #4 (HIGH): race condition в pendingConfirmationCall.
 *
 * Одиночное поле заменено на очередь: новый confirmation-запрос НЕ
 * перезаписывает незавершённый предыдущий, а встаёт в конец очереди.
 */
class ToolExecutorConfirmationQueueTest {

    /** Инструмент с CONFIRMATION_REQUIRED — execute() вернёт requiresConfirmation. */
    private class ConfirmationTool(
        override val toolId: String
    ) : JarvisTool {
        override val description: String = "Требует подтверждения $toolId"
        override val category: ToolCategory = ToolCategory.COMMUNICATION
        override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
        override val parametersSchema: JsonObject = buildJsonObject { }

        override suspend fun execute(arguments: JsonObject): ToolExecutionResult =
            ToolExecutionResult.success("Выполнено $toolId")
    }

    private fun buildExecutor(): ToolExecutor {
        val tools = setOf(
            ConfirmationTool("communication.sms"),
            ConfirmationTool("communication.call")
        )
        val registry = ToolRegistry(tools, ToolDiscoveryEngine(SemanticTextMatcher()))
        return ToolExecutor(registry, ToolPermissionManager(FakeCapabilityRegistry.create()))
    }

    private fun call(toolId: String) = ToolCall(toolId, buildJsonObject { })

    @Test
    fun `confirmation request is enqueued`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")

        val result = executor.execute(c)

        assertEquals(ToolExecutionStatus.REQUIRES_USER_CONFIRMATION, result.status)
        assertEquals(1, executor.pendingConfirmationCount())
        assertEquals(c.callId, executor.peekPendingConfirmation()?.toolCall?.callId)
    }

    @Test
    fun `second confirmation does not overwrite first - both stay in queue`() = runBlocking {
        val executor = buildExecutor()
        val first = call("communication.sms")
        val second = call("communication.call")

        executor.execute(first)
        executor.execute(second)

        // Пункт аудита #4: оба в очереди, первый не потерян.
        assertEquals(2, executor.pendingConfirmationCount())
        assertEquals(first.callId, executor.peekPendingConfirmation()?.toolCall?.callId)
        assertTrue(executor.hasPendingConfirmations())
    }

    @Test
    fun `confirming second call removes only it, first stays`() = runBlocking {
        val executor = buildExecutor()
        val first = call("communication.sms")
        val second = call("communication.call")

        executor.execute(first)
        executor.execute(second)

        // Подтверждаем ВТОРОЙ (тот, что видит UI) — удаляется только он.
        assertTrue(executor.removePendingConfirmation(second))
        assertEquals(1, executor.pendingConfirmationCount())
        assertEquals(first.callId, executor.peekPendingConfirmation()?.toolCall?.callId)
    }

    @Test
    fun `executeWithBypass with valid token removes and executes confirmed call`() = runBlocking {
        val executor = buildExecutor()
        val first = call("communication.sms")
        val second = call("communication.call")

        executor.execute(first)
        executor.execute(second)

        // Пользователь подтвердил второй → bypass с его токеном выполняет и извлекает.
        val token = executor.findPendingConfirmation(second.callId)?.confirmationToken!!
        val result = executor.executeWithBypass(
            call = second,
            confirmationToken = token,
            source = "test"
        )

        assertTrue(result.isSuccess)
        assertEquals(1, executor.pendingConfirmationCount())
        assertEquals(first.callId, executor.peekPendingConfirmation()?.toolCall?.callId)
    }

    @Test
    fun `cancel removes only current call`() = runBlocking {
        val executor = buildExecutor()
        val first = call("communication.sms")
        val second = call("communication.call")

        executor.execute(first)
        executor.execute(second)

        // «Нет» на второй → удаляется только второй.
        assertTrue(executor.removePendingConfirmation(second))
        assertFalse(executor.removePendingConfirmation(second)) // повторно — false
        assertEquals(1, executor.pendingConfirmationCount())
    }

    @Test
    fun `duplicate call is rejected and queue size stays`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")

        val r1 = executor.execute(c)
        val r2 = executor.execute(c) // тот же callId

        assertEquals(ToolExecutionStatus.REQUIRES_USER_CONFIRMATION, r1.status)
        assertEquals(1, executor.pendingConfirmationCount())
        // Дубликат: execute возвращает failure (очередь переполнена/дубликат) — НЕ второй confirmation.
        assertEquals(ToolExecutionStatus.FAILURE, r2.status)
        assertEquals("CONFIRMATION_QUEUE_FULL", r2.error)
    }

    @Test
    fun `queue overflow is rejected with failure`() = runBlocking {
        val executor = buildExecutor()
        // Разные callId — все встают в очередь до лимита.
        repeat(ToolExecutor.MAX_PENDING_CONFIRMATIONS) { _ ->
            val result = executor.execute(call("communication.sms"))
            assertEquals(ToolExecutionStatus.REQUIRES_USER_CONFIRMATION, result.status)
        }
        assertEquals(ToolExecutor.MAX_PENDING_CONFIRMATIONS, executor.pendingConfirmationCount())

        // Сверх лимита — честный отказ.
        val overflow = executor.execute(call("communication.call"))
        assertEquals(ToolExecutionStatus.FAILURE, overflow.status)
        assertEquals("CONFIRMATION_QUEUE_FULL", overflow.error)
    }

    @Test
    fun `clear empties the whole queue`() = runBlocking {
        val executor = buildExecutor()
        executor.execute(call("communication.sms"))
        executor.execute(call("communication.call"))

        executor.clearPendingConfirmation()

        assertEquals(0, executor.pendingConfirmationCount())
        assertNull(executor.peekPendingConfirmation())
        assertFalse(executor.hasPendingConfirmations())
    }

    @Test
    fun `bypass without token is rejected`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")

        // Пункт аудита #5: bypass без токена НЕ выполняется.
        val result = executor.executeWithBypass(
            call = c,
            confirmationToken = null,
            source = "test"
        )

        assertFalse(result.isSuccess)
        assertEquals("CONFIRMATION_TOKEN_INVALID", result.error)
        assertEquals(0, executor.pendingConfirmationCount())
    }

    @Test
    fun `bypass with wrong token is rejected and call stays in queue`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")
        executor.execute(c)

        val result = executor.executeWithBypass(
            call = c,
            confirmationToken = "wrong-token",
            source = "test"
        )

        assertFalse(result.isSuccess)
        assertEquals("CONFIRMATION_TOKEN_INVALID", result.error)
        // Вызов остался в очереди — можно подтвердить правильным токеном.
        assertEquals(1, executor.pendingConfirmationCount())
    }

    @Test
    fun `bypass with valid token executes`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")
        executor.execute(c)

        val token = executor.peekPendingConfirmation()?.confirmationToken!!
        val result = executor.executeWithBypass(call = c, confirmationToken = token, source = "test")

        assertTrue(result.isSuccess)
        assertEquals(0, executor.pendingConfirmationCount())
    }

    @Test
    fun `token is unique per confirmation request`() = runBlocking {
        val executor = buildExecutor()
        val first = call("communication.sms")
        val second = call("communication.call")

        executor.execute(first)
        executor.execute(second)

        val firstToken = executor.findPendingConfirmation(first.callId)!!.confirmationToken
        val secondToken = executor.findPendingConfirmation(second.callId)!!.confirmationToken

        // У разных запросов разные одноразовые токены.
        assertTrue(firstToken.isNotBlank())
        assertTrue(secondToken.isNotBlank())
        assertTrue(firstToken != secondToken)
    }
}
