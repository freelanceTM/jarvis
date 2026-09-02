package com.jarvis.assistant.presentation.translator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.assistant.presentation.core.CoreState

/**
 * Translation is a mode of OMNIX, not a separate app (§25).
 *
 * The route reuses the existing [LiveInterpreterViewModel] unchanged and maps
 * its state onto the same Core the rest of the product uses, so entering
 * translation never feels like leaving OMNIX.
 */
@Composable
fun TranslatorRoute(
    modifier: Modifier = Modifier,
    audioLevel: Float = 0f,
    viewModel: LiveInterpreterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // The Core reports the same eight states here as everywhere else: it
    // listens, it thinks while translating, it speaks the result.
    val coreState = when {
        state.isTranslating -> CoreState.THINKING
        state.isListening && state.partialRecognizedText.isNotBlank() -> CoreState.RECOGNIZING
        state.isListening -> CoreState.LISTENING
        else -> CoreState.IDLE
    }

    val latest = state.history.firstOrNull()

    TranslatorScreen(
        modifier = modifier,
        active = state.isListening,
        coreState = coreState,
        audioLevel = audioLevel,
        sourceLanguage = (state.detectedSourceLanguage ?: state.sourceLanguage).displayName,
        targetLanguage = state.targetLanguage.displayName,
        // While speech is still arriving the partial text is the transcript;
        // once an item lands, the finished pair is shown instead.
        transcript = state.partialRecognizedText.ifBlank { latest?.originalText.orEmpty() },
        translation = latest?.translatedText.orEmpty(),
        onStart = viewModel::toggleListening,
        onStop = viewModel::toggleListening,
        onSwap = viewModel::swapLanguages
    )
}
