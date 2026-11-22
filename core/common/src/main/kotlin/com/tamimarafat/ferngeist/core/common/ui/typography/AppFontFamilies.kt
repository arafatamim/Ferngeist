package com.tamimarafat.ferngeist.core.common.ui.typography

import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.tamimarafat.ferngeist.core.common.R

/*
 * Bundled so the theme is available without Play Services or network access. Material's scale
 * takes a brand face for the large roles and a plain face for reading: Anybody and Epilogue, both
 * from Etcetera Type Co. Neither covers Cyrillic or Greek, which fall back to the system face.
 */

private const val WEIGHT_STEP = 100

/** Every step is declared, so a weight a screen asks for is drawn instead of snapped or synthesised. */
private val weights = (FontWeight.Thin.weight..FontWeight.Black.weight step WEIGHT_STEP).map(::FontWeight)

/**
 * Light text on a dark surface reads heavier. The guide counters that with a negative grade;
 * neither face has a grade axis, so dark mode draws each weight a little lighter instead.
 */
private const val DARK_WEIGHT_TRIM = 20f

/** Slightly condensed, so headlines keep Anybody's character and still fit a top app bar. */
private const val BRAND_WIDTH = 90f

/** Epilogue, for titles, body and labels. */
fun plainFontFamily(dark: Boolean): FontFamily =
    FontFamily(
        weights.map { variableFont(R.font.epilogue_wght, it, dark = dark) } +
            weights.map { variableFont(R.font.epilogue_italic_wght, it, FontStyle.Italic, dark) },
    )

/** Anybody, for display, headline and the top app bar's title. */
fun brandFontFamily(dark: Boolean): FontFamily =
    FontFamily(
        weights.map { variableFont(R.font.anybody_wdth_wght, it, dark = dark, width = BRAND_WIDTH) },
    )

internal fun variableFont(
    @FontRes resId: Int,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
    dark: Boolean = false,
    width: Float? = null,
): Font {
    val drawnWeight = FontVariation.Setting("wght", weight.weight - if (dark) DARK_WEIGHT_TRIM else 0f)
    return Font(
        resId = resId,
        weight = weight,
        style = style,
        // Compose matches on the weight above. The font file needs the axis to draw that weight.
        variationSettings =
            if (width == null) {
                FontVariation.Settings(drawnWeight)
            } else {
                FontVariation.Settings(drawnWeight, FontVariation.width(width))
            },
    )
}
