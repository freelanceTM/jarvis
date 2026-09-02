package com.jarvis.assistant.presentation.activation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.OmnixPrimaryButton
import com.jarvis.assistant.presentation.core.CoreState
import com.jarvis.assistant.presentation.core.OmnixCore
import com.jarvis.assistant.presentation.design.OmnixRadius
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.design.OmnixWordmarkStyle

/**
 * Activation (§34, step 2).
 *
 * The same Core, the same wordmark, the same type scale as every other
 * screen — activation is the user's first impression of the product, so it
 * cannot look like a different app (§2).
 *
 * The screen shows the Core in THINKING while the code is being checked; there
 * is no separate spinner, and no technical wording about licences or servers.
 */
@Composable
fun ActivationScreen(
    onActivationSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val typography = OmnixTheme.typography

    LaunchedEffect(uiState.isActivated) {
        if (uiState.isActivated) onActivationSuccess()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(spacing.colossal))

        OmnixCore(
            state = if (uiState.isLoading) CoreState.THINKING else CoreState.IDLE,
            size = OmnixTheme.coreSizes.home
        )

        Spacer(Modifier.height(spacing.xl))

        Text(
            text = stringResource(R.string.omnix_wordmark),
            style = OmnixWordmarkStyle,
            color = colors.textPrimary
        )

        Spacer(Modifier.height(spacing.xl))

        Text(
            text = stringResource(R.string.omnix_activation_title),
            style = typography.heading,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(spacing.xs))

        Text(
            text = stringResource(R.string.omnix_activation_body),
            style = typography.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(spacing.xl))

        OutlinedTextField(
            value = uiState.inputCode,
            onValueChange = viewModel::onCodeChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !uiState.isLoading,
            isError = uiState.errorMessage != null,
            label = { Text(stringResource(R.string.omnix_activation_field)) },
            textStyle = typography.body,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            shape = RoundedCornerShape(OmnixRadius.medium),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedBorderColor = colors.stateIdle,
                unfocusedBorderColor = colors.border,
                errorBorderColor = colors.stateError,
                focusedLabelColor = colors.textSecondary,
                unfocusedLabelColor = colors.textTertiary,
                cursorColor = colors.stateIdle,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface
            )
        )

        // Errors are shown in the user's language by the view model; the UI
        // never renders a status code (§18).
        AnimatedVisibility(visible = uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage.orEmpty(),
                style = typography.caption,
                color = colors.stateError,
                modifier = Modifier.padding(top = spacing.xs)
            )
        }

        Spacer(Modifier.height(spacing.lg))

        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_activation_cta),
            onClick = viewModel::activate,
            enabled = uiState.inputCode.isNotBlank() && !uiState.isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(spacing.colossal))
    }
}
