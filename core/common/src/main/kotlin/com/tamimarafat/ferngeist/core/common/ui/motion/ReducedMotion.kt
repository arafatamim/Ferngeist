package com.tamimarafat.ferngeist.core.common.ui.motion

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * True when the system "Remove animations" setting is on (animator duration scale = 0).
 * Previews always animate: the setting means nothing there.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    return remember(context, isPreview) {
        !isPreview &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
