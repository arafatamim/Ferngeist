package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalMediaQueryApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.derivedMediaQuery
import androidx.compose.ui.unit.dp

/**
 * True when the window is narrower than 480dp, i.e. one pane at most. Phone portrait
 * stays single-pane; 7" landscape (600dp) and wider get the multi-pane workspace.
 * Reads `LocalUiMediaScope` internally, so callers need nothing threaded down to
 * them. [derivedMediaQuery] wraps the read in `derivedStateOf`, which matters because
 * `windowWidth` updates on every resize frame.
 */
@OptIn(ExperimentalMediaQueryApi::class)
@Composable
fun isWindowCompact(): Boolean {
    val narrowerThanMedium by derivedMediaQuery {
        windowWidth < 480.dp
    }
    return narrowerThanMedium
}

/**
 * True when the window is shorter than 480dp. Short windows (phone landscape,
 * split-screen, small foldables) get the collapsed single-row app bar everywhere:
 * the expanded flexible header costs too much vertical real estate to ever pay off.
 * Mirrors [isWindowCompact], which gates on width for the same reason.
 */
@OptIn(ExperimentalMediaQueryApi::class)
@Composable
fun isWindowShort(): Boolean {
    val shorterThanMedium by derivedMediaQuery {
        windowHeight < 480.dp
    }
    return shorterThanMedium
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
