package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private const val BODY_MAX_WIDTH_FRACTION = 0.85f

/**
 * Shared error/empty-state card used across feature surfaces.
 *
 * Renders a medallion (expressive [Shape] in a themed container), a bold
 * headline, a quiet body line, and a single CTA — the same anatomy as the
 * server list's empty state, promoted to a reusable component.
 *
 * Everything is theme-token-driven (dark mode / dynamic color safe). The
 * card is fixed-height with no scrollable inside: it is meant to sit inside
 * whatever scroll owner the caller already has (never nested scrollables —
 * see the crash class fixed in c4dba44).
 *
 * @param headline Bold headline text.
 * @param body Secondary body text.
 * @param icon Medallion icon.
 * @param medallionContainer Medallion fill color (e.g. errorContainer,
 *   primaryContainer, surfaceContainerHigh).
 * @param medallionContent Medallion icon tint (e.g. onErrorContainer,
 *   onPrimaryContainer, onSurfaceVariant).
 * @param medallionShape Expressive shape for the medallion (e.g.
 *   MaterialShapes.VerySunny.toShape() for errors, Cookie9Sided for empty).
 * @param ctaLabel CTA label.
 * @param onCta CTA click.
 * @param ctaIsHero When true the CTA is a filled [Button] (empty-state hero);
 *   otherwise a quieter [FilledTonalButton] (recovery action).
 * @param modifier Applied to the root column.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ErrorStateCard(
    headline: String,
    body: String,
    icon: ImageVector,
    medallionContainer: Color,
    medallionContent: Color,
    medallionShape: Shape,
    ctaLabel: String,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
    ctaIsHero: Boolean = false,
) {
    val medallionScale by rememberMedallionScale()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorStateMedallion(
            icon = icon,
            medallionContainer = medallionContainer,
            medallionContent = medallionContent,
            medallionShape = medallionShape,
            scale = medallionScale,
        )

        Spacer(modifier = Modifier.height(4.dp))

        ErrorStateContent(
            headline = headline,
            body = body,
            ctaLabel = ctaLabel,
            ctaIsHero = ctaIsHero,
            onCta = onCta,
        )
    }
}

/**
 * Computes the one-shot entrance scale for the medallion.
 *
 * The medallion scales in; content fades in. Errors are rare, so a subtle
 * delight is warranted; reduced motion snaps to final. System "Remove
 * animations" (animator duration scale = 0) → snap, no motion.
 */
@Composable
private fun rememberMedallionScale(): State<Float> {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val reduceMotion =
        remember(context) {
            if (isPreview) {
                false
            } else {
                android.provider.Settings.Global.getFloat(
                    context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) == 0f
            }
        }
    val springSpec =
        spring<Float>(
            dampingRatio = if (reduceMotion) Spring.DampingRatioNoBouncy else Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    return animateFloatAsState(
        targetValue = if (entered) 1f else 0.9f,
        animationSpec = springSpec,
        label = "ErrorStateCardMedallionScale",
        visibilityThreshold = 0.001f,
    )
}

@Composable
private fun ErrorStateMedallion(
    icon: ImageVector,
    medallionContainer: Color,
    medallionContent: Color,
    medallionShape: Shape,
    scale: Float,
) {
    Box(
        modifier =
            Modifier
                .scale(scale)
                .size(84.dp)
                .clip(medallionShape)
                .background(medallionContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = medallionContent,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun ErrorStateContent(
    headline: String,
    body: String,
    ctaLabel: String,
    ctaIsHero: Boolean,
    onCta: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(BODY_MAX_WIDTH_FRACTION),
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (ctaIsHero) {
            Button(
                onClick = onCta,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(ctaLabel)
            }
        } else {
            FilledTonalButton(onClick = onCta) {
                Text(ctaLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Error state (light)", showBackground = true)
@Composable
private fun ErrorStateCardErrorPreview() {
    MaterialExpressivePreview {
        ErrorStateCard(
            headline = "Couldn't load this session",
            body = "Disconnected. Reconnect to refresh this session.",
            icon = Icons.Rounded.CloudOff,
            medallionContainer = MaterialTheme.colorScheme.errorContainer,
            medallionContent = MaterialTheme.colorScheme.onErrorContainer,
            medallionShape = MaterialShapes.VerySunny.toShape(),
            ctaLabel = "Retry",
            onCta = {},
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Empty state (light)", showBackground = true)
@Composable
private fun ErrorStateCardEmptyPreview() {
    MaterialExpressivePreview {
        ErrorStateCard(
            headline = "No sessions yet",
            body = "Start a new session or resume one from the list.",
            icon = Icons.Rounded.Forum,
            medallionContainer = MaterialTheme.colorScheme.primaryContainer,
            medallionContent = MaterialTheme.colorScheme.onPrimaryContainer,
            medallionShape = MaterialShapes.Cookie9Sided.toShape(),
            ctaLabel = "New session",
            onCta = {},
            ctaIsHero = true,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Empty state (dark)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ErrorStateCardEmptyDarkPreview() {
    MaterialExpressivePreview(darkTheme = true) {
        ErrorStateCard(
            headline = "No sessions yet",
            body = "Start a new session or resume one from the list.",
            icon = Icons.Rounded.Forum,
            medallionContainer = MaterialTheme.colorScheme.primaryContainer,
            medallionContent = MaterialTheme.colorScheme.onPrimaryContainer,
            medallionShape = MaterialShapes.Cookie9Sided.toShape(),
            ctaLabel = "New session",
            onCta = {},
            ctaIsHero = true,
        )
    }
}

/**
 * Minimal wrapper so previews exercise the real theme (dark/light schemes,
 * expressive shapes) without depending on app's FerngeistTheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialExpressivePreview(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialExpressiveTheme(
        colorScheme = scheme,
        content = content,
    )
}
