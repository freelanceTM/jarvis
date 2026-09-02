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
 * Voice settings (§25, §42).
 *
 * Everything the old app scattered across a 774-line preferences screen —
 * speech rate, pitch, wake sensitivity, headset-only mode, background
 * execution, automations — lives here, phrased as things a person wants
 * rather than as parameters:
 *
 *  - "Speaking speed", not `speechRate = 1.0f`
 *  - "Only when the Clip is connected", not `isHeadsetOnlyMode`
 *  - "Keep OMNIX listening in the background", not "foreground service"
 */
@Composable
fun VoiceSettingsScreen(
    speechRate: Float,
    speechPitch: Float,
    wakeSensitivity: Float,
    headsetOnly: Boolean,
    listeningActive: Boolean,
    voiceFeedback: Boolean,
    automationCount: Int,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSpeechRateChange: (Float) -> Unit = {},
    onSpeechPitchChange: (Float) -> Unit = {},
    onWakeSensitivityChange: (Float) -> Unit = {},
    onHeadsetOnlyChange: (Boolean) -> Unit = {},
    onListeningChange: (Boolean) -> Unit = {},
    onVoiceFeedbackChange: (Boolean) -> Unit = {},
    onCommitChanges: () -> Unit = {},
    onOpenBackgroundSettings: () -> Unit = {},
    onOpenAutomations: () -> Unit = {}
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
            title = stringResource(R.string.omnix_voice_title),
            onBack = onBack
        )

        OmnixSettingsSectionHeader(stringResource(R.string.omnix_voice_listening))

        OmnixToggleRow(
            title = stringResource(R.string.omnix_voice_listening),
            subtitle = stringResource(R.string.omnix_voice_listening_body),
            checked = listeningActive,
            onCheckedChange = onListeningChange
        )
        OmnixToggleRow(
            title = stringResource(R.string.omnix_voice_headset_only),
            subtitle = stringResource(R.string.omnix_voice_headset_only_body),
            checked = headsetOnly,
            onCheckedChange = onHeadsetOnlyChange
        )
        OmnixSliderRow(
            title = stringResource(R.string.omnix_voice_sensitivity),
            value = wakeSensitivity,
            valueLabel = levelLabel(wakeSensitivity, 0f, 1f),
            valueRange = 0f..1f,
            onValueChange = onWakeSensitivityChange,
            onValueChangeFinished = onCommitChanges
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_voice_background),
            subtitle = stringResource(R.string.omnix_voice_background_body),
            value = stringResource(R.string.omnix_voice_background_action),
            onClick = onOpenBackgroundSettings
        )

        OmnixDivider()
        OmnixSettingsSectionHeader(stringResource(R.string.omnix_voice_title))

        OmnixSliderRow(
            title = stringResource(R.string.omnix_voice_speaking_speed),
            value = speechRate,
            valueLabel = levelLabel(speechRate, 0.5f, 2.0f),
            valueRange = 0.5f..2.0f,
            onValueChange = onSpeechRateChange,
            onValueChangeFinished = onCommitChanges
        )
        OmnixSliderRow(
            title = stringResource(R.string.omnix_voice_tone),
            value = speechPitch,
            valueLabel = levelLabel(speechPitch, 0.5f, 2.0f),
            valueRange = 0.5f..2.0f,
            onValueChange = onSpeechPitchChange,
            onValueChangeFinished = onCommitChanges
        )
        OmnixToggleRow(
            title = stringResource(R.string.omnix_voice_feedback),
            checked = voiceFeedback,
            onCheckedChange = onVoiceFeedbackChange
        )

        OmnixDivider()
        OmnixSettingsSectionHeader(stringResource(R.string.omnix_voice_automations))

        OmnixSettingRow(
            title = stringResource(R.string.omnix_voice_automations),
            subtitle = if (automationCount == 0) {
                stringResource(R.string.omnix_voice_automations_empty)
            } else {
                null
            },
            value = if (automationCount > 0) automationCount.toString() else null,
            onClick = onOpenAutomations
        )

        Spacer(Modifier.height(spacing.colossal))
    }
}

/**
 * Turns a raw float into a word.
 *
 * A slider that reports "1.35" tells the user nothing; "Faster" does. The
 * underlying value stays continuous — only the label is quantised.
 */
@Composable
private fun levelLabel(value: Float, min: Float, max: Float): String {
    val normalised = ((value - min) / (max - min)).coerceIn(0f, 1f)
    return stringResource(
        when {
            normalised < 0.2f -> R.string.omnix_level_lowest
            normalised < 0.4f -> R.string.omnix_level_low
            normalised < 0.6f -> R.string.omnix_level_normal
            normalised < 0.8f -> R.string.omnix_level_high
            else -> R.string.omnix_level_highest
        }
    )
}
