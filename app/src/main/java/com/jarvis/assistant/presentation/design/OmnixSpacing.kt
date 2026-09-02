package com.jarvis.assistant.presentation.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale, base unit 4 (§7, §31).
 *
 * Only these values may appear in layouts. A padding of `17.dp` is a bug,
 * not a decision.
 */
@Immutable
data class OmnixSpacingTokens(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 40.dp,
    val huge: Dp = 48.dp,
    val giant: Dp = 64.dp,
    val colossal: Dp = 80.dp,

    /** Standard horizontal screen gutter. */
    val screenHorizontal: Dp = 24.dp,
    /** Minimum touch target, §57. */
    val touchTarget: Dp = 44.dp
)

val OmnixSpacing = OmnixSpacingTokens()

/**
 * Corner radius tokens (§8). Four values, not six. The Core has its own
 * geometry and never uses these.
 */
@Immutable
data class OmnixRadiusTokens(
    val small: Dp = 10.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val pill: Dp = 999.dp
)

val OmnixRadius = OmnixRadiusTokens()

/**
 * Core sizes (§14). One Core, four scales.
 */
@Immutable
data class OmnixCoreSizeTokens(
    val navigation: Dp = 30.dp,
    val status: Dp = 44.dp,
    val home: Dp = 150.dp,
    val focus: Dp = 240.dp
)

val OmnixCoreSizes = OmnixCoreSizeTokens()

/**
 * Elevation tokens. OMNIX uses surface separation rather than shadows;
 * these values exist so that sheets and dialogs are consistent.
 */
@Immutable
data class OmnixElevationTokens(
    val flat: Dp = 0.dp,
    val raised: Dp = 1.dp,
    val sheet: Dp = 8.dp
)

val OmnixElevation = OmnixElevationTokens()
