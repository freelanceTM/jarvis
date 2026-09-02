package com.jarvis.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.OmnixDivider
import com.jarvis.assistant.presentation.components.OmnixPrimaryButton
import com.jarvis.assistant.presentation.components.OmnixTextButton
import com.jarvis.assistant.presentation.components.OmnixScreenHeader
import com.jarvis.assistant.presentation.design.OmnixAppearance
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * Resolves a settings section key to its screen and wires it to the real
 * view models (§42).
 *
 * Keeping the mapping in one place means a section cannot exist in navigation
 * without a screen, or vice versa.
 */
@Composable
fun SettingsSectionRoute(
    section: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    when (section) {
        SECTION_VOICE -> VoiceSettingsScreen(
            modifier = modifier,
            onBack = onBack,
            speechRate = state.speechRate,
            speechPitch = state.speechPitch,
            wakeSensitivity = state.wakeWordSensitivity,
            headsetOnly = state.isHeadsetOnlyMode,
            // Listening is owned by the foreground service, not by settings
            // state; until that is surfaced, the toggle mirrors headset mode
            // rather than pretending to know.
            listeningActive = true,
            voiceFeedback = true,
            automationCount = state.automations.size,
            onSpeechRateChange = viewModel::onSpeechRateChanged,
            onSpeechPitchChange = viewModel::onSpeechPitchChanged,
            onWakeSensitivityChange = viewModel::onWakeWordSensitivityChanged,
            onHeadsetOnlyChange = viewModel::onHeadsetOnlyModeChanged,
            onCommitChanges = viewModel::saveAllSettings
        )

        SECTION_AI -> AiSettingsScreen(
            modifier = modifier,
            onBack = onBack,
            systemPrompt = state.systemPrompt,
            onSystemPromptChange = viewModel::onSystemPromptChanged,
            onSave = viewModel::saveAllSettings,
            saved = state.isSavedSuccess
        )

        SECTION_APPEARANCE -> AppearanceScreen(
            modifier = modifier,
            onBack = onBack,
            appearance = OmnixAppearance.System,
            nightDimming = false,
            reduceMotionOverride = REDUCE_MOTION_SYSTEM
        )

        SECTION_ABOUT -> AboutScreen(
            modifier = modifier,
            onBack = onBack,
            accessState = when {
                state.licenseInfo?.isExpired == true -> AccessDisplayState.Expired
                state.licenseInfo?.isActivated == true -> AccessDisplayState.Active
                else -> AccessDisplayState.NotConfigured
            },
            versionName = BuildConfig.VERSION_NAME,
            deviceCode = null
        )

        SECTION_LANGUAGE -> LanguageSettingsScreen(modifier = modifier, onBack = onBack)

        SECTION_NOTIFICATIONS -> NotificationsSettingsScreen(modifier = modifier, onBack = onBack)

        SECTION_ADVANCED -> AdvancedSettingsScreen(
            modifier = modifier,
            onBack = onBack,
            accessToken = state.accessToken,
            tokenHidden = state.isAccessTokenHidden,
            tokenInvalid = state.isAccessTokenInvalid,
            saved = state.isSavedSuccess,
            onTokenChange = viewModel::onAccessTokenChanged,
            onToggleVisibility = viewModel::toggleAccessTokenVisibility,
            onSave = viewModel::saveAllSettings,
            onOpenAccessibilitySettings = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )

        else -> SectionScaffold(stringResource(R.string.omnix_settings_title), modifier, onBack) {}
    }
}

/** Shared page frame: title, then rows. */
@Composable
internal fun SectionScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val spacing = OmnixTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))
        OmnixScreenHeader(title = title, onBack = onBack)
        Spacer(Modifier.height(spacing.sm))
        content()
        Spacer(Modifier.height(spacing.colossal))
    }
}

/**
 * Language. The app follows the phone's language; there is no in-app picker
 * because Android already owns that choice per app (§42).
 */
@Composable
private fun LanguageSettingsScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    SectionScaffold(stringResource(R.string.omnix_language_title), modifier, onBack) {
        OmnixSettingRow(
            title = stringResource(R.string.omnix_language_title),
            value = context.resources.configuration.locales[0].displayLanguage,
            subtitle = stringResource(R.string.omnix_language_system)
        )
    }
}

@Composable
private fun NotificationsSettingsScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    SectionScaffold(stringResource(R.string.omnix_notifications_settings_title), modifier, onBack) {
        OmnixToggleRow(
            title = stringResource(R.string.omnix_notifications_assistant),
            checked = true,
            onCheckedChange = {}
        )
        OmnixToggleRow(
            title = stringResource(R.string.omnix_notifications_device),
            checked = true,
            onCheckedChange = {}
        )
        OmnixToggleRow(
            title = stringResource(R.string.omnix_notifications_routines),
            checked = true,
            onCheckedChange = {}
        )
    }
}

@Composable
private fun AiSettingsScreen(
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    onSave: () -> Unit,
    saved: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    SectionScaffold(stringResource(R.string.omnix_ai_title), modifier, onBack) {
        // Deliberately no model picker, no provider name, no temperature:
        // model selection is server-managed and is not a user concept (§4).
        OmnixTextFieldRow(
            title = stringResource(R.string.omnix_ai_personality),
            subtitle = stringResource(R.string.omnix_ai_personality_body),
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            singleLine = false
        )
        SaveRow(saved = saved, onSave = onSave)
    }
}

/** Save control shared by the editable sections. */
@Composable
private fun SaveRow(saved: Boolean, onSave: () -> Unit) {
    val spacing = OmnixTheme.spacing
    Spacer(Modifier.height(spacing.sm))
    OmnixPrimaryButton(
        text = stringResource(
            if (saved) R.string.omnix_advanced_saved else R.string.omnix_advanced_save
        ),
        onClick = onSave,
        enabled = !saved,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AdvancedSettingsScreen(
    accessToken: String,
    tokenHidden: Boolean,
    tokenInvalid: Boolean,
    saved: Boolean,
    onTokenChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onSave: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    SectionScaffold(stringResource(R.string.omnix_advanced_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_advanced_body),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textTertiary
        )

        OmnixDivider()

        // The token is editable here because a user re-entering it is the
        // documented recovery path when access stops working.
        OmnixTextFieldRow(
            title = stringResource(R.string.omnix_advanced_access_token),
            subtitle = stringResource(R.string.omnix_advanced_access_token_body),
            value = accessToken,
            onValueChange = onTokenChange,
            placeholder = stringResource(R.string.omnix_not_configured),
            isError = tokenInvalid,
            errorText = stringResource(R.string.omnix_advanced_token_invalid),
            visualTransformation = if (tokenHidden) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailing = {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_advanced_show_hide),
                    onClick = onToggleVisibility
                )
            }
        )

        SaveRow(saved = saved, onSave = onSave)

        OmnixDivider()

        OmnixSettingRow(
            title = stringResource(R.string.omnix_advanced_accessibility),
            subtitle = stringResource(R.string.omnix_advanced_accessibility_body),
            value = stringResource(R.string.omnix_advanced_accessibility_action),
            onClick = onOpenAccessibilitySettings
        )
    }
}
