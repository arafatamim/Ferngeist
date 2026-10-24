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
import com.tamimarafat.ferngeist.core.common.ui.darkGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.lightGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.core.common.ui.syntaxColorsFor
import androidx.compose.material3.darkColorScheme as materialDarkColorScheme
import androidx.compose.material3.lightColorScheme as materialLightColorScheme

private val darkColorScheme =
    materialDarkColorScheme(
        primary = purple80,
        secondary = purpleGrey80,
        tertiary = pink80,
    )

private val lightColorScheme =
    materialLightColorScheme(
        primary = purple40,
        secondary = purpleGrey40,
        tertiary = pink40,
    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
     */
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FerngeistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> darkColorScheme
            else -> lightColorScheme
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
