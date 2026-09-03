package com.jarvis.assistant.agent.policy

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Policy Engine безопасности действий.
 *
 * LLM предлагает действие ([ProposedAction]) — решение о риске и подтверждении
 * принимает ТОЛЬКО политика. LLM не имеет канала повлиять на риск-оценку:
 * категория выводится из toolId, риск аргументов — из их содержимого
 * (детектор сумм), происхождение — из источника вызова, статический пол —
 * из объявления автора инструмента.
 *
 * ```
 * AI → Proposed Action → [здесь] → Risk Level → Confirmation?
 *   ├── NO  → Execute (PolicyDecision.Allow)
 *   └── YES → Ask user (PolicyDecision.RequireConfirmation → очередь подтверждений)
 * ```
 *
 * Форсированные правила (нельзя отключить настройками):
 *  - деньги в исходящем сообщении/платёжной операции → подтверждение всегда
 *    («Отправь Ивану 50 000» — обязательное подтверждение);
 *  - PAYMENT-инструменты → подтверждение всегда;
 *  - DELETE-инструменты (memory.forget, wipe) → подтверждение всегда;
 *  - accessibility-запись (type_text/ui_click в чужих приложениях) → всегда;
 *  - AUTOMATION-происхождение + CALL/MESSAGE → подтверждение всегда
 *    (триггер-событие не имеет права звонить/писать самостоятельно, S-3).
 *
 * Настраиваемые правила ([ActionPolicySettings]): политика звонков
 * (ALWAYS / TRUSTED_ONLY / NEVER), политика сообщений (ALWAYS / MONEY_ONLY /
 * NEVER), доверенные контакты.
 */
