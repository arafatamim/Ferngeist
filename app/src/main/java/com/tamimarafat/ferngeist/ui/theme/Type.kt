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
 * The app's typeface: Roboto Mono.
 *
 * The `.ttf` files are bundled in `res/font`, and they come FIRST in the family. That ordering is
 * the whole point: a `FontFamily` built only from `GoogleFont`s renders nothing when the download
 * fails, and it fails silently. The device needs Play Services, a resolvable `com.google.android.gms.fonts`
 * provider and a working network; an emulator with no route out, or a device with Play Services
 * disabled, loses the entire theme typography and quietly falls back to a proportional face. That
 * is not a hypothetical — it is exactly what happened on a network-less tablet AVD, where every
 * heading rendered proportional while the code's explicit `FontFamily.Monospace` sites did not.
 *
 * Bundling costs ~790 KB and makes the typeface deterministic on every device, online or not.
 * Italic and medium faces are included because the markdown renderer emits them; without the
 * italic files, italic text would synthesise an oblique slant.
 */
private val AppFontFamily =
    FontFamily(
        Font(R.font.roboto_mono_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.roboto_mono_medium, FontWeight.Medium, FontStyle.Normal),
        Font(R.font.roboto_mono_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.roboto_mono_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.roboto_mono_medium_italic, FontWeight.Medium, FontStyle.Italic),
        Font(R.font.roboto_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

private val BaseTypography =
    Typography(
        bodyLarge =
            TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
    )

val Typography =
    BaseTypography.copy(
        displayLarge = BaseTypography.displayLarge.copy(fontFamily = AppFontFamily),
        displayMedium = BaseTypography.displayMedium.copy(fontFamily = AppFontFamily),
        displaySmall = BaseTypography.displaySmall.copy(fontFamily = AppFontFamily),
        headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = AppFontFamily),
        headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = AppFontFamily),
        headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = AppFontFamily),
        titleLarge = BaseTypography.titleLarge.copy(fontFamily = AppFontFamily),
        titleMedium = BaseTypography.titleMedium.copy(fontFamily = AppFontFamily),
        titleSmall = BaseTypography.titleSmall.copy(fontFamily = AppFontFamily),
        bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = AppFontFamily),
        bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = AppFontFamily),
        bodySmall = BaseTypography.bodySmall.copy(fontFamily = AppFontFamily),
        labelLarge = BaseTypography.labelLarge.copy(fontFamily = AppFontFamily),
        labelMedium = BaseTypography.labelMedium.copy(fontFamily = AppFontFamily),
        labelSmall = BaseTypography.labelSmall.copy(fontFamily = AppFontFamily),
    )
