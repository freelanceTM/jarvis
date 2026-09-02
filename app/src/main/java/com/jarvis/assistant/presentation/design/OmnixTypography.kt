package com.jarvis.assistant.presentation.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OMNIX typography tokens (§6).
 *
 * "Typography should disappear into the product": one family, few weights,
 * six roles. Sizes are declared in `sp` so system font scaling works (§30).
 * Screens must not declare `fontSize = 15.sp` locally.
 */
@Immutable
data class OmnixTypographyTokens(
    /** Product wordmark and first-run statements. */
    val display: TextStyle,
    /** Screen titles: Settings, Devices, History. */
    val screenTitle: TextStyle,
    /** Section and card headings. */
    val heading: TextStyle,
    /** Default reading text and primary state labels. */
    val body: TextStyle,
    /** Guidance, secondary explanations, examples. */
    val caption: TextStyle,
    /** Status line: "Listening…", "Clip Connected". */
    val status: TextStyle,
    /** Small overline labels: "TODAY", "YOU", "OMNIX". */
    val overline: TextStyle
)

private val Sans = FontFamily.SansSerif

val OmnixTypography = OmnixTypographyTokens(
    display = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.2).sp
    ),
    screenTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.1).sp
    ),
    heading = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    body = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    caption = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    status = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    overline = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.22.sp
    )
)

/** The OMNIX wordmark: the only place with wide tracking (§23, §79). */
val OmnixWordmarkStyle: TextStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 2.8.sp
)
