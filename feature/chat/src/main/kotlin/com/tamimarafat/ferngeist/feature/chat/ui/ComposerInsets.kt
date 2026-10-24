package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.chat.ChatState

internal data class ComposerInsets(
    val imeBottomPx: Int,
    val showComposerToolbar: Boolean,
    val listBottomPadding: Dp,
    val bottomFadeBandHeight: Dp,
    val snackbarBottomPadding: Dp,
)

@Composable
internal fun rememberComposerInsets(
    state: ChatState,
    composerContentHeightPx: MutableState<Int>,
): ComposerInsets {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val systemBottomInsetPx = if (imeBottomPx > navBottomPx) imeBottomPx else navBottomPx

    // Composer is hidden on error + empty state, and while the initial load
    // has no content yet. Cached messages stay typable during markdown
    // hydration: sends queue offline until the session is ready.
    val showComposerToolbar =
        !(state.error != null && state.messages.isEmpty()) &&
            (!state.isLoading || state.messages.isNotEmpty())
    val composerContentHeightDp = with(density) { composerContentHeightPx.value.toDp() }
    val systemBottomInsetDp = with(density) { systemBottomInsetPx.toDp() }

    // Bottom padding for message list: composer height + floating offset + 36dp, plus any
    // system inset the floating composer does not already sit above. The window does not
    // resize for the IME (edge-to-edge + targetSdk 37), so without this the follow-to-bottom
    // path targets the un-inset screen bottom and the last message settles behind the keyboard.
    val listBottomPadding =
        remember(showComposerToolbar, composerContentHeightDp, systemBottomInsetDp) {
            if (!showComposerToolbar) {
                0.dp
            } else {
                composerContentHeightDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    36.dp +
                    systemBottomInsetDp
            }
        }

    // Snackbar also needs to sit above the composer bar but with less extra padding (16dp)
    val snackbarBottomPadding =
        remember(showComposerToolbar, composerContentHeightDp, systemBottomInsetDp) {
            if (!showComposerToolbar) {
                0.dp
            } else {
                composerContentHeightDp +
                    systemBottomInsetDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    16.dp
            }
        }

    // How far the fade climbs from the bottom of the screen: the composer (plus
    // the system bar beneath it) rises this far, and the band starts a little
    // above the composer's own top edge so content begins to dim just before it
    // slides under the pill.
    //
    // [bottomFadeLead] must stay under the 36dp of clearance the list keeps
    // above the composer (see [listBottomPadding]), otherwise the last message
    // would be tinted while at rest.
    val bottomFadeBandHeight =
        remember(showComposerToolbar, composerContentHeightDp, systemBottomInsetDp) {
            if (!showComposerToolbar) {
                0.dp
            } else {
                composerContentHeightDp +
                    systemBottomInsetDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    bottomFadeLead
            }
        }
    return ComposerInsets(
        imeBottomPx = imeBottomPx,
        showComposerToolbar = showComposerToolbar,
        listBottomPadding = listBottomPadding,
        bottomFadeBandHeight = bottomFadeBandHeight,
        snackbarBottomPadding = snackbarBottomPadding,
    )
}

// How far above the composer's top edge the bottom fade begins.
private val bottomFadeLead = 24.dp
