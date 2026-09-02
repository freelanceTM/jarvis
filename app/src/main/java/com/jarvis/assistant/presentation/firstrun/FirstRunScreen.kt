package com.jarvis.assistant.presentation.firstrun

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.OmnixPrimaryButton
import com.jarvis.assistant.presentation.components.OmnixSpokenExample
import com.jarvis.assistant.presentation.components.OmnixTextButton
import com.jarvis.assistant.presentation.components.SystemStateView
import com.jarvis.assistant.presentation.components.clipLabel
import com.jarvis.assistant.presentation.core.CoreState
import com.jarvis.assistant.presentation.core.OmnixCore
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.design.OmnixWordmarkStyle
import com.jarvis.assistant.presentation.state.ClipState
import com.jarvis.assistant.presentation.state.OmnixUiState
import com.jarvis.assistant.presentation.state.SystemStateType

/**
 * First run (§34, §67).
 *
 * The Core is present from the very first screen and stays in place across
 * every step — the user meets one object and watches it react, rather than
 * paging through a carousel of illustrations (§30).
 */
@Composable
fun FirstRunScreen(
    step: FirstRunStep,
    state: OmnixUiState,
    microphoneGranted: Boolean,
    modifier: Modifier = Modifier,
    onAdvance: () -> Unit,
    onSkipDevice: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onEnterActivationCode: () -> Unit,
    onSearchAgain: () -> Unit
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(spacing.xxxl))

        Text(
            text = stringResource(R.string.omnix_wordmark),
            style = OmnixWordmarkStyle,
            color = colors.textSecondary
        )

        Spacer(Modifier.weight(1f))

        OmnixCore(
            state = coreStateFor(step, state, microphoneGranted),
            size = OmnixTheme.coreSizes.home,
            audioLevel = state.audioLevel
        )

        Spacer(Modifier.height(spacing.xxl))

        // The durations are read here, in composable scope: `transitionSpec`
        // runs outside it and cannot touch the theme.
        val enterMs = OmnixTheme.motion.screenEnterMs
        val exitMs = OmnixTheme.motion.screenExitMs

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                fadeIn(tween(enterMs)) togetherWith fadeOut(tween(exitMs))
            },
            label = "first-run-step"
        ) { current ->
            when (current) {
                FirstRunStep.Welcome -> WelcomeStep(onAdvance)

                FirstRunStep.DeviceDetection -> DeviceDetectionStep(
                    clip = state.clip,
                    onAdvance = onAdvance,
                    onSkip = onSkipDevice,
                    onEnterCode = onEnterActivationCode,
                    onSearchAgain = onSearchAgain
                )

                FirstRunStep.ClipPairing -> ClipPairingStep(
                    clip = state.clip,
                    onAdvance = onAdvance
                )

                FirstRunStep.Microphone -> MicrophoneStep(
                    granted = microphoneGranted,
                    onRequest = onRequestMicrophone,
                    onOpenSettings = onOpenSystemSettings,
                    onAdvance = onAdvance
                )

                FirstRunStep.FirstCommand -> FirstCommandStep(
                    state = state,
                    onAdvance = onAdvance
                )

                FirstRunStep.Complete -> Box(Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(spacing.xxl))
    }
}

/**
 * The Core reflects the step, so progress is felt rather than counted. There
 * is no progress bar and no "Step 2 of 5" (§30, §84).
 */
private fun coreStateFor(
    step: FirstRunStep,
    state: OmnixUiState,
    microphoneGranted: Boolean
): CoreState = when (step) {
    FirstRunStep.Welcome -> CoreState.IDLE
    FirstRunStep.DeviceDetection -> when (state.clip) {
        is ClipState.Connected -> CoreState.SUCCESS
        is ClipState.Connecting, ClipState.Searching -> CoreState.THINKING
        else -> CoreState.IDLE
    }
    FirstRunStep.ClipPairing ->
        if (state.clip is ClipState.Connected) CoreState.SUCCESS else CoreState.EXECUTING
    FirstRunStep.Microphone -> if (microphoneGranted) CoreState.SUCCESS else CoreState.IDLE
    // During the first command the Core does what it will always do.
    FirstRunStep.FirstCommand -> state.coreState
    FirstRunStep.Complete -> CoreState.IDLE
}

