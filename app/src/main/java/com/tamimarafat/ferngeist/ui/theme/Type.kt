package com.tamimarafat.ferngeist.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tamimarafat.ferngeist.R

/**
 * The app's typeface: Geist Mono (Vercel, OFL-1.1).
 *
 * Bundled, never downloaded. A `FontFamily` built from `GoogleFont`s renders nothing when the
 * download fails, and it fails silently: it needs Play Services, a resolvable
 * `com.google.android.gms.fonts` provider and a working network. An emulator with no route out
 * loses the entire theme typography and quietly falls back to a proportional face. That is not
 * hypothetical — it is exactly what happened on a network-less tablet AVD, where every heading
 * rendered proportional while the code's explicit `FontFamily.Monospace` sites did not.
 *
 * Two variable files replace Roboto Mono's six static faces. `wght` spans 100..900, so the three
 * weights the theme asks for are instances of one font rather than three files — 355 KB against
 * 790 KB, and any weight between them is free. Both the upright and the italic carry the axis, so
 * markdown emphasis is a real italic face rather than a synthesised oblique slant, which is what
 * the previous static cut had to carry two extra files for.
 */
private val AppFontFamily =
    FontFamily(
        Font(R.font.geist_mono_wght, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.geist_mono_wght, FontWeight.Medium, FontStyle.Normal),
        Font(R.font.geist_mono_wght, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.geist_mono_italic_wght, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.geist_mono_italic_wght, FontWeight.Medium, FontStyle.Italic),
        Font(R.font.geist_mono_italic_wght, FontWeight.Bold, FontStyle.Italic),
    )

/**
 * Optical size trim for a monospaced face.
 *
 * Material's scale was tuned for a proportional UI face, and a monospaced face sits differently
 * on it: every character takes the same advance, so a line is as wide as its widest glyph.
 *
 * This is 1.0, not a guess — it was 0.9 while the app used Martian Mono, whose advance is
 * unusually wide and read oversized at the stock scale. Geist Mono has a conventional monospace
 * advance, so it takes Material's sizes as-is and trimming them undershot. Left as a named dial
 * because the right value is a judgement about the face, not something the scale implies.
 *
 * Tracking is dropped regardless: monospace already spaces its own glyphs, and positive tracking
 * only widens lines that are already at their widest.
 */
private const val OPTICAL_SCALE = 1.0f

private fun TextStyle.forGeistMono(): TextStyle =
    copy(
        fontFamily = AppFontFamily,
        fontSize = fontSize * OPTICAL_SCALE,
        lineHeight = lineHeight * OPTICAL_SCALE,
        letterSpacing = 0.sp,
    )

private val BaseTypography =
    Typography(
        bodyLarge =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ).forGeistMono(),
    )

val Typography =
    BaseTypography.copy(
        displayLarge = BaseTypography.displayLarge.forGeistMono(),
        displayMedium = BaseTypography.displayMedium.forGeistMono(),
        displaySmall = BaseTypography.displaySmall.forGeistMono(),
        headlineLarge = BaseTypography.headlineLarge.forGeistMono(),
        headlineMedium = BaseTypography.headlineMedium.forGeistMono(),
        headlineSmall = BaseTypography.headlineSmall.forGeistMono(),
        titleLarge = BaseTypography.titleLarge.forGeistMono(),
        titleMedium = BaseTypography.titleMedium.forGeistMono(),
        titleSmall = BaseTypography.titleSmall.forGeistMono(),
        bodyLarge = BaseTypography.bodyLarge.forGeistMono(),
        bodyMedium = BaseTypography.bodyMedium.forGeistMono(),
        bodySmall = BaseTypography.bodySmall.forGeistMono(),
        labelLarge = BaseTypography.labelLarge.forGeistMono(),
        labelMedium = BaseTypography.labelMedium.forGeistMono(),
        labelSmall = BaseTypography.labelSmall.forGeistMono(),
    )
