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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test
    fun `valid token cannot authorize mutated tool or arguments with same call id`() = runBlocking {
        val executor = buildExecutor()
        val original = ToolCall(
            toolId = "communication.sms",
            arguments = buildJsonObject {
                put("recipient", "alice")
                put("message", "hello")
            }
        )
        executor.execute(original)
        val token = executor.peekPendingConfirmation()!!.confirmationToken

        val changedArguments = original.copy(
            arguments = buildJsonObject {
                put("recipient", "attacker")
                put("message", "send money")
            }
        )
        val changedTool = original.copy(toolId = "communication.call")

        val argsResult = executor.executeWithBypass(changedArguments, token, "test")
        val toolResult = executor.executeWithBypass(changedTool, token, "test")

        assertEquals("CONFIRMATION_TOKEN_INVALID", argsResult.error)
        assertEquals("CONFIRMATION_TOKEN_INVALID", toolResult.error)
        // Неудачные попытки не потребляют исходное подтверждение.
        assertEquals(1, executor.pendingConfirmationCount())
        assertTrue(executor.executeWithBypass(original, token, "test").isSuccess)
    }

    @Test
    fun `one-time token executes at most once under concurrent replay`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")
        executor.execute(c)
        val token = executor.peekPendingConfirmation()!!.confirmationToken

        val results = List(64) {
            async(Dispatchers.Default) {
                executor.executeWithBypass(c, token, "concurrency_test")
            }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(63, results.count { it.error == "CONFIRMATION_TOKEN_INVALID" })
        assertEquals(0, executor.pendingConfirmationCount())
    }

    /**
     * CR-04 (mandatory): при двух параллельных confirmations в очереди
     * подтверждение ВТОРОГО вызова токеном ПЕРВОГО (головы очереди)
     * ДОЛЖНО быть отклонено. Корректный токен второго подтверждает его,
     * первый при этом остаётся в очереди нетронутым.
     */
    @Test
    fun `confirming second call with heads token is rejected - CR04 head-of-queue bug`() = runBlocking {
        val executor = buildExecutor()
        val first = call("communication.sms")
        val second = call("communication.call")

        executor.execute(first)
        executor.execute(second)
        assertEquals(2, executor.pendingConfirmationCount())

        val headToken = executor.peekPendingConfirmation()!!.confirmationToken  // токен ПЕРВОГО
        val secondToken = executor.confirmationTokenFor(second.callId)!!        // токен ВТОРОГО
        assertTrue(headToken != secondToken)

        // 1. Пытаемся подтвердить второй вызов токеном ГОЛОВЫ → должно быть отклонено.
        val wrong = executor.executeWithBypass(second, headToken, "test")
        assertFalse("using head's token must reject second call", wrong.isSuccess)
        assertEquals("CONFIRMATION_TOKEN_INVALID", wrong.error)
        assertEquals(2, executor.pendingConfirmationCount())  // ни один не удалён

        // 2. Подтверждаем второй его собственным токеном — успешно, первый остаётся.
        val ok = executor.executeWithBypass(second, secondToken, "test")
        assertTrue("using own token must execute second call", ok.isSuccess)
        assertEquals(1, executor.pendingConfirmationCount())
        assertEquals(first.callId, executor.peekPendingConfirmation()?.toolCall?.callId)
    }

    @Test
    fun `claimPendingConfirmation reassigns owner without changing token`() = runBlocking {
        val executor = buildExecutor()
        val c = call("communication.sms")
        executor.execute(c)

        val original = executor.findPendingConfirmation(c.callId)!!
        assertEquals(ConfirmationOwner.CHAT_UI, original.owner)

        assertTrue(executor.claimPendingConfirmation(c.callId, ConfirmationOwner.VOICE))
        val claimed = executor.findPendingConfirmation(c.callId)!!
        assertEquals(ConfirmationOwner.VOICE, claimed.owner)
        // Токен НЕ меняется — caller запомнил его до claim.
        assertEquals(original.confirmationToken, claimed.confirmationToken)
    }
}
