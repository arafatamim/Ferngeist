package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors for one syntax role set. Sizes, weights, and the monospace font stay with the text style. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
    val property: Color,
    val variable: Color,
    val operator: Color,
    val punctuation: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
    val constant: Color,
    val escape: Color,
    val markup: Color,
)

/**
 * Derives syntax roles from the active Material scheme, so code follows the user's dynamic color.
 *
 * Material provides three accent families — primary, secondary, tertiary — plus [ColorScheme.error]
 * and the neutral text tones. Roles share those families deliberately rather than inventing
 * near-identical tints: a palette with more nominal roles than the scheme has hues reads as noise.
 *
 * The trade-off is fewer distinct hues than a dedicated editor theme, especially in a monochrome
 * dynamic scheme. [lightSyntaxColors] remains available as a high-separation
 * fixed palette if that trade-off is not wanted.
 */
fun syntaxColorsFor(scheme: ColorScheme): SyntaxColors =
    SyntaxColors(
        keyword = scheme.primary,
        string = scheme.tertiary,
        number = scheme.secondary,
        comment = scheme.onSurfaceVariant,
        function = scheme.secondary,
        type = scheme.tertiary,
        property = scheme.secondary,
        variable = scheme.onSurface,
        operator = scheme.onSurfaceVariant,
        punctuation = scheme.onSurfaceVariant,
        annotation = scheme.primary,
        tag = scheme.secondary,
        attribute = scheme.primary,
        constant = scheme.secondary,
        escape = scheme.error,
        markup = scheme.onSurface,
    )

/**
 * Fixed fallback palette: [localSyntaxColors]'s default for previews and tests. The app provides
 * the dynamic-derived palette from [syntaxColorsFor] instead.
 */
val lightSyntaxColors =
    SyntaxColors(
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        number = Color(0xFF0550AE),
        comment = Color(0xFF6E7781),
        function = Color(0xFF8250DF),
        type = Color(0xFF953800),
        property = Color(0xFF116329),
        variable = Color(0xFF24292F),
        operator = Color(0xFF57606A),
        punctuation = Color(0xFF57606A),
        annotation = Color(0xFF953800),
        tag = Color(0xFF116329),
        attribute = Color(0xFF0550AE),
        constant = Color(0xFF0550AE),
        escape = Color(0xFFCF222E),
        markup = Color(0xFF24292F),
    )

val localSyntaxColors = staticCompositionLocalOf { lightSyntaxColors }
