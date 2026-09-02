package com.jarvis.assistant.presentation.state

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps real tool calls and their results onto the user-facing [ActionSnapshot]
 * (§16, §61).
 *
 * The frontend does not decide what happened — it only translates what the
 * executor reports. Every tool id below exists in the registry; anything else
 * degrades to [ActionType.Generic] rather than leaking an identifier.
 */
object ActionMapper {

    /** Registry tool ids → the phrasing family the UI can use. */
    private val TYPE_BY_TOOL: Map<String, ActionType> = mapOf(
        "communication.call" to ActionType.CALL,
        "communication.sms" to ActionType.MESSAGE,
        "communication.share" to ActionType.MESSAGE,
        "communication.contacts" to ActionType.Generic,
        "productivity.alarm_timer" to ActionType.ALARM,
        "productivity.calendar" to ActionType.CALENDAR,
        "productivity.create_automation" to ActionType.AUTOMATION,
        "productivity.ear_briefing" to ActionType.Generic,
        "location.navigation" to ActionType.NAVIGATION,
        "intelligence.weather" to ActionType.WEATHER,
        "intelligence.translate" to ActionType.TRANSLATE,
        "device.open_app" to ActionType.APP,
        "device.volume" to ActionType.DEVICE_SETTING,
        "device.brightness" to ActionType.DEVICE_SETTING,
        "device.flashlight" to ActionType.DEVICE_SETTING,
        "device.bluetooth" to ActionType.DEVICE_SETTING,
        "device.wifi" to ActionType.DEVICE_SETTING,
        "device.dnd" to ActionType.DEVICE_SETTING,
        "device.screenshot" to ActionType.DEVICE_SETTING,
        "media.control" to ActionType.MEDIA
    )

    /**
     * Argument keys that carry the human target of an action, in priority
     * order. Only real arguments are used — the UI never fabricates a name.
     */
    private val TARGET_KEYS = listOf(
        "contact_name", "contactName", "recipient", "name", "contact",
        "app_name", "appName", "package_label",
        "destination", "location", "city", "query",
        "time", "label", "title"
    )

    fun typeOf(toolId: String): ActionType = TYPE_BY_TOOL[toolId] ?: ActionType.Generic

    /** Extracts the human target from the tool arguments, or null. */
    fun targetOf(call: ToolCall): String? {
        for (key in TARGET_KEYS) {
            val element = call.arguments[key] ?: continue
            val primitive = (element as? JsonPrimitive) ?: runCatching {
                element.jsonPrimitive
            }.getOrNull() ?: continue
            val value = primitive.content.trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    /** Snapshot for a call that is about to run or is running now. */
    fun executing(call: ToolCall): ActionSnapshot = ActionSnapshot(
        type = typeOf(call.toolId),
        status = ActionStatus.EXECUTING,
        toolName = call.toolId,
        target = targetOf(call)
    )

    /** Snapshot for a call waiting for the user's decision (§17). */
    fun pendingConfirmation(call: ToolCall): ActionSnapshot = ActionSnapshot(
        type = typeOf(call.toolId),
        status = ActionStatus.PENDING_CONFIRMATION,
        toolName = call.toolId,
        target = targetOf(call),
        confirmationRequired = true
    )

    /** Snapshot after the executor returned a real result. */
    fun completed(call: ToolCall, result: ToolExecutionResult): ActionSnapshot {
        val base = ActionSnapshot(
            type = typeOf(call.toolId),
            status = ActionStatus.SUCCEEDED,
            toolName = call.toolId,
            target = targetOf(call)
        )
        return when (result.status) {
            ToolExecutionStatus.SUCCESS ->
                base.copy(result = result.summary.takeIf { it.isNotBlank() })

            ToolExecutionStatus.CANCELLED ->
                base.copy(status = ActionStatus.CANCELLED)

            ToolExecutionStatus.PERMISSION_REQUIRED ->
                base.copy(
                    status = ActionStatus.FAILED,
                    error = SystemStateType.PERMISSION_REQUIRED
                )

            ToolExecutionStatus.USER_ACTION_REQUIRED,
            ToolExecutionStatus.REQUIRES_SYSTEM_PANEL ->
                base.copy(
                    status = ActionStatus.FAILED,
                    error = SystemStateType.USER_ACTION_REQUIRED
                )

            ToolExecutionStatus.UNSUPPORTED ->
                base.copy(
                    status = ActionStatus.FAILED,
                    error = SystemStateType.CAPABILITY_UNAVAILABLE
                )

            ToolExecutionStatus.REQUIRES_USER_CONFIRMATION ->
                base.copy(
                    status = ActionStatus.PENDING_CONFIRMATION,
                    confirmationRequired = true
                )

            ToolExecutionStatus.TIMEOUT ->
                base.copy(
                    status = ActionStatus.FAILED,
                    error = SystemStateType.SERVICE_UNREACHABLE
                )

            ToolExecutionStatus.FAILURE ->
                base.copy(
                    status = ActionStatus.FAILED,
                    error = SystemStateType.ACTION_FAILED
                )
        }
    }
}
