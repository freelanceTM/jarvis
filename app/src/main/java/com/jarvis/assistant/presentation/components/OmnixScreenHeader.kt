package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * Title row for a sub-screen, with the back affordance (§20).
 *
 * Sub-screens are reached from Me, so they need a way out that does not rely
 * on the system gesture alone. The control is a 44 dp target with a spoken
 * label, and it never competes with the Core: no filled bar, no elevation.
 */
@Composable
fun OmnixScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors
    val backLabel = stringResource(R.string.omnix_nav_back)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            OmnixIconButton(
                onClick = onBack,
                contentDescription = backLabel
            ) {
                OmnixBackIcon(color = colors.textSecondary)
            }
            Spacer(Modifier.width(spacing.xs))
        }

        Text(
            text = title,
            style = OmnixTheme.typography.screenTitle,
            color = colors.textPrimary
        )
    }
}

/**
 * A borderless, circular tap target that satisfies the 44 dp minimum even
 * when the glyph inside it is small (§32).
 */
@Composable
fun OmnixIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val spacing = OmnixTheme.spacing
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .size(spacing.touchTarget)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(spacing.xs)
        ) {
            content()
        }
    }
}
