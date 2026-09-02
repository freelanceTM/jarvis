package com.jarvis.assistant.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.ClipStatusBar
import com.jarvis.assistant.presentation.components.OmnixSpokenExample
import com.jarvis.assistant.presentation.components.SystemStateView
import com.jarvis.assistant.presentation.core.CoreState
import com.jarvis.assistant.presentation.core.OmnixAudioBars
import com.jarvis.assistant.presentation.core.OmnixCore
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.design.OmnixWordmarkStyle
import com.jarvis.assistant.presentation.state.ActionPhrase
import com.jarvis.assistant.presentation.state.GuidanceLevel
import com.jarvis.assistant.presentation.state.OmnixPhase
import com.jarvis.assistant.presentation.state.OmnixUiState

/**
 * Home — presence and orientation, not a dashboard (§9, §21, §22).
 *
 * The screen answers exactly four questions:
 *  1. Is OMNIX here?          → the wordmark and the Core
 *  2. Is my Clip connected?   → [ClipStatusBar]
 *  3. What is it doing?       → the state line under the Core
 *  4. What can I say?         → guidance, which fades as the user learns
 *
 * There is no CPU load, no provider name, no latency, no token count and no
 * command counter anywhere on this screen (§9, §31, §84).
 */
@Composable
fun HomeScreen(
    state: OmnixUiState,
    modifier: Modifier = Modifier,
    onCoreTap: () -> Unit = {},
    onClipTap: () -> Unit = {},
    onSystemStateAction: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(spacing.xxl))

        Text(
            text = stringResource(R.string.omnix_wordmark),
            style = OmnixWordmarkStyle,
            color = colors.textSecondary
        )

        Spacer(Modifier.height(spacing.xs))

        ClipStatusBar(
            clip = state.clip,
            isOnline = state.isOnline,
            onClick = onClipTap
        )

        // The Core sits in the optical centre: the space above and below it is
        // deliberate, and is what makes the product feel calm (§21, §80).
        Spacer(Modifier.height(spacing.colossal))

        // The Core, flanked by the audio bars while it is hearing or speaking.
        // The bars only exist for the audio-reactive states, so the layout is
        // quiet the rest of the time (§29).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            AudioBarsSlot(state = state, mirrored = true)

            OmnixCore(
                state = state.coreState,
                size = OmnixTheme.coreSizes.home,
                audioLevel = state.audioLevel,
                contentDescription = stringResource(
                    R.string.omnix_a11y_core_state,
                    stateLabel(state)
                )
            )

            AudioBarsSlot(state = state, mirrored = false)
        }

        Spacer(Modifier.height(spacing.xxl))

        StateLine(state = state)

        Spacer(Modifier.height(spacing.md))

        // Guidance is progressive: it is driven by real stored signals, not by
        // a timer and not by a hardcoded "isNewUser" flag (§10, §82).
        Guidance(state = state)

        state.systemState?.let { systemState ->
            Spacer(Modifier.height(spacing.xl))
            SystemStateView(
                type = systemState,
                onAction = onSystemStateAction
            )
        }

        Spacer(Modifier.height(spacing.colossal))
    }
}

/**
 * A fixed-width slot for the audio bars.
 *
 * The slot is always present so the Core never shifts horizontally when
 * listening starts; only the bars inside it appear (§46).
 */
@Composable
private fun AudioBarsSlot(state: OmnixUiState, mirrored: Boolean) {
    val visible = state.coreState.isAudioReactive
    Box(modifier = Modifier.size(width = 34.dp, height = 34.dp)) {
        if (visible) {
            OmnixAudioBars(
                level = state.audioLevel,
                color = OmnixTheme.colors.let { colors ->
                    when (state.coreState) {
                        CoreState.SPEAKING -> colors.stateSpeaking
                        else -> colors.stateListening
                    }
                },
                mirrored = mirrored
            )
        }
    }
}

/**
 * The single line of text under the Core. It always says what OMNIX is doing
 * in the user's language — "Calling Alex…", never "EXECUTING" (§15, §19).
 */
@Composable
private fun StateLine(state: OmnixUiState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Reserved height keeps the Core from shifting as the label
            // changes length between states (§46).
            .height(56.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = stateLabel(state),
            style = OmnixTheme.typography.heading,
            color = OmnixTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )
    }
}

/** Resolves the current phase into human copy. */
@Composable
fun stateLabel(state: OmnixUiState): String = when (val phase = state.phase) {
    is OmnixPhase.Idle -> stringResource(R.string.omnix_state_ready)
    is OmnixPhase.Listening -> stringResource(R.string.omnix_state_listening)
    // While recognising, the partial transcript *is* the feedback: showing the
    // user's own words is more reassuring than the word "Recognizing" (§17).
    is OmnixPhase.Recognizing -> phase.partialTranscript.ifBlank {
        stringResource(R.string.omnix_state_listening)
    }
    is OmnixPhase.Thinking -> stringResource(R.string.omnix_state_thinking)
    is OmnixPhase.Executing -> ActionPhrase.of(phase.action)
    is OmnixPhase.Speaking -> phase.text.ifBlank {
        stringResource(R.string.omnix_state_speaking)
    }
    is OmnixPhase.Success -> phase.message
    is OmnixPhase.Error -> stringResource(R.string.omnix_state_ready)
}

/**
 * Progressive disclosure (§10, §24–§26, §82).
 *
 * New user  → wake word plus a concrete example
 * Familiar  → wake word only
 * Minimal   → nothing; presence is enough
 */
@Composable
private fun Guidance(state: OmnixUiState, modifier: Modifier = Modifier) {
    val spacing = OmnixTheme.spacing
    val visible = state.phase is OmnixPhase.Idle && state.guidance != GuidanceLevel.Minimal

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(OmnixTheme.motion.screenEnterMs)),
        exit = fadeOut(tween(OmnixTheme.motion.screenExitMs)),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(
                text = stringResource(
                    R.string.omnix_home_hint_say_omni_wake,
                    stringResource(R.string.omnix_wake_word_quoted)
                ),
                style = OmnixTheme.typography.body,
                color = OmnixTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
            if (state.guidance == GuidanceLevel.New) {
                OmnixSpokenExample(text = stringResource(R.string.omnix_home_example_time))
            }
            if (!state.isOnline) {
                Text(
                    text = stringResource(R.string.omnix_home_offline_partial),
                    style = OmnixTheme.typography.caption,
                    color = OmnixTheme.colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
