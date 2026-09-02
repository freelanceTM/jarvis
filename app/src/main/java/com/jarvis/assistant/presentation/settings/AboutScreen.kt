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
import com.jarvis.assistant.presentation.components.OmnixScreenHeader
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * About (§42).
 *
 * Account and access status in human terms. "OMNIX Access" is the product
 * word for what the codebase calls a licence; the user is never shown the
 * word "licence key", a token, or an expiry timestamp in epoch millis.
 *
 * Anything the build genuinely cannot do yet — self-service renewal — says so
 * plainly instead of offering a button that goes nowhere (§3).
 */
@Composable
fun AboutScreen(
    accessState: AccessDisplayState,
    versionName: String,
    deviceCode: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onManageAccount: () -> Unit = {}
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
            title = stringResource(R.string.omnix_about_title),
            onBack = onBack
        )

        Spacer(Modifier.height(spacing.md))

        OmnixSettingRow(
            title = stringResource(R.string.omnix_about_access),
            value = stringResource(
                when (accessState) {
                    AccessDisplayState.Active -> R.string.omnix_about_access_active
                    AccessDisplayState.Expired -> R.string.omnix_about_access_expired
                    AccessDisplayState.NotConfigured -> R.string.omnix_about_access_not_configured
                }
            ),
            onClick = onManageAccount
        )
        OmnixDivider()

        // Renewal is not implemented in this build; saying so is honest and
        // costs the user nothing. A dead "Renew" button would not be.
        OmnixSettingRow(
            title = stringResource(R.string.omnix_about_renewal),
            value = stringResource(R.string.omnix_about_renewal_unavailable),
            enabled = false
        )
        OmnixDivider()

        OmnixSettingRow(
            title = stringResource(R.string.omnix_about_version),
            value = versionName
        )

        deviceCode?.let { code ->
            OmnixDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_about_device_code),
                value = code
            )
        }

        Spacer(Modifier.height(spacing.colossal))
    }
}

/** Access status, already reduced to what the UI needs to say. */
enum class AccessDisplayState { Active, Expired, NotConfigured }
