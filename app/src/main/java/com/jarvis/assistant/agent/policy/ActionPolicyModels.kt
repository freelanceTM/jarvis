package com.jarvis.assistant.agent.policy

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Кто инициировал действие. Ключевое для безопасности различие:
 * запрос пользователя (голос/чат) против срабатывания автоматизации
 * (правила «когда наушники…», процедурные макросы). Автоматизация НЕ имеет
 * права выполнять коммуникации без подтверждения — иначе триггер-событие
 * (илиprompt injection в данные правила) может позвонить/отправить SMS
 * без ведома пользователя (находка S-3 аудита).
 */
enum class ActionOrigin {
    /** Прямой запрос пользователя: голос или текстовый чат. */
    USER_REQUEST,

    /** Автоматизация: PersonalAutomationEngine, WorkflowExecutor, сценарии. */
    AUTOMATION
}

/** Категория действия по его влиянию на мир (основа риск-политики). */
enum class ActionCategory {
    /** Чтение статусов (время, батарея, сеть) — не мутирует состояние. */
    STATUS_READ,

    /** Запуск приложения. */
    APP_LAUNCH,

    /** Мутация устройства (громкость, яркость, фонарик, DND, BT/Wi-Fi панель). */
    DEVICE_MUTATION,

    /** Исходящий звонок от имени пользователя. */
    CALL,

    /** Исходящее сообщение: SMS/Telegram. */
    MESSAGE,

    /** Платёжные операции. Сегодня в реестре нет, политика готовит место. */
    PAYMENT,

    /** Разрушающие действия: удаление памяти/данных (memory.forget, wipe). */
    DELETE,

    /** Accessibility-запись: ввод текста/клики в чужих приложениях. */
    ACCESSIBILITY_WRITE,

    /** Accessibility-чтение экрана. */
    ACCESSIBILITY_READ,

    /** Всё остальное (память-запись, перевод, поиск и т.п.). */
    OTHER
}

/** Уровень риска, вычисленный политикой (не LLM). */
enum class ActionRiskLevel { NONE, LOW, ELEVATED, HIGH, CRITICAL }

/**
 * Решение Policy Engine. По архитектуре решения принимает ТОЛЬКО политика:
 *
 * ```
 * AI → Proposed Action → Policy Engine → Risk Level → Confirmation?
 *   ├── NO  → Execute
 *   └── YES → Ask user
 * ```
 */
sealed interface PolicyDecision {
    /** Уровень риска, назначенный политикой. */
    val risk: ActionRiskLevel

    /** Обоснование для логов и диагностики (не для озвучки). */
    val rationale: String

    /** Выполнять без подтверждения. */
    data class Allow(
        override val risk: ActionRiskLevel,
        override val rationale: String
    ) : PolicyDecision

    /**
     * Требуется подтверждение пользователя.
     *
     * @param prompt текст вопроса пользователю (озвучивается/показывается как есть)
     * @param forced true — подтверждение НЕЛЬЗЯ отключить настройками
     *        (деньги, удаление, payment, accessibility, автоматизация)
     */
    data class RequireConfirmation(
        val prompt: String,
        val forced: Boolean,
        override val risk: ActionRiskLevel,
        override val rationale: String
    ) : PolicyDecision
}

/**
 * Настройки политики подтверждений (пользовательские, «можно настроить»).
 * Форсированные правила (деньги/удаление/payment/accessibility/автоматизация)
 * от настроек НЕ зависят — их нельзя выключить.
 */
enum class CallConfirmationPolicy {
    /** Всегда спрашивать (дефолт). */
    ALWAYS,

    /** Спрашивать только для контактов вне доверенного списка. */
    TRUSTED_ONLY,

    /** Не спрашивать (только для прямых запросов пользователя). */
    NEVER
}

enum class MessagingConfirmationPolicy {
    /** Всегда спрашивать (дефолт). */
    ALWAYS,

    /** Спрашивать только когда в сообщении обнаружена денежная сумма. */
    MONEY_ONLY,

    /** Не спрашивать (кроме денег — суммы спрашиваются всегда). */
    NEVER
}

data class ActionPolicySettings(
    val callPolicy: CallConfirmationPolicy = CallConfirmationPolicy.ALWAYS,
    val messagingPolicy: MessagingConfirmationPolicy = MessagingConfirmationPolicy.ALWAYS,
    /** Доверенные контакты: имена или номера (сравнение с нормализацией). */
    val trustedContacts: Set<String> = emptySet()
)

/** Источник настроек политики (сейчас — in-memory; DataStore — следующий шаг UI настроек). */
interface ActionPolicySettingsProvider {
    val settings: StateFlow<ActionPolicySettings>
    fun current(): ActionPolicySettings
    suspend fun update(transform: (ActionPolicySettings) -> ActionPolicySettings)
}

/** Вычисленное политикой действие к исполнению через очередь подтверждений. */
data class ProposedAction(
    val toolId: String,
    val arguments: JsonObject,
    val origin: ActionOrigin
) {
    companion object {
        fun of(call: ToolCall, origin: ActionOrigin) = ProposedAction(call.toolId, call.arguments, origin)
    }
}

/** Объявленный автором инструмента риск — пол инструмента, политика его не понижает. */
internal fun ToolRisk.policyFloor(): ActionRiskLevel? = when (this) {
    ToolRisk.SAFE, ToolRisk.LOW -> null
    ToolRisk.CONFIRMATION_REQUIRED -> ActionRiskLevel.ELEVATED
    ToolRisk.HIGH -> ActionRiskLevel.HIGH
    ToolRisk.CRITICAL -> ActionRiskLevel.CRITICAL
}
