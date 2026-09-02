package com.jarvis.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.OmnixDivider
import com.jarvis.assistant.presentation.design.OmnixAppearance
import com.jarvis.assistant.presentation.components.OmnixScreenHeader
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * Appearance (§47, §54, §56).
 *
 * Three real settings, all of which the app actually honours: the light/dark
 * choice, night dimming, and a reduced-motion override that can follow the
 * system or force animation off.
 */
@Composable
fun AppearanceScreen(
    appearance: OmnixAppearance,
    nightDimming: Boolean,
    reduceMotionOverride: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAppearanceChange: (OmnixAppearance) -> Unit = {},
    onNightDimmingChange: (Boolean) -> Unit = {},
    onReduceMotionChange: (String) -> Unit = {}
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
            title = stringResource(R.string.omnix_appearance_title),
            onBack = onBack
        )

        Spacer(Modifier.height(spacing.sm))

        Column(modifier = Modifier.selectableGroup()) {
            AppearanceOption(
                label = stringResource(R.string.omnix_appearance_system),
                selected = appearance == OmnixAppearance.System,
                onSelect = { onAppearanceChange(OmnixAppearance.System) }
            )
            AppearanceOption(
                label = stringResource(R.string.omnix_appearance_dark),
                selected = appearance == OmnixAppearance.Dark,
                onSelect = { onAppearanceChange(OmnixAppearance.Dark) }
            )
            AppearanceOption(
                label = stringResource(R.string.omnix_appearance_light),
                selected = appearance == OmnixAppearance.Light,
                onSelect = { onAppearanceChange(OmnixAppearance.Light) }
            )
        }

        OmnixDivider()

        OmnixToggleRow(
            title = stringResource(R.string.omnix_appearance_night),
            subtitle = stringResource(R.string.omnix_appearance_night_body),
            checked = nightDimming,
            onCheckedChange = onNightDimmingChange
        )

        OmnixToggleRow(
            title = stringResource(R.string.omnix_appearance_reduce_motion),
            subtitle = stringResource(R.string.omnix_appearance_reduce_motion_body),
            // "system" defers to the OS; "on" forces motion off in-app.
            checked = reduceMotionOverride == REDUCE_MOTION_ON,
            onCheckedChange = { forced ->
                onReduceMotionChange(if (forced) REDUCE_MOTION_ON else REDUCE_MOTION_SYSTEM)
            }
        )

        Spacer(Modifier.height(spacing.colossal))
    }
}

@Composable
private fun AppearanceOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    OmnixSettingRow(
        title = label,
        value = if (selected) "•" else null,
        onClick = onSelect
    )
}

const val REDUCE_MOTION_SYSTEM = "system"
const val REDUCE_MOTION_ON = "on"

/**
 * Explicitly opts back into motion even when the OS asks to reduce it.
 * Rare, but a user who turned the OS setting on for one bad app should not
 * be locked out of OMNIX's motion forever.
 */
const val REDUCE_MOTION_OFF = "off"
