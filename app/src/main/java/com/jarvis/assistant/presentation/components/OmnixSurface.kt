package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.presentation.design.OmnixTheme

/**
 * A neutral OMNIX panel. Used sparingly: the product is built on empty space and
 * typography, not on stacked cards (§9, §21).
 */
@Composable
fun OmnixPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = OmnixTheme.spacing.md,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = OmnixTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OmnixTheme.radius.medium),
        color = colors.surface,
        border = BorderStroke(OmnixHairline, colors.border)
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** The one-pixel separator used across the product. */
@Composable
fun OmnixDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OmnixHairline)
            .background(OmnixTheme.colors.border)
    )
}

/**
 * The status dot used in headers and device rows.
 *
 * Colour alone never communicates state (§55) — always pair it with a label.
 */
@Composable
fun OmnixStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp
) {
    Box(
        modifier = modifier
            .clearAndSetSemantics { }
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * A caption rendered the way spoken examples are shown on Home and in the
 * first-run flow — quoted, quiet, never a button (§24).
 */
@Composable
fun OmnixSpokenExample(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = OmnixTheme.typography.caption,
        color = OmnixTheme.colors.textDisabled,
        modifier = modifier
    )
}
