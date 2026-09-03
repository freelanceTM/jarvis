package com.jarvis.assistant.agent.core

import android.content.ActivityNotFoundException
import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.model.ToolDefinition
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

enum class ToolCategory(val displayName: String) {
    SYSTEM("Система и статус"),
    DEVICE("Управление устройством"),
    COMMUNICATION("Связь и уведомления"),
    PRODUCTIVITY("Задачи и автоматизация"),
    INTELLIGENCE("Интеллект, память и поиск")
}

/**
 * Единый контракт инструмента Tool Registry 2.0.
 *
 * Каждый tool имеет:
 *
 * ```
 * Tool
 * ├── id            → [toolId]
 * ├── description   → [description]
 * ├── permissions   → [requiredPermissions] (+ capabilityContract у CapabilityAwareTool)
 * ├── execute()     → фаза мутации состояния
 * ├── verify()      → фаза подтверждения read-back'ом
 * ├── timeout       → [executionTimeoutMs] (покрывает execute + verify вместе)
 * └── error mapping → [mapError] (исключение → честный результат)
 * ```
 *
 * Полный путь каждого вызова (обеспечивается ToolExecutor):
 *
 * ```
 * Discovery   (ToolRegistry / ToolDiscoveryEngine)
 *   ↓
 * Selection   (FastCommandRouter / CognitivePlanner / ExecutionDecisionEngine)
 *   ↓
 * Execution   (ToolExecutor: privacy gate → preflight → [execute])
 *   ↓
 * Verification ([verify] — ОБЯЗАТЕЛЬНЫЙ шаг после успешного execute;
 *               инструменты без собственной верификации наследуют pass-through)
 *   ↓
 * Result      (ToolExecutionResult; SUCCESS только после подтверждения системы)
 * ```
 *
 * Доктрина честности: инструмент НЕ имеет права сообщать «готово», пока
 * Android фактически не подтвердил изменение состояния. Никогда
 * `Tool → exception → «Готово»`; только `execute → verify → SUCCESS`
 * или `execute → failure → ERROR`.
 */
interface JarvisTool {
    val toolId: String
    val name: String get() = toolId
    val description: String
    val category: ToolCategory
    val parametersSchema: JsonObject
    val riskLevel: ToolRisk

    /**
     * Runtime-разрешения, необходимые инструменту (контрактный член).
     *
     * Участвует в двух фазах пайплайна: preflight (ToolPermissionManager
     * блокирует выполнение, пока разрешения не выданы) и error mapping
     * ([mapError] превращает SecurityException в PERMISSION_REQUIRED с этим
     * списком). CapabilityAwareTool выводит список из capability-контракта —
     * единого источника истины.
     */
    val requiredPermissions: List<String>
        get() = emptyList()

    val requiresConfirmation: Boolean
        get() = riskLevel == ToolRisk.CONFIRMATION_REQUIRED || riskLevel == ToolRisk.HIGH || riskLevel == ToolRisk.CRITICAL

    val isOffline: Boolean
        get() = true

    /**
     * True when arguments can leave the JARVIS process/device boundary, even if
     * the tool itself does not require network access (share intents, dialer,
     * accessibility text entry, synced calendar, and similar hand-offs).
     */
    val mayDiscloseUserContentExternally: Boolean
        get() = !isOffline

    /** Local-only context the privacy gate must classify before executing. */
    fun externalPrivacyContext(arguments: JsonObject): List<String> = emptyList()

    /**
     * Бюджет ВСЕГО жизненного цикла вызова: execute + verify вместе.
     * ToolExecutor накрывает обе фазы одним withTimeout — верификация
     * не может выйти за пределы бюджета инструмента.
     */
    val executionTimeoutMs: Long
        get() = 4000L

    val supportsParallel: Boolean
        get() = true

    val requiresForeground: Boolean
        get() = false

    fun toDefinition(): ToolDefinition = ToolDefinition(
        toolId = toolId,
        name = name,
        description = description,
        parametersSchema = parametersSchema,
        riskLevel = riskLevel,
        isOffline = isOffline,
        executionTimeoutMs = executionTimeoutMs,
        supportsParallel = supportsParallel,
        requiresForeground = requiresForeground,
        capabilityId = (this as? CapabilityAwareTool)?.capability?.id
    )

