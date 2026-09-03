package com.jarvis.assistant.agent.policy

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Контракты Policy Engine: решение о риске и подтверждении принимает
 * ТОЛЬКО политика — по категории toolId, содержимому аргументов
 * (детектор сумм), происхождению и статическому полу риска.
 * LLM не имеет канала повлиять на решение.
 *
 * Ключевые сценарии спецификации:
 *  - «Открой YouTube»               → execute (Allow);
 *  - «Позвони Ивану»                → по настраиваемой политике;
 *  - «Отправь Ивану 50 000»         → обязательное подтверждение (forced).
 */
class ActionPolicyEngineTest {

    private class FakeSettingsProvider(
        initial: ActionPolicySettings = ActionPolicySettings()
    ) : ActionPolicySettingsProvider {
        private val state = MutableStateFlow(initial)
        override val settings: StateFlow<ActionPolicySettings> = state
        override fun current(): ActionPolicySettings = state.value
        override suspend fun update(transform: (ActionPolicySettings) -> ActionPolicySettings) {
            state.value = transform(state.value)
        }
    }

    private class PolicyTestTool(
        override val toolId: String,
        override val riskLevel: ToolRisk = ToolRisk.SAFE
    ) : JarvisTool {
        override val description: String = "test tool $toolId"
        override val category = ToolCategory.SYSTEM
        override val parametersSchema: JsonObject = JsonObject(emptyMap())
        override suspend fun execute(arguments: JsonObject) =
            com.jarvis.assistant.agent.model.ToolExecutionResult.success("ok")
    }

    private fun engine(settings: ActionPolicySettings = ActionPolicySettings()) =
        ActionPolicyEngine(FakeSettingsProvider(settings))

    private fun ActionPolicyEngine.decide(
        tool: JarvisTool,
        toolCall: ToolCall,
        origin: ActionOrigin
    ): PolicyDecision = evaluate(ProposedAction.of(toolCall, origin), tool)

    private fun call(toolId: String, vararg args: Pair<String, String>) = ToolCall(
        toolId,
        buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
    )

    // ------------------------------------------------------------ Allow