@Singleton
class ActionPolicyEngine @Inject constructor(
    private val settingsProvider: ActionPolicySettingsProvider
) {

    fun evaluate(action: ProposedAction, tool: JarvisTool): PolicyDecision {
        // ProposedAction нормализуется в ToolCall: детектор сумм и промпты
        // работают с аргументами в едином формате.
        val call = ToolCall(action.toolId, action.arguments)
        val origin = action.origin
        val settings = settingsProvider.current()
        val category = categoryOf(tool.toolId)

        // ---------------------------------------------------------- forced-правила
        when (category) {
            ActionCategory.PAYMENT -> {
                val amount = moneyInArguments(call)
                return PolicyDecision.RequireConfirmation(
                    prompt = paymentPrompt(amount),
                    forced = true,
                    risk = if (amount != null && amount >= MoneyAmountDetector.LARGE_AMOUNT_THRESHOLD) {
                        ActionRiskLevel.CRITICAL
                    } else {
                        ActionRiskLevel.HIGH
                    },
                    rationale = "PAYMENT always requires confirmation (amount=${amount ?: "n/a"})"
                )
            }

            ActionCategory.DELETE -> {
                val target = call.arguments["target"]?.jsonPrimitive?.contentOrNull
                    ?: call.arguments["name"]?.jsonPrimitive?.contentOrNull
                return PolicyDecision.RequireConfirmation(
                    prompt = deletePrompt(target, tool),
                    forced = true,
                    risk = ActionRiskLevel.HIGH,
                    rationale = "DELETE always requires confirmation"
                )
            }

            ActionCategory.ACCESSIBILITY_WRITE -> {
                val preview = call.arguments["text"]?.jsonPrimitive?.contentOrNull?.take(60)
                return PolicyDecision.RequireConfirmation(
                    prompt = if (preview.isNullOrBlank()) {
                        "Выполнить действие в чужом приложении? Подтвердите, сэр."
                    } else {
                        "Ввести в активное поле текст «$preview»? Подтвердите, сэр."
                    },
                    forced = true,
                    risk = ActionRiskLevel.HIGH,
                    rationale = "ACCESSIBILITY_WRITE always requires confirmation"
                )
            }

            ActionCategory.CALL -> {
                val recipient = call.arguments["recipient"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val trusted = TrustedContactMatcher.isTrusted(recipient, settings.trustedContacts)
                val mustConfirm = when (settings.callPolicy) {
                    CallConfirmationPolicy.ALWAYS -> true
                    CallConfirmationPolicy.TRUSTED_ONLY -> !trusted
                    CallConfirmationPolicy.NEVER -> false
                } || origin == ActionOrigin.AUTOMATION
                return if (mustConfirm) {
                    PolicyDecision.RequireConfirmation(
                        prompt = callPrompt(recipient, origin),
                        forced = origin == ActionOrigin.AUTOMATION,
                        risk = if (origin == ActionOrigin.AUTOMATION) ActionRiskLevel.HIGH else ActionRiskLevel.ELEVATED,
                        rationale = "CALL policy=${settings.callPolicy} trusted=$trusted origin=$origin"
                    )
                } else {
                    PolicyDecision.Allow(
                        risk = ActionRiskLevel.LOW,
                        rationale = "CALL policy=${settings.callPolicy} trusted contact"
                    )
                }
            }

            ActionCategory.MESSAGE -> {
                val recipient = call.arguments["recipient"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val message = call.arguments["message"]?.jsonPrimitive?.contentOrNull
                    ?: call.arguments["text"]?.jsonPrimitive?.contentOrNull
                val amount = MoneyAmountDetector.findAmount(message)
                val trusted = TrustedContactMatcher.isTrusted(recipient, settings.trustedContacts)
                val mustConfirm = when (settings.messagingPolicy) {
                    MessagingConfirmationPolicy.ALWAYS -> true
                    MessagingConfirmationPolicy.MONEY_ONLY -> amount != null
                    MessagingConfirmationPolicy.NEVER -> false
                } || amount != null || origin == ActionOrigin.AUTOMATION
                return if (mustConfirm) {
                    PolicyDecision.RequireConfirmation(
                        prompt = messagePrompt(recipient, message, amount, origin),
                        forced = amount != null || origin == ActionOrigin.AUTOMATION,
                        risk = when {
                            amount != null && amount >= MoneyAmountDetector.LARGE_AMOUNT_THRESHOLD ->
                                ActionRiskLevel.CRITICAL
                            amount != null -> ActionRiskLevel.HIGH
                            origin == ActionOrigin.AUTOMATION -> ActionRiskLevel.HIGH
                            else -> ActionRiskLevel.ELEVATED
                        },
                        rationale = "MESSAGE policy=${settings.messagingPolicy} amount=$amount trusted=$trusted origin=$origin"
                    )
                } else {
                    PolicyDecision.Allow(
                        risk = ActionRiskLevel.LOW,
                        rationale = "MESSAGE policy=${settings.messagingPolicy} no money signals"
                    )
                }
            }

            ActionCategory.STATUS_READ,
            ActionCategory.APP_LAUNCH,
            ActionCategory.DEVICE_MUTATION,
            ActionCategory.ACCESSIBILITY_READ,
            ActionCategory.OTHER -> Unit // решает статический пол ниже
        }

        // ------------------------------------------------------- статический пол
        // Политика никогда не понижает риск, объявленный автором инструмента.
        val floor = tool.riskLevel.policyFloor()
        if (floor != null) {
            return PolicyDecision.RequireConfirmation(
                prompt = "Действие «${tool.description}» требует подтверждения. Подтвердить выполнение, сэр?",
                forced = tool.riskLevel == ToolRisk.HIGH || tool.riskLevel == ToolRisk.CRITICAL,
                risk = floor,
                rationale = "Static tool risk ${tool.riskLevel} is a policy floor"
            )
        }

        return PolicyDecision.Allow(
            risk = ActionRiskLevel.LOW,
            rationale = "Category ${category.name} does not require confirmation"
        )
    }

    // ------------------------------------------------------------ helpers

    private fun moneyInArguments(call: ToolCall): Long? =
        call.arguments.values
            .filterIsInstance<JsonPrimitive>()
            .mapNotNull { if (it.isString) it.content else null }
            .firstNotNullOfOrNull { MoneyAmountDetector.findAmount(it) }

    // ------------------------------------------------------------ prompts

    private fun callPrompt(recipient: String, origin: ActionOrigin): String {
        val who = recipient.ifBlank { "контакту" }
        return if (origin == ActionOrigin.AUTOMATION) {
            "Автоматизация пытается позвонить $who. Подтвердите звонок, сэр."
        } else {
            "Позвонить $who? Подтвердите, сэр."
        }
    }

    private fun messagePrompt(
        recipient: String,
        message: String?,
        amount: Long?,
        origin: ActionOrigin
    ): String {
        val who = recipient.ifBlank { "контакту" }
        val prefix = if (origin == ActionOrigin.AUTOMATION) "Автоматизация пытается отправить сообщение" else "Отправить"
        return when {
            amount != null && amount >= MoneyAmountDetector.LARGE_AMOUNT_THRESHOLD ->
                "$prefix $who денежный перевод на ${MoneyAmountDetector.formatAmount(amount)}? Это крупная сумма — подтвердите обязательно, сэр."
            amount != null ->
                "$prefix $who сумму ${MoneyAmountDetector.formatAmount(amount)}? Подтвердите перевод, сэр."
            message.isNullOrBlank() ->
                "$prefix $who сообщение? Подтвердите, сэр."
            else -> {
                val preview = message.take(60)
                "$prefix $who сообщение «$preview»? Подтвердите, сэр."
            }
        }
    }

    private fun paymentPrompt(amount: Long?): String = if (amount != null) {
        "Платёжная операция на ${MoneyAmountDetector.formatAmount(amount)} требует обязательного подтверждения. Подтвердить, сэр?"
    } else {
        "Платёжная операция требует обязательного подтверждения. Подтвердить, сэр?"
    }

    private fun deletePrompt(target: String?, tool: JarvisTool): String {
        val what = target?.takeIf { it.isNotBlank() } ?: tool.description
        return "Удалить «$what»? Действие необратимо — подтвердите, сэр."
    }

    companion object {
        /**
         * Категория по toolId — единственный вход риск-классификации,
         * недоступный для манипуляции со стороны модели (аргументы влияют
         * только на детектор сумм).
         */
        fun categoryOf(toolId: String): ActionCategory = when {
            toolId.startsWith("system.") || toolId.startsWith("health.") ||
                toolId.startsWith("intelligence.weather") || toolId.startsWith("intelligence.vision") ->
                ActionCategory.STATUS_READ

            toolId.startsWith("accessibility.") ->
                if (toolId == "accessibility.screen_reader") ActionCategory.ACCESSIBILITY_READ
                else ActionCategory.ACCESSIBILITY_WRITE

            toolId.contains("pay") || toolId.contains("transfer") -> ActionCategory.PAYMENT

            toolId.contains("forget") || toolId.contains("delete") ||
                toolId.contains("wipe") || toolId.contains("clear") -> ActionCategory.DELETE

            toolId == "communication.call" -> ActionCategory.CALL

            toolId == "communication.sms" || toolId == "communication.telegram" -> ActionCategory.MESSAGE

            toolId.startsWith("communication.") -> ActionCategory.OTHER // contacts, share

            toolId == "device.open_app" -> ActionCategory.APP_LAUNCH

            toolId.startsWith("device.") -> ActionCategory.DEVICE_MUTATION

            toolId.startsWith("media.") -> ActionCategory.DEVICE_MUTATION

            toolId.startsWith("productivity.") -> ActionCategory.OTHER

            toolId.startsWith("intelligence.") -> ActionCategory.OTHER

            toolId.startsWith("memory.") -> ActionCategory.OTHER

            toolId.startsWith("location.") -> ActionCategory.OTHER

            else -> ActionCategory.OTHER
        }
    }
}