    /**
     * Фаза Execution: применить мутацию состояния (или прочитать данные).
     *
     * Для мутирующих инструментов SUCCESS здесь — черновик (draft): финальный
     * вердикт выдаёт [verify] после read-back'а. Разделение фаз даёт единый
     * пайплайн для всех инструментов и тестируемость верификации в изоляции.
     */
    suspend fun execute(arguments: JsonObject): ToolExecutionResult

    /**
     * Фаза Verification: подтвердить draft фактическим состоянием системы.
     *
     * Вызывается ToolExecutor'ом после КАЖДОГО успешного [execute]. Контракт:
     *  - подтверждено → итоговый SUCCESS (summary/data можно уточнить);
     *  - не подтверждено → честный FAILURE (код `*_VERIFY_FAILED`) или
     *    USER_ACTION_REQUIRED (когда проверить может только пользователь);
     *  - исходное состояние для сверки берётся из [draft] (data/rollbackData):
     *    verify не хранит состояния в самом инструменте (supportsParallel).
     *
     * Значение по умолчанию — pass-through: для инструментов, чей результат
     * самодостаточен (чистые чтения) или не верифицируем публичным API
     * (например, запуск приложения: нет API «приложение в фокусе» без
     * специального доступа). Такие инструменты обязаны формулировать summary
     * как сделанное ДЕЙСТВИЕ («Открываю…»), а не как результат («Открыл»).
     */
    suspend fun verify(arguments: JsonObject, draft: ToolExecutionResult): ToolExecutionResult = draft

    /**
     * Единый error mapping: неожиданное исключение → честный результат.
     *
     * Значение по умолчанию покрывает весь реестр:
     *  - CancellationException пробрасывается (structured concurrency);
     *  - SecurityException → PERMISSION_REQUIRED с [requiredPermissions];
     *  - ActivityNotFoundException → USER_ACTION_REQUIRED;
     *  - остальное → FAILURE с классом исключения как кодом.
     *
     * Инструмент может переопределить для специфичных исключений, но НЕ имеет
     * права возвращать SUCCESS из error mapping — «Готово» из исключения
     * запрещено доктриной.
     */
    fun mapError(arguments: JsonObject, error: Throwable): ToolExecutionResult =
        when (error) {
            is CancellationException -> throw error
            is SecurityException -> ToolExecutionResult.permissionRequired(
                summary = "Система отклонила доступ — требуется разрешение для «$name»",
                permissions = requiredPermissions
            )
            is ActivityNotFoundException -> ToolExecutionResult.userActionRequired(
                summary = "Приложение для этого действия не найдено на устройстве, сэр.",
                reason = "ACTIVITY_NOT_FOUND"
            )
            else -> ToolExecutionResult.failure(
                summary = error.localizedMessage ?: "Ошибка выполнения $name",
                error = error.javaClass.simpleName
            )
        }

    /**
     * Транзакционный откат действия в случае ошибки в цепочке сценария (Rollback)
     */
    suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        return false // По умолчанию для необратимых действий (например, опрос времени/батареи)
    }
}

/**
 * Инструмент, который явно объявляет свои требования к устройству и разрешениям.
 *
 * Позволяет агенту ответить на вопрос «могу ли я выполнить это действие на данном
 * устройстве» ДО вызова [JarvisTool.execute] — вместо того, чтобы узнавать об
 * ограничении Android постфактум.
 */
interface CapabilityAwareTool : JarvisTool {
    val capabilityContract: ToolCapabilityContract

    /**
     * Группа Android Capability Layer, к которой относится инструмент
     * (например, [JarvisCapability.Bluetooth] для device.bluetooth).
     * `null`, если инструмент не привязан ни к одному домену слоя.
     */
    val capability: JarvisCapability?
        get() = null

    /** Единый контракт permissions: выводится из capability-контракта. */
    override val requiredPermissions: List<String>
        get() = capabilityContract.requiredPermissions

    override val requiresConfirmation: Boolean
        get() = capabilityContract.confirmationRequired ||
            riskLevel == ToolRisk.CONFIRMATION_REQUIRED ||
            riskLevel == ToolRisk.HIGH ||
            riskLevel == ToolRisk.CRITICAL
}
