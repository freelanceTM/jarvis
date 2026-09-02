package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.state.SystemStateType

/**
 * The **one** presentation of every system condition (§18, §48, §51).
 *
 * A screen never designs its own error, empty state or permission prompt: it
 * hands a [SystemStateType] (or an [OmnixSystemMessage]) to this component,
 * which always answers three questions — WHAT, WHY, NEXT ACTION (§50).
 *
 * A normal user never sees `DEVICE_DISCONNECTED`, `HTTP 503`, `ToolException`
 * or `TimeoutException`.
 */
@Immutable
data class OmnixSystemMessage(
    val title: String,
    val description: String?,
    val actionLabel: String?
)

/** Resolves a [SystemStateType] into human copy for the current locale. */
@Composable
fun rememberSystemMessage(type: SystemStateType): OmnixSystemMessage = when (type) {
    SystemStateType.CLIP_DISCONNECTED -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_clip_title),
        stringResource(R.string.omnix_error_clip_body),
        stringResource(R.string.omnix_error_clip_action)
    )

    SystemStateType.BLUETOOTH_OFF -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_bt_off_title),
        stringResource(R.string.omnix_error_bt_off_body),
        stringResource(R.string.omnix_error_bt_off_action)
    )

    SystemStateType.MICROPHONE_DENIED -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_mic_title),
        stringResource(R.string.omnix_error_mic_body),
        stringResource(R.string.omnix_error_mic_action)
    )

    SystemStateType.MICROPHONE_UNAVAILABLE -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_mic_unavailable_title),
        stringResource(R.string.omnix_error_mic_unavailable_body),
        null
    )

    SystemStateType.NETWORK_UNAVAILABLE,
    SystemStateType.SERVICE_UNREACHABLE -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_network_title),
        stringResource(R.string.omnix_error_network_body),
        stringResource(R.string.omnix_error_network_action)
    )

    SystemStateType.USER_ACTION_REQUIRED -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_user_action_title),
        stringResource(R.string.omnix_error_user_action_body),
        stringResource(R.string.omnix_error_user_action_action)
    )

    SystemStateType.NOT_UNDERSTOOD -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_not_understood_title),
        stringResource(R.string.omnix_error_not_understood_body),
        null
    )

    SystemStateType.ACTION_FAILED -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_generic_title),
        stringResource(R.string.omnix_error_generic_body),
        stringResource(R.string.omnix_error_generic_action)
    )

    SystemStateType.PERMISSION_REQUIRED -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_permission_title),
        stringResource(R.string.omnix_error_permission_body),
        stringResource(R.string.omnix_error_permission_action)
    )

    SystemStateType.ACCESS_EXPIRED -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_access_expired_title),
        stringResource(R.string.omnix_error_access_expired_body),
        stringResource(R.string.omnix_error_access_expired_action)
    )

    SystemStateType.CAPABILITY_UNAVAILABLE -> OmnixSystemMessage(
        stringResource(R.string.omnix_error_unavailable_title),
        stringResource(R.string.omnix_error_unavailable_body),
        null
    )
}

/**
 * Renders a system condition inline — used on Home under the Core, inside
 * sheets and as an empty state. There is no icon, no illustration and no red
 * banner: the Core already carries the state colour (§22).
 */
@Composable
fun SystemStateView(
    type: SystemStateType,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null
) {
    val message = rememberSystemMessage(type)
    SystemStateView(
        message = message,
        modifier = modifier,
        onAction = onAction
    )
}

/**
 * Renders an already-resolved message — used by empty states, which describe
 * context rather than failure (§19, §51).
 */
@Composable
fun SystemStateView(
    message: OmnixSystemMessage,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null
) {
    val spacing = OmnixTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Text(
            text = message.title,
            style = OmnixTheme.typography.heading,
            color = OmnixTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )
        message.description?.let { description ->
            Text(
                text = description,
                style = OmnixTheme.typography.caption,
                color = OmnixTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        if (message.actionLabel != null && onAction != null) {
            OmnixPrimaryButton(
                text = message.actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = spacing.xs)
            )
        }
    }
}

/**
 * Empty state (§19, §51): context, reason and the next action — never a bare
 * "No data".
 */
@Composable
fun OmnixEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    SystemStateView(
        message = OmnixSystemMessage(title, description, actionLabel),
        modifier = modifier,
        onAction = onAction
    )
}
