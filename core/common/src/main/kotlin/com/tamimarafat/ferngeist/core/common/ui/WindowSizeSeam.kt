package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalMediaQueryApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiMediaScope
import androidx.compose.ui.derivedMediaQuery
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * True when the window is narrower than the *medium* width class (< 600dp), i.e. one pane
 * at most. Reads `LocalUiMediaScope` internally, so callers need nothing threaded down to
 * them. [derivedMediaQuery] wraps the read in `derivedStateOf`, which matters because
 * `windowWidth` updates on every resize frame.
 */
@OptIn(ExperimentalMediaQueryApi::class)
@Composable
fun isWindowCompact(): Boolean {
    val narrowerThanMedium by derivedMediaQuery {
        windowWidth < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    }
    return narrowerThanMedium
}

/** True when the device is half-folded like a laptop — content above the fold, controls below. */
@OptIn(ExperimentalMediaQueryApi::class)
@Composable
fun isWindowTabletop(): Boolean {
    val tabletop by derivedMediaQuery { windowPosture == UiMediaScope.Posture.Tabletop }
    return tabletop
}

/**
 * Keeps single-column form content readable on wide windows. Large screens should not
 * stretch text fields and full-width buttons across the whole window.
 *
 * Apply as the OUTERMOST modifier in the chain, next to an alignment that centers the
 * result: a `widthIn` placed after `fillMaxSize()`/`fillMaxWidth()` is a no-op, because
 * those hand the inner node a fixed width it then cannot shrink.
 */
fun Modifier.formContentMaxWidth(): Modifier = this.widthIn(max = 640.dp)
