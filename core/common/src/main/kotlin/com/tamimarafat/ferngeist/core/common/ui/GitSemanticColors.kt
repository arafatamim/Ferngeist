package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Stable, theme-independent colors for git semantics (additions vs deletions).
 *
 * These are intentionally NOT derived from the Material dynamic palette: under
 * dynamic color, `primary`/`error` can shift to any hue, but green = added and
 * red = deleted is a universal git convention that must stay recognizable in
 * every theme configuration. Values are fixed per light/dark mode.
 */
data class GitSemanticColors(
    /** Added lines / added files. Always a green. */
    val added: Color,
    /** Deleted lines / deleted files. Always a red. */
    val deleted: Color,
    /** Neutral tone for untracked/renamed/misc status. */
    val neutral: Color,
)

val LightGitSemanticColors =
    GitSemanticColors(
        // Deep green vs brighter red: strong luminance gap so adjacent
        // bars/counts stay distinguishable (green 900 ~0.09 vs red 600 ~0.20).
        added = Color(0xFF1B5E20), // green 900
        deleted = Color(0xFFE53935), // red 600
        neutral = Color(0xFF616161), // gray 700
    )

val DarkGitSemanticColors =
    GitSemanticColors(
        // Very light green vs mid red: a large luminance gap on dark surfaces
        // (green A200 ~0.63 vs red 400 ~0.33) keeps them clearly distinct.
        added = Color(0xFF69F0AE), // green A200
        deleted = Color(0xFFEF5350), // red 400
        neutral = Color(0xFFBDBDBD), // gray 400
    )

val LocalGitSemanticColors = staticCompositionLocalOf { LightGitSemanticColors }
