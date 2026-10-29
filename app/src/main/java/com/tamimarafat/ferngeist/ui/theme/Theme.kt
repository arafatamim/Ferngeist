package com.tamimarafat.ferngeist.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.material3.dynamicColorScheme
import com.tamimarafat.ferngeist.core.common.ui.darkGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.lightGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.core.common.ui.syntaxColorsFor

internal fun fernColorScheme(dark: Boolean) = dynamicColorScheme(seedColor = fernSeed, isDark = dark)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FerngeistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            remember(darkTheme) { fernColorScheme(darkTheme) }
        }
    val gitSemanticColors = if (darkTheme) darkGitSemanticColors else lightGitSemanticColors
    val syntaxColors = remember(colorScheme) { syntaxColorsFor(colorScheme) }

    CompositionLocalProvider(
        localGitSemanticColors provides gitSemanticColors,
        localSyntaxColors provides syntaxColors,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            // Physics-based motion as the app-wide default (springs over fixed-duration easing).
            motionScheme = MotionScheme.expressive(),
            typography = appTypography,
            content = content,
        )
    }
}
