package com.jarvis.assistant.agent.safety

import com.jarvis.assistant.agent.capability.CapabilityStatus
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.CapabilityChecker
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.policy.ActionOrigin
import com.jarvis.assistant.agent.policy.ActionPolicyEngine
import com.jarvis.assistant.agent.policy.PolicyDecision
import com.jarvis.assistant.agent.policy.ProposedAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Предварительная проверка инструмента перед выполнением.
 */
sealed interface PreflightVerdict {
    /** Можно выполнять сразу. */
    data object Allowed : PreflightVerdict

    /** Нужно подтверждение пользователя (опасное/необратимое действие). */
    data class ConfirmationRequired(val prompt: String) : PreflightVerdict

    /** Не хватает runtime-разрешений — выполнение бессмысленно. */
    data class PermissionsMissing(val permissions: List<String>, val explanation: String) : PreflightVerdict

    /** Возможность недоступна на устройстве/API-level. */
    data class Unsupported(val reason: String) : PreflightVerdict
}

/**
 * Confirmation & capability gate.
 *
 * Отвечает на два вопроса ДО вызова инструмента:
 *  1. «Могу ли я это сделать на данном устройстве?» — через capability-контракт;
 *  2. «Нужно ли спросить пользователя?» — через уровень опасности.
 *
 * Политика подтверждений (в рамках реального scope приложения):
 *  - LOW    → выполняется молча (громкость, фонарик, запуск приложений, статусы);
 *  - MEDIUM → выполняется, но действие видимо пользователю (звонок открывает
 *             экран вызова, скриншот);
 *  - HIGH   → всегда требует явного подтверждения (SMS от имени пользователя).
 * Финансовых интеграций в проекте нет, поэтому и правил для них здесь нет.
 */
@Singleton
class ToolPermissionManager @Inject constructor(
    private val capabilities: CapabilityChecker,
    // Дефолт для тестов; в DI ActionPolicyEngine — @Singleton с привязанным
    // ActionPolicySettingsProvider (HiltModules).
    private val policyEngine: ActionPolicyEngine = ActionPolicyEngine(
        com.jarvis.assistant.agent.policy.DefaultActionPolicySettingsProvider()
    )
) {

    /**
     * Полная предварительная проверка: capability → разрешения → ПОЛИТИКА.
     *
     * Порядок осознанный: бессмысленно спрашивать подтверждение у действия,
     * которое нельзя выполнить (нет разрешения/возможности). Policy Engine —
     * финальный гейт перед постановкой в очередь подтверждений: LLM предлагает
     * действие, решение о риске и подтверждении принимает [ActionPolicyEngine]
     * (категория по toolId, детектор сумм, происхождение, статический пол
     * риска инструмента).
     */
    fun preflight(
        tool: JarvisTool,
        call: ToolCall,
        origin: ActionOrigin = ActionOrigin.USER_REQUEST
    ): PreflightVerdict {
        if (tool is CapabilityAwareTool) {
            val contract = tool.capabilityContract

            // 1. Возможность вообще существует на устройстве?
            val unsupported = contract.capabilities
                .map { capabilities.statusOf(it) }
                .filterIsInstance<CapabilityStatus.Unsupported>()
            if (unsupported.isNotEmpty() && unsupported.size == contract.capabilities.size) {
                return PreflightVerdict.Unsupported(unsupported.first().reason)
            }

            // 2. Обязательные разрешения. Инструмент может иметь запасной путь
            //    (например, открыть системный UI), поэтому здесь мы блокируем
            //    только когда требование объявлено как обязательное.
            val missing = capabilities.missingPermissions(contract.requiredPermissions)
            if (missing.isNotEmpty() && contract.dangerLevel == DangerLevel.HIGH) {
                return PreflightVerdict.PermissionsMissing(
                    permissions = missing,
                    explanation = "Для «${tool.description}» нужны разрешения: ${missing.joinToString()}"
                )
            }
        }

        // Единый контракт permissions: plain-инструменты (без capability-контракта)
        // тоже объявляют requiredPermissions — preflight блокирует выполнение,
        // пока разрешения не выданы. CapabilityAware-инструменты проверены выше
        // через capabilityContract.
        if (tool !is CapabilityAwareTool && tool.requiredPermissions.isNotEmpty()) {
            val missing = capabilities.missingPermissions(tool.requiredPermissions)
            if (missing.isNotEmpty()) {
                return PreflightVerdict.PermissionsMissing(
                    permissions = missing,
                    explanation = "Для «${tool.description}» нужны разрешения: ${missing.joinToString()}"
                )
            }
        }

        // Политика — финальный гейт: решает подтверждение по категории,
        // аргументам и происхождению; статический пол риска учтён внутри.
        return when (
            val decision = policyEngine.evaluate(
                ProposedAction.of(call, origin),
                tool
            )
        ) {
            is PolicyDecision.RequireConfirmation ->
                PreflightVerdict.ConfirmationRequired(decision.prompt)
            is PolicyDecision.Allow -> PreflightVerdict.Allowed
        }
    }

    /**
     * Проверяет, разрешено ли автоматическое выполнение инструмента без
     * подтверждения пользователя.
     */
    fun isExecutionAllowed(tool: JarvisTool): Boolean = !requiresConfirmation(tool)

    private fun requiresConfirmation(tool: JarvisTool): Boolean {
        if (tool is CapabilityAwareTool && tool.capabilityContract.confirmationRequired) return true
        return when (tool.riskLevel) {
            ToolRisk.SAFE, ToolRisk.LOW -> false
            ToolRisk.CONFIRMATION_REQUIRED, ToolRisk.HIGH, ToolRisk.CRITICAL -> true
        }
    }

}
