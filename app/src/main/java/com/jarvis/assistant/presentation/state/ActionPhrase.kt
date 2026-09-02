package com.jarvis.assistant.presentation.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R

/**
 * Turns an [ActionSnapshot] into the sentence the user reads (§16, §19, §61).
 *
 * This is the only place where an action becomes words. Because the copy lives
 * in resources and is selected by [ActionType], the UI can never print
 * "EXECUTING", a tool id, or a class name.
 */
object ActionPhrase {

    /** Present-tense phrasing while the action is running. */
    @Composable
    fun of(action: ActionSnapshot): String = when (action.status) {
        ActionStatus.EXECUTING, ActionStatus.PENDING_CONFIRMATION -> executing(action)
        ActionStatus.SUCCEEDED -> succeeded(action)
        ActionStatus.FAILED -> stringResource(R.string.omnix_error_generic_title)
        ActionStatus.CANCELLED -> stringResource(R.string.omnix_result_cancelled)
    }

    @Composable
    private fun executing(action: ActionSnapshot): String {
        val target = action.target
        return when (action.type) {
            ActionType.CALL -> withTarget(
                target, R.string.omnix_action_calling, R.string.omnix_action_calling_generic
            )

            ActionType.MESSAGE -> withTarget(
                target, R.string.omnix_action_messaging, R.string.omnix_action_messaging_generic
            )

            ActionType.NAVIGATION -> withTarget(
                target, R.string.omnix_action_navigation, R.string.omnix_action_navigation_generic
            )

            ActionType.APP -> withTarget(
                target, R.string.omnix_action_app, R.string.omnix_action_app_generic
            )

            ActionType.ALARM -> stringResource(R.string.omnix_action_alarm)
            ActionType.TIMER -> stringResource(R.string.omnix_action_timer)
            ActionType.CALENDAR -> stringResource(R.string.omnix_action_calendar)
            ActionType.WEATHER -> stringResource(R.string.omnix_action_weather)
            ActionType.SEARCH -> stringResource(R.string.omnix_action_search)
            ActionType.TRANSLATE -> stringResource(R.string.omnix_action_translate)
            ActionType.DEVICE_SETTING -> stringResource(R.string.omnix_action_device_setting)
            ActionType.MEDIA -> stringResource(R.string.omnix_action_media)
            ActionType.AUTOMATION -> stringResource(R.string.omnix_action_automation)
            // The honest fallback for a tool without dedicated copy: it says
            // something true and vague rather than leaking the tool name.
            ActionType.Generic -> stringResource(R.string.omnix_state_working)
        }
    }

    /**
     * Past-tense confirmation. The executor's own summary is preferred when it
     * exists, because it carries the real outcome ("Alarm set for 7:00").
     */
    @Composable
    private fun succeeded(action: ActionSnapshot): String {
        action.result?.takeIf { it.isNotBlank() }?.let { return it }
        return when (action.type) {
            ActionType.CALL -> stringResource(R.string.omnix_result_call_started)
            ActionType.MESSAGE -> stringResource(R.string.omnix_result_message_sent)
            else -> stringResource(R.string.omnix_result_done)
        }
    }

    @Composable
    private fun withTarget(target: String?, withArg: Int, generic: Int): String =
        if (target.isNullOrBlank()) stringResource(generic) else stringResource(withArg, target)
}
