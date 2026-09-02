package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * The OMNIX button system (§76).
 *
 * Three roles, no more:
 *  - [OmnixPrimaryButton]   one genuinely important action per screen;
 *  - [OmnixSecondaryButton] the alternative — Cancel, Manage, Later;
 *  - [OmnixTextButton]      quiet links — Learn more, View history.
 *
 * All of them satisfy the 44 dp minimum touch target (§57) even when the
 * visible shape is smaller.
 */
@Composable
fun OmnixPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = spacing.touchTarget),
        shape = RoundedCornerShape(OmnixTheme.radius.pill),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.actionPrimary,
            contentColor = colors.onActionPrimary,
            disabledContainerColor = colors.surfaceElevated,
            disabledContentColor = colors.textDisabled
        ),
        contentPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.sm)
    ) {
        Text(text = text, style = OmnixTheme.typography.body)
    }
}

@Composable
fun OmnixSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = spacing.touchTarget),
        shape = RoundedCornerShape(OmnixTheme.radius.pill),
        border = BorderStroke(OmnixHairline, colors.actionSecondaryBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.textPrimary,
            disabledContentColor = colors.textDisabled
        ),
        contentPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.sm)
    ) {
        Text(text = text, style = OmnixTheme.typography.body)
    }
}

@Composable
fun OmnixTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = spacing.touchTarget),
        colors = ButtonDefaults.textButtonColors(
            contentColor = colors.textSecondary,
            disabledContentColor = colors.textDisabled
        ),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        Text(text = text, style = OmnixTheme.typography.caption)
    }
}

/** The single hairline width used by every OMNIX border and divider. */
val OmnixHairline: Dp = 1.dp
