package com.jarvis.assistant.agent.safety

import com.jarvis.assistant.agent.capability.CapabilityStatus
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.CapabilityChecker
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    private val capabilities: CapabilityChecker
) {

    /**
     * Полная предварительная проверка: capability + разрешения + подтверждение.
     */
    fun preflight(tool: JarvisTool, call: ToolCall): PreflightVerdict {
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

        return if (requiresConfirmation(tool)) {
            PreflightVerdict.ConfirmationRequired(buildConfirmationPrompt(tool, call))
        } else {
            PreflightVerdict.Allowed
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

    /**
     * Формирует понятный текст запроса подтверждения для пользователя
     */
    fun buildConfirmationPrompt(tool: JarvisTool, call: ToolCall): String {
        return when (tool.toolId) {
            "communication.call" -> {
                val recipient = call.arguments["recipient"]?.jsonPrimitive?.contentOrNull ?: "контакту"
                "Вы подтверждаете звонок для $recipient, сэр?"
            }
            "communication.sms" -> {
                val recipient = call.arguments["recipient"]?.jsonPrimitive?.contentOrNull ?: "контакту"
                val preview = call.arguments["message"]?.jsonPrimitive?.contentOrNull?.take(60)
                if (preview.isNullOrBlank()) {
                    "Вы подтверждаете отправку SMS для $recipient, сэр?"
                } else {
                    "Отправить $recipient сообщение «$preview»? Подтвердите, сэр."
                }
            }
            "accessibility.type_text" -> {
                val preview = call.arguments["text"]?.jsonPrimitive?.contentOrNull?.take(60).orEmpty()
                "Ввести в активное поле текст «$preview»? Подтвердите, сэр."
            }
            else -> "Действие «${tool.description}» требует подтверждения. Подтвердить выполнение, сэр?"
        }
    }
}
