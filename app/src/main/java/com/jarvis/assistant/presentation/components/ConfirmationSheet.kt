package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.state.ConfirmationRequest

/**
 * Confirmation before a consequential action (§17, §36, §62).
 *
 * Rules enforced here:
 *  - the exact content that will be sent is shown, never a summary of it;
 *  - Cancel is as reachable as Confirm — it is not a faint link;
 *  - the voice hint is present because the same decision can be spoken, and
 *    both paths run the same orchestrator code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationSheet(
    request: ConfirmationRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(
            topStart = OmnixTheme.radius.large,
            topEnd = OmnixTheme.radius.large
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = request.title,
                style = OmnixTheme.typography.heading,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )

            request.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                // The literal content, quoted, so the user approves what will
                // actually happen — not a paraphrase of it.
                Text(
                    text = detail,
                    style = OmnixTheme.typography.body,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OmnixSecondaryButton(
                    text = request.cancelLabel,
                    onClick = onCancel
                )
                OmnixPrimaryButton(
                    text = request.confirmLabel,
                    onClick = onConfirm
                )
            }

            if (request.voiceEnabled) {
                Text(
                    text = stringResource(
                        R.string.omnix_confirm_voice_hint,
                        stringResource(R.string.omnix_voice_yes)
                    ),
                    style = OmnixTheme.typography.caption,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
