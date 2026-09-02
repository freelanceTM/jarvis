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
import com.jarvis.assistant.presentation.components.ClipStatusBar
import com.jarvis.assistant.presentation.components.OmnixDivider
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.navigation.OmnixDestination
import com.jarvis.assistant.presentation.state.ClipState

/**
 * "Me" — the third primary destination (§20, §44).
 *
 * It is an index of human concepts, not a preference dump: Voice, Privacy,
 * Devices, AI, Language, Notifications, Appearance, About. Each opens a focused
 * page. Advanced is present but visually last, because it is not part of
 * everyday use (§42).
 */
@Composable
fun MeScreen(
    clip: ClipState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onOpenSection: (OmnixDestination) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))

        Text(
            text = stringResource(R.string.omnix_settings_title),
            style = OmnixTheme.typography.screenTitle,
            color = OmnixTheme.colors.textPrimary
        )

        Spacer(Modifier.height(spacing.sm))

        // The Clip's state is the one piece of live status worth surfacing
        // here — it is what determines whether OMNIX can hear the user.
        ClipStatusBar(
            clip = clip,
            isOnline = isOnline,
            onClick = { onOpenSection(OmnixDestination.Devices) }
        )

        Spacer(Modifier.height(spacing.md))
        OmnixDivider()

        // Modes come before preferences: they are things OMNIX can do, not
        // things to configure. Translation is a mode, not an app (§25), and
        // typing is the quiet alternative to speaking (§24).
        OmnixSettingRow(
            title = stringResource(R.string.omnix_translator_title),
            contentDescription = stringResource(R.string.omnix_a11y_open_translator),
            onClick = { onOpenSection(OmnixDestination.Translator) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_chat_title),
            contentDescription = stringResource(R.string.omnix_a11y_open_chat),
            onClick = { onOpenSection(OmnixDestination.Chat) }
        )

        Spacer(Modifier.height(spacing.lg))
        OmnixDivider()

        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_voice),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_VOICE)) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_privacy),
            onClick = { onOpenSection(OmnixDestination.Privacy) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_devices),
            contentDescription = stringResource(R.string.omnix_a11y_open_devices),
            onClick = { onOpenSection(OmnixDestination.Devices) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_ai),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_AI)) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_language),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_LANGUAGE)) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_notifications),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_NOTIFICATIONS)) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_appearance),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_APPEARANCE)) }
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_about),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_ABOUT)) }
        )

        Spacer(Modifier.height(spacing.lg))
        OmnixDivider()

        OmnixSettingRow(
            title = stringResource(R.string.omnix_settings_advanced),
            subtitle = stringResource(R.string.omnix_advanced_body),
            onClick = { onOpenSection(OmnixDestination.SettingsSection(SECTION_ADVANCED)) }
        )

        Spacer(Modifier.height(spacing.colossal))
    }
}

const val SECTION_VOICE = "voice"
const val SECTION_AI = "ai"
const val SECTION_LANGUAGE = "language"
const val SECTION_NOTIFICATIONS = "notifications"
const val SECTION_APPEARANCE = "appearance"
const val SECTION_ABOUT = "about"
const val SECTION_ADVANCED = "advanced"
