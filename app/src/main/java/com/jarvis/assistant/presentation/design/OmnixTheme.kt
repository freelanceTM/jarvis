package com.jarvis.assistant.presentation.design

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** User-facing appearance choice (§47). Default is [System]. */
enum class OmnixAppearance { System, Light, Dark }

val LocalOmnixColors = staticCompositionLocalOf { OmnixDarkColors }
val LocalOmnixTypography = staticCompositionLocalOf { OmnixTypography }
val LocalOmnixSpacing = staticCompositionLocalOf { OmnixSpacing }
val LocalOmnixRadius = staticCompositionLocalOf { OmnixRadius }
val LocalOmnixCoreSizes = staticCompositionLocalOf { OmnixCoreSizes }
val LocalOmnixElevation = staticCompositionLocalOf { OmnixElevation }

/** Motion tokens, already reduced when the system asks for it. */
val LocalOmnixMotion = staticCompositionLocalOf { OmnixMotion }

/**
 * True when the user disabled animations system-wide (§29). Components must
 * express state through typography, stroke and colour in that case.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Central access point for design tokens.
 *
 * ```
 * Text(text = …, style = OmnixTheme.typography.body, color = OmnixTheme.colors.textSecondary)
 * ```
 */
object OmnixTheme {
    val colors: OmnixColorScheme
        @Composable @ReadOnlyComposable get() = LocalOmnixColors.current

    val typography: OmnixTypographyTokens
        @Composable @ReadOnlyComposable get() = LocalOmnixTypography.current

    val spacing: OmnixSpacingTokens
        @Composable @ReadOnlyComposable get() = LocalOmnixSpacing.current

    val radius: OmnixRadiusTokens
        @Composable @ReadOnlyComposable get() = LocalOmnixRadius.current

    val coreSizes: OmnixCoreSizeTokens
        @Composable @ReadOnlyComposable get() = LocalOmnixCoreSizes.current

    val elevation: OmnixElevationTokens
        @Composable @ReadOnlyComposable get() = LocalOmnixElevation.current

    val motion: OmnixMotionTokens
        @Composable @ReadOnlyComposable get() = LocalOmnixMotion.current

    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/**
 * Reads the platform animation scale. `0` means the user turned animations off
 * (Developer options or Accessibility → Remove animations).
 */
@Composable
private fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * The single theme of the application.
 *
 * A Material3 scheme is still provided so that platform components (text
 * fields, ripples, sheets) inherit OMNIX colours during and after the
 * migration — but OMNIX components read [OmnixTheme] directly.
 */
@Composable
fun OmnixTheme(
    appearance: OmnixAppearance = OmnixAppearance.System,
    nightMode: Boolean = false,
    reducedMotionOverride: Boolean? = null,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (appearance) {
        OmnixAppearance.System -> systemDark
        OmnixAppearance.Dark -> true
        OmnixAppearance.Light -> false
    }

    val colors = when {
        useDark && nightMode -> OmnixNightColors
        useDark -> OmnixDarkColors
        else -> OmnixLightColors
    }

    val reduced = reducedMotionOverride ?: systemReducedMotion()
    val motion = remember(reduced) { if (reduced) OmnixMotion.reduced() else OmnixMotion }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !useDark
                    isAppearanceLightNavigationBars = !useDark
                }
            }
        }
    }

    val materialScheme = if (useDark) {
        darkColorScheme(
            primary = colors.actionPrimary,
            onPrimary = colors.onActionPrimary,
            secondary = colors.stateIdle,
            onSecondary = colors.onActionPrimary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.stateError,
            onError = colors.onActionPrimary
        )
    } else {
        lightColorScheme(
            primary = colors.actionPrimary,
            onPrimary = colors.onActionPrimary,
            secondary = colors.stateIdle,
            onSecondary = colors.onActionPrimary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.stateError,
            onError = colors.onActionPrimary
        )
    }

    val materialTypography = Typography(
        displayMedium = OmnixTypography.display,
        headlineMedium = OmnixTypography.screenTitle,
        titleMedium = OmnixTypography.heading,
        bodyLarge = OmnixTypography.body,
        bodyMedium = OmnixTypography.caption,
        labelLarge = OmnixTypography.status,
        labelSmall = OmnixTypography.overline
    )

    CompositionLocalProvider(
        LocalOmnixColors provides colors,
        LocalOmnixTypography provides OmnixTypography,
        LocalOmnixSpacing provides OmnixSpacing,
        LocalOmnixRadius provides OmnixRadius,
        LocalOmnixCoreSizes provides OmnixCoreSizes,
        LocalOmnixElevation provides OmnixElevation,
        LocalOmnixMotion provides motion,
        LocalReducedMotion provides reduced
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = materialTypography,
            content = content
        )
    }
}