    @Test
    fun `open app executes without confirmation`() {
        val decision = engine().decide(PolicyTestTool("device.open_app"), call("device.open_app", "app_name" to "youtube"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.Allow)
        assertEquals(ActionRiskLevel.LOW, decision.risk)
    }

    @Test
    fun `volume mutation executes without confirmation`() {
        val decision = engine().decide(PolicyTestTool("device.volume"), call("device.volume", "action" to "up"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.Allow)
    }

    @Test
    fun `status reads and screen reading execute without confirmation`() {
        val e = engine()
        assertTrue(e.decide(PolicyTestTool("system.get_battery"), call("system.get_battery"), ActionOrigin.USER_REQUEST) is PolicyDecision.Allow)
        assertTrue(e.decide(PolicyTestTool("accessibility.screen_reader"), call("accessibility.screen_reader"), ActionOrigin.USER_REQUEST) is PolicyDecision.Allow)
    }

    // ------------------------------------------------------------ CALL

    @Test
    fun `call requires confirmation by default`() {
        val decision = engine().decide(PolicyTestTool("communication.call", ToolRisk.CONFIRMATION_REQUIRED), call("communication.call", "recipient" to "Иван"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        decision as PolicyDecision.RequireConfirmation
        assertFalse(decision.forced)
        assertTrue(decision.prompt.contains("Иван"))
    }

    @Test
    fun `call to trusted contact executes when policy is trusted only`() = runBlocking {
        val e = engine(ActionPolicySettings(callPolicy = CallConfirmationPolicy.TRUSTED_ONLY, trustedContacts = setOf("+7 999 100-20-30")))
        val decision = e.decide(PolicyTestTool("communication.call", ToolRisk.CONFIRMATION_REQUIRED), call("communication.call", "recipient" to "+79991002030"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.Allow)
    }

    @Test
    fun `call to unknown contact still confirms when policy is trusted only`() {
        val e = engine(ActionPolicySettings(callPolicy = CallConfirmationPolicy.TRUSTED_ONLY, trustedContacts = setOf("Мама")))
        val decision = e.decide(PolicyTestTool("communication.call", ToolRisk.CONFIRMATION_REQUIRED), call("communication.call", "recipient" to "Иван"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
    }

    @Test
    fun `call never policy allows direct user request`() {
        val e = engine(ActionPolicySettings(callPolicy = CallConfirmationPolicy.NEVER))
        val decision = e.decide(PolicyTestTool("communication.call", ToolRisk.CONFIRMATION_REQUIRED), call("communication.call", "recipient" to "Иван"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.Allow)
    }

    @Test
    fun `automation cannot call even with never policy and trusted contact`() {
        val e = engine(ActionPolicySettings(callPolicy = CallConfirmationPolicy.NEVER, trustedContacts = setOf("Иван")))
        val decision = e.decide(PolicyTestTool("communication.call", ToolRisk.CONFIRMATION_REQUIRED), call("communication.call", "recipient" to "Иван"), ActionOrigin.AUTOMATION)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        decision as PolicyDecision.RequireConfirmation
        assertTrue(decision.forced)
        assertTrue(decision.prompt.contains("Автоматизация"))
    }

    // ------------------------------------------------------------ MESSAGE / MONEY

    @Test
    fun `plain message requires confirmation by default`() {
        val decision = engine().decide(PolicyTestTool("communication.sms", ToolRisk.CONFIRMATION_REQUIRED), call("communication.sms", "recipient" to "Иван", "message" to "привет"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
    }

    @Test
    fun `fifty thousand transfer forces confirmation under money only policy`() {
        val e = engine(ActionPolicySettings(messagingPolicy = MessagingConfirmationPolicy.MONEY_ONLY))
        val decision = e.evaluate(
            PolicyTestTool("communication.sms", ToolRisk.CONFIRMATION_REQUIRED),
            call("communication.sms", "recipient" to "Иван", "message" to "Отправь Ивану 50 000"),
            ActionOrigin.USER_REQUEST
        )
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        decision as PolicyDecision.RequireConfirmation
        assertTrue(decision.forced)
        assertEquals(ActionRiskLevel.CRITICAL, decision.risk)
        assertTrue(decision.prompt.contains("50 000"))
    }

    @Test
    fun `money forces confirmation even when messaging policy is never`() {
        val e = engine(ActionPolicySettings(messagingPolicy = MessagingConfirmationPolicy.NEVER))
        val decision = e.evaluate(
            PolicyTestTool("communication.telegram", ToolRisk.CONFIRMATION_REQUIRED),
            call("communication.telegram", "recipient" to "Иван", "message" to "переведу 5000 рублей"),
            ActionOrigin.USER_REQUEST
        )
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        assertTrue((decision as PolicyDecision.RequireConfirmation).forced)
    }

    @Test
    fun `message without money executes under money only policy`() {
        val e = engine(ActionPolicySettings(messagingPolicy = MessagingConfirmationPolicy.MONEY_ONLY))
        val decision = e.evaluate(
            PolicyTestTool("communication.sms", ToolRisk.CONFIRMATION_REQUIRED),
            call("communication.sms", "recipient" to "Иван", "message" to "встречаемся в 6 у входа"),
            ActionOrigin.USER_REQUEST
        )
        assertTrue(decision is PolicyDecision.Allow)
    }

    @Test
    fun `automation cannot message even with never policy`() {
        val e = engine(ActionPolicySettings(messagingPolicy = MessagingConfirmationPolicy.NEVER))
        val decision = e.evaluate(
            PolicyTestTool("communication.sms", ToolRisk.CONFIRMATION_REQUIRED),
            call("communication.sms", "recipient" to "Иван", "message" to "привет"),
            ActionOrigin.AUTOMATION
        )
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        assertTrue((decision as PolicyDecision.RequireConfirmation).forced)
    }

    // ------------------------------------------------------------ PAYMENT / DELETE / ACCESSIBILITY

    @Test
    fun `payment tools always require forced confirmation`() {
        val decision = engine().decide(PolicyTestTool("payment.transfer"), call("payment.transfer", "amount" to "15000"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        decision as PolicyDecision.RequireConfirmation
        assertTrue(decision.forced)
        assertEquals(ActionRiskLevel.CRITICAL, decision.risk)
        assertTrue(decision.prompt.contains("15 000"))
    }

    @Test
    fun `payment without amount still forces confirmation`() {
        val decision = engine().decide(PolicyTestTool("payment.checkout"), call("payment.checkout"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        assertTrue((decision as PolicyDecision.RequireConfirmation).forced)
        assertEquals(ActionRiskLevel.HIGH, decision.risk)
    }

    @Test
    fun `delete actions always require confirmation`() {
        val decision = engine().decide(PolicyTestTool("memory.forget"), call("memory.forget", "target" to "день рождения"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        decision as PolicyDecision.RequireConfirmation
        assertTrue(decision.forced)
        assertTrue(decision.prompt.contains("день рождения"))
    }

    @Test
    fun `accessibility typing forces confirmation but reading does not`() {
        val e = engine()
        val typing = e.decide(PolicyTestTool("accessibility.type_text", ToolRisk.CONFIRMATION_REQUIRED), call("accessibility.type_text", "text" to "привет"), ActionOrigin.USER_REQUEST)
        assertTrue(typing is PolicyDecision.RequireConfirmation)
        assertTrue((typing as PolicyDecision.RequireConfirmation).forced)

        val reading = e.decide(PolicyTestTool("accessibility.screen_reader", ToolRisk.LOW), call("accessibility.screen_reader"), ActionOrigin.USER_REQUEST)
        assertTrue(reading is PolicyDecision.Allow)
    }

    // ------------------------------------------------------------ static floor

    @Test
    fun `static tool risk level is a floor policy cannot lower`() {
        // Категория OTHER с объявленным CRITICAL — подтверждение остаётся.
        val decision = engine().decide(PolicyTestTool("system.danger_custom", ToolRisk.CRITICAL), call("system.danger_custom"), ActionOrigin.USER_REQUEST)
        assertTrue(decision is PolicyDecision.RequireConfirmation)
        assertTrue((decision as PolicyDecision.RequireConfirmation).forced)
    }

    // ------------------------------------------------------------ detector

    @Test
    fun `money detector parses spaces currencies and multipliers`() {
        assertEquals(50_000L, MoneyAmountDetector.findAmount("Отправь Ивану 50 000"))
        assertEquals(50_000L, MoneyAmountDetector.findAmount("переведу 50000 рублей"))
        assertEquals(5_000L, MoneyAmountDetector.findAmount("заплати 5 тыс"))
        assertEquals(150_000L, MoneyAmountDetector.findAmount("скинь 150 тысяч тенге"))
        assertEquals(100L, MoneyAmountDetector.findAmount("цена $100"))
        assertEquals(250L, MoneyAmountDetector.findAmount("€ 250 за билет"))
        assertEquals(2_500_000L, MoneyAmountDetector.findAmount("сумма 2.5 млн тенге"))
    }

    @Test
    fun `money detector ignores time phone numbers and plain digits without context`() {
        assertNull(MoneyAmountDetector.findAmount("встречаемся в 6 у входа"))
        assertNull(MoneyAmountDetector.findAmount("позвони мне в 5"))
        assertNull(MoneyAmountDetector.findAmount("город с населением 50 тысяч жителей"))
        assertNull(MoneyAmountDetector.findAmount("номер 25"))
        assertNull(MoneyAmountDetector.findAmount(""))
        assertNull(MoneyAmountDetector.findAmount(null))
    }

    @Test
    fun `trusted matcher compares phones by last seven digits and names case insensitive`() {
        val contacts = setOf("+7 999 100-20-30", "Мама")
        assertTrue(TrustedContactMatcher.isTrusted("+79991002030", contacts))
        assertTrue(TrustedContactMatcher.isTrusted("8 999 100 20 30", contacts))
        assertTrue(TrustedContactMatcher.isTrusted("мама", contacts))
        assertFalse(TrustedContactMatcher.isTrusted("+7 999 100-20-31", contacts))
        assertFalse(TrustedContactMatcher.isTrusted("Иван", contacts))
        assertFalse(TrustedContactMatcher.isTrusted(null, contacts))
        assertFalse(TrustedContactMatcher.isTrusted("Мама", emptySet()))
    }
}
