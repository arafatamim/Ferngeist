package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.chat.ChatState

/**
 * The bottom clearances follow the keyboard frame by frame, so they are states read where they are
 * used. Reading the IME inset here, at screen level, recomposed the whole chat screen on every
 * frame of the keyboard animation, which runs alongside the FAB-to-composer transform.
 */
internal class ComposerInsets(
    val showComposerToolbar: Boolean,
    val listBottomPadding: State<Dp>,
    val bottomFadeBandHeight: State<Dp>,
    val snackbarBottomPadding: State<Dp>,
)

@Composable
internal fun rememberComposerInsets(
    state: ChatState,
    composerContentHeightPx: MutableState<Int>,
): ComposerInsets {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars

    // Composer is hidden on error + empty state, and while the initial load
    // has no content yet. Cached messages stay typable during markdown
    // hydration: sends queue offline until the session is ready.
    val showComposerToolbar =
        !(state.error != null && state.messages.isEmpty()) &&
            (!state.isLoading || state.messages.isNotEmpty())

    // The composer plus the system inset it floats above, and the 0dp a hidden composer clears.
    fun clearance(extra: Dp): State<Dp> =
        derivedStateOf {
            if (!showComposerToolbar) return@derivedStateOf 0.dp
            val systemBottomInsetPx = maxOf(ime.getBottom(density), navigationBars.getBottom(density))
            with(density) { (composerContentHeightPx.value + systemBottomInsetPx).toDp() } +
                FloatingToolbarDefaults.ScreenOffset +
                extra
        }

    return remember(showComposerToolbar, density, ime, navigationBars, composerContentHeightPx) {
        ComposerInsets(
            showComposerToolbar = showComposerToolbar,
            // Bottom padding for message list: composer height + floating offset + 36dp, plus any
            // system inset the floating composer does not already sit above. The window does not
            // resize for the IME (edge-to-edge + targetSdk 37), so without this the follow-to-bottom
            // path targets the un-inset screen bottom and the last message settles behind the keyboard.
            listBottomPadding = clearance(36.dp),
            // How far the fade climbs from the bottom of the screen: the band starts a little
            // above the composer's own top edge so content begins to dim just before it slides
            // under the pill. [bottomFadeLead] must stay under the 36dp of clearance the list
            // keeps above the composer, otherwise the last message would be tinted while at rest.
            bottomFadeBandHeight = clearance(bottomFadeLead),
            // Snackbar also needs to sit above the composer bar but with less extra padding.
            snackbarBottomPadding = clearance(16.dp),
        )
    }
}

// How far above the composer's top edge the bottom fade begins. Kept under
// the 36dp of clearance the list maintains so the last message never sits in
// the band at rest.
private val bottomFadeLead = 32.dp
