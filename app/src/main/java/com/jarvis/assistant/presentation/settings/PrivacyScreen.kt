package com.jarvis.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.OmnixDivider
import com.jarvis.assistant.presentation.components.OmnixTextButton
import com.jarvis.assistant.presentation.components.OmnixScreenHeader
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * Privacy (§26, §42, §52).
 *
 * The point of this screen is reassurance through specificity: it states what
 * is kept, where it is kept, and what leaves the phone — in sentences a
 * non-technical person can act on. It never mentions providers, endpoints,
 * model names or token counts (§4).
 *
 * Every value shown here is read from real state; nothing is asserted that
 * the app does not actually enforce (§3).
 */
@Composable
fun PrivacyScreen(
    microphoneAllowed: Boolean,
    historyStored: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onManagePermissions: () -> Unit = {},
    onDeleteHistory: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))

        OmnixScreenHeader(
            title = stringResource(R.string.omnix_privacy_title),
            onBack = onBack
        )

        Spacer(Modifier.height(spacing.md))

        OmnixSettingRow(
            title = stringResource(R.string.omnix_privacy_microphone),
            value = stringResource(
                if (microphoneAllowed) {
                    R.string.omnix_privacy_allowed
                } else {
                    R.string.omnix_privacy_not_allowed
                }
            )
        )
        OmnixDivider()

        OmnixSettingRow(
            title = stringResource(R.string.omnix_privacy_voice_history),
            value = stringResource(
                if (historyStored) {
                    R.string.omnix_privacy_stored_on_device
                } else {
                    R.string.omnix_privacy_not_stored
                }
            )
        )
        OmnixDivider()

        // This reflects a real behaviour: the orchestrator asks for consent
        // before sending a request classified as private to the cloud.
        OmnixSettingRow(
            title = stringResource(R.string.omnix_privacy_cloud),
            value = stringResource(R.string.omnix_privacy_cloud_controlled)
        )
        OmnixDivider()

        OmnixSettingRow(
            title = stringResource(R.string.omnix_privacy_permissions),
            value = stringResource(R.string.omnix_privacy_permissions_manage),
            onClick = onManagePermissions
        )

        Spacer(Modifier.height(spacing.xl))

        OmnixTextButton(
            text = stringResource(R.string.omnix_privacy_delete_history),
            onClick = onDeleteHistory
        )

        Spacer(Modifier.height(spacing.colossal))
    }
}
