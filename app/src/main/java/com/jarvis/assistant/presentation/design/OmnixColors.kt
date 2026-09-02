package com.jarvis.assistant.presentation.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * OMNIX colour tokens — the single source of colour for the whole frontend.
 *
 * Specification §3–§5, §54. Rules enforced here:
 *  - the background is never pure `#000000`; it is a felt material;
 *  - text is never pure white, it uses four opacity steps;
 *  - every state colour is semantic and muted — OMNIX is not a neon interface;
 *  - screens must never declare `Color(0x...)` locally.
 */
@Immutable
data class OmnixColorScheme(
    // Surfaces
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val scrim: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,

    // Semantic Core / voice states
    val stateIdle: Color,
    val stateListening: Color,
    val stateRecognizing: Color,
    val stateThinking: Color,
    val stateExecuting: Color,
    val stateSpeaking: Color,
    val stateSuccess: Color,
    val stateError: Color,

    // Interactive
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val actionSecondaryBorder: Color,

    /** True for the dimmed night variant (§54). */
    val isNight: Boolean
)

private val TextPrimaryToken = Color(0xF0FFFFFF)      // 0.94
private val TextSecondaryToken = Color(0x9EFFFFFF)    // 0.62
private val TextTertiaryToken = Color(0x61FFFFFF)     // 0.38
private val TextDisabledToken = Color(0x38FFFFFF)     // 0.22

/** Default dark scheme — the primary OMNIX appearance (§3). */
val OmnixDarkColors = OmnixColorScheme(
    // Sampled from the approved reference: the ground is a very dark blue,
    // not a neutral grey. The blue cast is what makes the coloured Core states
    // read as emitted light instead of as painted strokes.
    background = Color(0xFF01090F),
    surface = Color(0xFF0A1015),
    surfaceElevated = Color(0xFF121A20),
    border = Color(0x14FFFFFF),                        // rgba(255,255,255,0.08)
    scrim = Color(0xB3000000),

    textPrimary = TextPrimaryToken,
    textSecondary = TextSecondaryToken,
    textTertiary = TextTertiaryToken,
    textDisabled = TextDisabledToken,

    // Core state hues, sampled from the reference rings.
    stateIdle = Color(0xFFDFE0E6),
    stateListening = Color(0xFF6FD98D),
    stateRecognizing = Color(0xFFE3DE7A),
    stateThinking = Color(0xFF5FB0F5),
    stateExecuting = Color(0xFF6FD9E8),
    stateSpeaking = Color(0xFFB07FEA),
    stateSuccess = Color(0xFF7FD98A),
    stateError = Color(0xFFFF5A3C),

    actionPrimary = TextPrimaryToken,
    onActionPrimary = Color(0xFF01090F),
    actionSecondaryBorder = Color(0x2EFFFFFF),          // 0.18

    isNight = false
)

/**
 * Night scheme (§54): minimum brightness, low contrast, no bright surfaces.
 * Same tokens, dimmer values — never a second design language.
 */
val OmnixNightColors = OmnixDarkColors.copy(
    background = Color(0xFF000508),
    surface = Color(0xFF060B10),
    surfaceElevated = Color(0xFF0C1318),
    border = Color(0x0FFFFFFF),

    textPrimary = Color(0xD6FFFFFF),
    textSecondary = Color(0x80FFFFFF),
    textTertiary = Color(0x4DFFFFFF),
    textDisabled = Color(0x2BFFFFFF),

    stateIdle = Color(0xFFA9AEB3),
    stateListening = Color(0xFF7FBF91),
    stateRecognizing = Color(0xFFC0B272),
    stateThinking = Color(0xFF7EA5D1),
    stateExecuting = Color(0xFF77BFC8),
    stateSpeaking = Color(0xFFA18BD1),
    stateSuccess = Color(0xFF8AC080),
    stateError = Color(0xFFD1615B),

    actionPrimary = Color(0xD6FFFFFF),
    isNight = true
)

/**
 * Light scheme. The product is voice-first and designed dark; light exists so
 * that `Appearance = System/Light` (§47) is honest rather than ignored.
 */
val OmnixLightColors = OmnixColorScheme(
    background = Color(0xFFF6F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    border = Color(0x14000000),
    scrim = Color(0x66000000),

    textPrimary = Color(0xF0121416),
    textSecondary = Color(0x9E121416),
    textTertiary = Color(0x61121416),
    textDisabled = Color(0x38121416),

    stateIdle = Color(0xFF6C7276),
    stateListening = Color(0xFF2F8B50),
    stateRecognizing = Color(0xFF8A7420),
    stateThinking = Color(0xFF2E6BB0),
    stateExecuting = Color(0xFF1F7E8B),
    stateSpeaking = Color(0xFF6A4CB0),
    stateSuccess = Color(0xFF2F8B50),
    stateError = Color(0xFFC0322A),

    actionPrimary = Color(0xFF121416),
    onActionPrimary = Color(0xFFF6F7F8),
    actionSecondaryBorder = Color(0x2E000000),

    isNight = false
)
