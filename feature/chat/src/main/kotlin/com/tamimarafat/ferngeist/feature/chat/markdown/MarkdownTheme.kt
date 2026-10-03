/*
 * Started from com.adamglin.compose.markdown:markdown-compose 0.1.5 (MIT, (c) 2026 Adam Glin),
 * cut down to what the chat uses. Upstream resolves its own palette and type scale; here both come
 * from the app's Material theme, so markdown follows light/dark and dynamic colour like the rest
 * of the bubble. See MarkdownBlocks.kt for why the renderer is vendored.
 */

package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle

@Immutable
internal data class MarkdownColors(
    val textSecondary: Color,
    val surfaceMuted: Color,
    val accent: Color,
    val border: Color,
    val borderMuted: Color,
)

@Immutable
internal data class MarkdownTypography(
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val labelMedium: TextStyle,
) {
    /**
     * Gives every role a colour. The renderer draws with `BasicText`, which paints an unspecified
     * colour black, and Material type styles carry none, so without this markdown was black text
     * in dark mode.
     */
    fun withTextColors(
        primary: Color,
        secondary: Color,
    ): MarkdownTypography {
        fun TextStyle.tinted(color: Color) = if (this.color.isSpecified) this else copy(color = color)
        return MarkdownTypography(
            headlineLarge = headlineLarge.tinted(primary),
            headlineMedium = headlineMedium.tinted(primary),
            headlineSmall = headlineSmall.tinted(primary),
            titleLarge = titleLarge.tinted(primary),
            titleMedium = titleMedium.tinted(primary),
            titleSmall = titleSmall.tinted(primary),
            bodyLarge = bodyLarge.tinted(primary),
            bodyMedium = bodyMedium.tinted(primary),
            labelMedium = labelMedium.tinted(secondary),
        )
    }
}

private val LocalMarkdownColors = staticCompositionLocalOf<MarkdownColors> { error("No ProvideMarkdownTheme") }
private val LocalMarkdownTypography =
    staticCompositionLocalOf<MarkdownTypography> { error("No ProvideMarkdownTheme") }

internal object MarkdownTheme {
    val colors: MarkdownColors
        @Composable get() = LocalMarkdownColors.current

    val typography: MarkdownTypography
        @Composable get() = LocalMarkdownTypography.current
}

/** Provides [typography] and the Material-derived colours to the blocks inside. */
@Composable
internal fun ProvideMarkdownTheme(
    typography: MarkdownTypography,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors =
        remember(scheme) {
            MarkdownColors(
                textSecondary = scheme.onSurfaceVariant,
                surfaceMuted = scheme.surfaceContainerHighest,
                accent = scheme.primary,
                border = scheme.outline,
                borderMuted = scheme.outlineVariant,
            )
        }
    val tinted = remember(typography, scheme) { typography.withTextColors(scheme.onSurface, scheme.onSurfaceVariant) }
    CompositionLocalProvider(
        LocalMarkdownColors provides colors,
        LocalMarkdownTypography provides tinted,
        content = content,
    )
}
