package com.tamimarafat.ferngeist.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.tamimarafat.ferngeist.core.common.ui.typography.brandFontFamily
import com.tamimarafat.ferngeist.core.common.ui.typography.plainFontFamily
import androidx.compose.material3.Typography as MaterialTypography

/** Epilogue sets wider than Roboto, so Roboto's positive tracking leaves its text airy. */
private const val PLAIN_TRACKING = 0.4f

/**
 * Display, headline and title large take the brand face; the rest takes the plain face. Title
 * large is the collapsed top app bar title: sharing the headline's family keeps the bar's lerp
 * smooth, because a family change cannot interpolate and would jump mid-collapse. Map the
 * emphasized roles too, so they use the same family as the base scale.
 */
private fun appTypography(
    brand: FontFamily,
    plain: FontFamily,
): MaterialTypography {
    fun TextStyle.brand() = copy(fontFamily = brand)

    fun TextStyle.plain() = copy(fontFamily = plain, letterSpacing = letterSpacing * PLAIN_TRACKING)

    return with(MaterialTypography()) {
        copy(
            displayLarge = displayLarge.brand(),
            displayMedium = displayMedium.brand(),
            displaySmall = displaySmall.brand(),
            headlineLarge = headlineLarge.brand(),
            headlineMedium = headlineMedium.brand(),
            headlineSmall = headlineSmall.brand(),
            titleLarge = titleLarge.brand(),
            titleMedium = titleMedium.plain(),
            titleSmall = titleSmall.plain(),
            bodyLarge = bodyLarge.plain(),
            bodyMedium = bodyMedium.plain(),
            bodySmall = bodySmall.plain(),
            labelLarge = labelLarge.plain(),
            labelMedium = labelMedium.plain(),
            labelSmall = labelSmall.plain(),
            displayLargeEmphasized = displayLargeEmphasized.brand(),
            displayMediumEmphasized = displayMediumEmphasized.brand(),
            displaySmallEmphasized = displaySmallEmphasized.brand(),
            headlineLargeEmphasized = headlineLargeEmphasized.brand(),
            headlineMediumEmphasized = headlineMediumEmphasized.brand(),
            headlineSmallEmphasized = headlineSmallEmphasized.brand(),
            titleLargeEmphasized = titleLargeEmphasized.brand(),
            titleMediumEmphasized = titleMediumEmphasized.plain(),
            titleSmallEmphasized = titleSmallEmphasized.plain(),
            bodyLargeEmphasized = bodyLargeEmphasized.plain(),
            bodyMediumEmphasized = bodyMediumEmphasized.plain(),
            bodySmallEmphasized = bodySmallEmphasized.plain(),
            labelLargeEmphasized = labelLargeEmphasized.plain(),
            labelMediumEmphasized = labelMediumEmphasized.plain(),
            labelSmallEmphasized = labelSmallEmphasized.plain(),
        )
    }
}

private val lightTypography = appTypography(brandFontFamily(dark = false), plainFontFamily(dark = false))
private val darkTypography = appTypography(brandFontFamily(dark = true), plainFontFamily(dark = true))

fun appTypography(dark: Boolean): MaterialTypography = if (dark) darkTypography else lightTypography
