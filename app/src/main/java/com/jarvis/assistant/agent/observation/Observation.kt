package com.jarvis.assistant.agent.observation

import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import kotlinx.serialization.json.JsonObject

/**
 * Что агенту делать дальше по результатам шага.
 */
enum class NextActionHint {
    /** Всё в порядке — переходим к следующему шагу плана. */
    CONTINUE,

    /** Цель шага уже достигнута, повторять действие не нужно. */
    GOAL_SATISFIED,

    /** Нужен другой инструмент/подход — имеет смысл перепланировать. */
    REPLAN,

    /** Нужно запросить у пользователя разрешение и повторить. */
    REQUEST_PERMISSION,

    /** Требуется действие пользователя в системном UI; повтор бессмысленен. */
    AWAIT_USER_ACTION,

    /** Возможность недоступна на устройстве — перепланирование не поможет. */
    ABORT_UNSUPPORTED,

    /** Нужно подтверждение пользователя перед выполнением. */
    AWAIT_CONFIRMATION
}

/**
 * Наблюдение — единая структура, в которую сворачивается [ToolExecutionResult]
 * для принятия решения агентом:
 *
 *   ToolResult → Observation { success, stateChanged, data, error, nextActionHint }
 *
 * Ключевое различие, которого не было раньше: **success ≠ stateChanged**.
 * Инструмент может честно отработать (например, сообщить, что Bluetooth
 * переключается только пользователем), но состояние устройства при этом не
 * изменилось — агент не должен считать цель достигнутой.
 */
data class Observation(
    val toolId: String,
    val success: Boolean,
    val stateChanged: Boolean,
    val summary: String,
    val data: JsonObject? = null,
    val error: String? = null,
    val nextActionHint: NextActionHint,
    val missingPermissions: List<String> = emptyList()
) {
    val requiresUserInvolvement: Boolean
        get() = nextActionHint == NextActionHint.REQUEST_PERMISSION ||
            nextActionHint == NextActionHint.AWAIT_USER_ACTION ||
            nextActionHint == NextActionHint.AWAIT_CONFIRMATION

    /** Есть ли смысл в перепланировании после этого наблюдения. */
    val isReplanWorthwhile: Boolean
        get() = nextActionHint == NextActionHint.REPLAN

    companion object {
        /**
         * Преобразование результата инструмента в наблюдение.
         * Единственное место, где статусы Android-ограничений превращаются
         * в решения агента.
         */
        fun from(toolId: String, result: ToolExecutionResult): Observation = when (result.status) {
            ToolExecutionStatus.SUCCESS -> Observation(
                toolId = toolId,
                success = true,
                // Инструменты чтения (battery/time/status) не меняют состояние,
                // но у них нет отдельного статуса — считаем изменение состоявшимся
                // только если инструмент не пометил результат как требующий пользователя.
                stateChanged = !result.actionRequiresUser,
                summary = result.summary,
                data = result.data,
                nextActionHint = NextActionHint.CONTINUE
            )

            ToolExecutionStatus.PERMISSION_REQUIRED -> Observation(
                toolId = toolId,
                success = false,
                stateChanged = false,
                summary = result.summary,
                data = result.data,
                error = result.error,
                nextActionHint = NextActionHint.REQUEST_PERMISSION,
                missingPermissions = result.missingPermissions
            )

            ToolExecutionStatus.USER_ACTION_REQUIRED, ToolExecutionStatus.REQUIRES_SYSTEM_PANEL -> Observation(
                toolId = toolId,
                success = false,
                stateChanged = false,
                summary = result.summary,
                data = result.data,
                error = result.error,
                nextActionHint = NextActionHint.AWAIT_USER_ACTION
            )

            ToolExecutionStatus.UNSUPPORTED -> Observation(
                toolId = toolId,
                success = false,
                stateChanged = false,
                summary = result.summary,
                data = result.data,
                error = result.error,
                nextActionHint = NextActionHint.ABORT_UNSUPPORTED
            )

            ToolExecutionStatus.REQUIRES_USER_CONFIRMATION -> Observation(
                toolId = toolId,
                success = false,
                stateChanged = false,
                summary = result.summary,
                data = result.data,
                error = result.error,
                nextActionHint = NextActionHint.AWAIT_CONFIRMATION
            )

            ToolExecutionStatus.FAILURE, ToolExecutionStatus.TIMEOUT -> Observation(
                toolId = toolId,
                success = false,
                stateChanged = false,
                summary = result.summary,
                data = result.data,
                error = result.error,
                nextActionHint = NextActionHint.REPLAN
            )

            ToolExecutionStatus.CANCELLED -> Observation(
                toolId = toolId,
                success = false,
                stateChanged = false,
                summary = result.summary,
                data = result.data,
                error = result.error,
                nextActionHint = NextActionHint.ABORT_UNSUPPORTED
            )
        }
    }
}