@Composable
private fun StepScaffold(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Text(
            text = title,
            style = OmnixTheme.typography.display,
            color = OmnixTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )
        body?.let {
            Text(
                text = it,
                style = OmnixTheme.typography.body,
                color = OmnixTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(spacing.md))
        content()
    }
}

@Composable
private fun WelcomeStep(onAdvance: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.omnix_welcome_headline),
        body = stringResource(R.string.omnix_welcome_body)
    ) {
        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_welcome_cta),
            onClick = onAdvance
        )
    }
}

@Composable
private fun DeviceDetectionStep(
    clip: ClipState,
    onAdvance: () -> Unit,
    onSkip: () -> Unit,
    onEnterCode: () -> Unit,
    onSearchAgain: () -> Unit
) {
    val spacing = OmnixTheme.spacing
    when (clip) {
        is ClipState.Connected, is ClipState.Connecting -> StepScaffold(
            title = stringResource(R.string.omnix_pairing_detected),
            body = clipLabel(clip)
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_continue), onAdvance)
        }

        ClipState.Searching -> StepScaffold(
            title = stringResource(R.string.omnix_pairing_title),
            body = stringResource(R.string.omnix_pairing_searching)
        ) {
            // No Clip is required to use OMNIX, so the way forward is always
            // open — the user is never trapped by missing hardware (§34).
            OmnixTextButton(stringResource(R.string.omnix_skip_for_now), onSkip)
        }

        else -> StepScaffold(
            title = stringResource(R.string.omnix_pairing_not_found_title),
            body = stringResource(R.string.omnix_pairing_not_found_body)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                OmnixPrimaryButton(stringResource(R.string.omnix_pairing_retry), onSearchAgain)
                OmnixTextButton(stringResource(R.string.omnix_pairing_code_link), onEnterCode)
                OmnixTextButton(stringResource(R.string.omnix_skip_for_now), onSkip)
            }
        }
    }
}

@Composable
private fun ClipPairingStep(clip: ClipState, onAdvance: () -> Unit) {
    val connected = clip is ClipState.Connected
    StepScaffold(
        title = if (connected) {
            stringResource(R.string.omnix_clip_connected_title)
        } else {
            stringResource(R.string.omnix_clip_connecting_title)
        },
        body = if (connected) stringResource(R.string.omnix_pairing_verified) else null
    ) {
        if (connected) {
            OmnixPrimaryButton(stringResource(R.string.omnix_clip_continue), onAdvance)
        }
    }
}

/**
 * The microphone request (§38, §50).
 *
 * WHAT  — "Let OMNIX hear you"
 * WHY   — "Your microphone is needed for voice commands."
 * ACTION— a single button.
 *
 * The Android permission identifier is never shown.
 */
@Composable
private fun MicrophoneStep(
    granted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onAdvance: () -> Unit
) {
    if (granted) {
        StepScaffold(
            title = stringResource(R.string.omnix_mic_ready),
            body = null
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_mic_continue), onAdvance)
        }
    } else {
        StepScaffold(
            title = stringResource(R.string.omnix_mic_title),
            body = stringResource(R.string.omnix_mic_body)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OmnixTheme.spacing.xs)
            ) {
                OmnixPrimaryButton(stringResource(R.string.omnix_error_mic_action), onRequest)
                OmnixTextButton(stringResource(R.string.omnix_mic_open_settings), onOpenSettings)
            }
        }
    }
}

/**
 * The first real command. Success here is a genuine interaction, not a
 * simulated one — the state comes from the live pipeline (§34).
 */
@Composable
private fun FirstCommandStep(state: OmnixUiState, onAdvance: () -> Unit) {
    val spacing = OmnixTheme.spacing
    val succeeded = state.lastInteraction != null

    if (succeeded) {
        StepScaffold(
            title = stringResource(R.string.omnix_first_success_title),
            body = stringResource(R.string.omnix_first_success_body)
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_first_success_cta), onAdvance)
        }
    } else {
        StepScaffold(
            title = stringResource(R.string.omnix_clip_ready_title),
            body = stringResource(R.string.omnix_clip_ready_say)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                OmnixSpokenExample(stringResource(R.string.omnix_home_example_time))
                if (state.systemState == SystemStateType.MICROPHONE_DENIED) {
                    SystemStateView(type = SystemStateType.MICROPHONE_DENIED)
                }
                OmnixTextButton(stringResource(R.string.omnix_skip_for_now), onAdvance)
            }
        }
    }
}
