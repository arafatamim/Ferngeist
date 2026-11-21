package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.iconUrl

/**
 * The connecting sun's size. Matches the indicator this replaced, so the mark is
 * drawn at exactly the size the app always showed for a connecting card.
 */
private val loadingSunSize = 24.dp

/** The sun's arrival: overshoots slightly then settles, as the old indicator did. */
private val busyPopSpec =
    spring<Float>(dampingRatio = 0.62f, stiffness = 500f)

/** Cross-fade leg of the swap, kept short so the pop reads as the motion. */
private const val FADE_MILLIS = 150

/** The connecting mark pops in while the previous symbol fades away. */
fun AnimatedContentTransitionScope<Boolean>.agentIconTransition(): ContentTransform =
    (fadeIn(animationSpec = tween(FADE_MILLIS)) + scaleIn(animationSpec = busyPopSpec, initialScale = 0.8f))
        .togetherWith(fadeOut(animationSpec = tween(FADE_MILLIS)))
        .using(SizeTransform(clip = false))

/**
 * An agent's registry logo, or [fallback] when it has none.
 *
 * The ACP registry publishes logos as SVG — monochrome masks rather than brand art —
 * so the logo sits in the same tonal role as the glyph it replaces instead of
 * fighting the theme. It is drawn bare: the surface that shows it owns the frame, so
 * an identity mark can be a filled square in one place and part of a clover in another.
 *
 * The logo is identity, never content: the name is always shown alongside it. Custom
 * and embedded agents, including every agent this app adds itself, have no registry
 * entry and therefore never resolve a URL; a failed load shows the fallback too, so an
 * unreachable CDN degrades to an initial or glyph rather than leaving an empty gap.
 *
 * When [loading] is set it shows the app's busy sun in place of the logo, crossfaded.
 *
 * @param size the logo's size. Draws nothing larger than this; the loading sun keeps
 *   its own [loadingSunSize] and is centred in whatever slot the caller gives it.
 * @param loading shows the busy sun instead of the logo.
 */
@Composable
fun AgentLogo(
    iconUrl: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
    loading: Boolean = false,
    fallbackName: String? = null,
) {
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            // The old indicator popped in with an overshoot; a plain crossfade read
            // flat by comparison. Scaling from a touch under the resting size gives
            // the mark the same springy arrival it always had, in the same direction.
            agentIconTransition()
        },
        contentAlignment = Alignment.Center,
        label = "agentLogoBusy",
    ) { isLoading ->
        if (isLoading) {
            LoadingSun(modifier = modifier)
        } else {
            Box(modifier = modifier) {
                if (iconUrl == null) {
                    FallbackGlyph(fallback, size, tint, contentDescription, fallbackName)
                } else {
                    var failed by remember(iconUrl) { mutableStateOf(false) }
                    Crossfade(targetState = failed, label = "agentLogoFallback") { isFailed ->
                        if (isFailed) {
                            FallbackGlyph(fallback, size, tint, contentDescription, fallbackName)
                        } else {
                            // Registry logos are `currentColor` masks, so they are
                            // recolored to the caller's tint rather than shipped in
                            // one fixed brand color. Without this the image renders
                            // in whatever the SVG's own fill says, which on the hero
                            // card meant a black mark on a dark clover.
                            SubcomposeAsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(LocalContext.current)
                                        .data(iconUrl)
                                        .build(),
                                contentDescription = contentDescription,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(size),
                                colorFilter = ColorFilter.tint(tint),
                                loading = {},
                                error = { failed = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Convenience overload for a launchable target, whose icon URL comes from its binding. */
@Composable
fun AgentLogo(
    target: LaunchableTarget,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
    loading: Boolean = false,
) = AgentLogo(
    iconUrl = target.iconUrl,
    fallback = fallback,
    modifier = modifier,
    size = size,
    tint = tint,
    contentDescription = contentDescription,
    loading = loading,
    fallbackName = if (target is LaunchableTarget.GatewayAgent) target.name else null,
)

@Composable
private fun FallbackGlyph(
    fallback: ImageVector,
    size: Dp,
    tint: Color,
    contentDescription: String?,
    fallbackName: String?,
) {
    val initial = fallbackName?.trim()?.firstOrNull()?.uppercase()
    if (initial != null) {
        Text(
            text = initial,
            color = tint.copy(alpha = tint.alpha * 0.6f),
            fontSize = with(LocalDensity.current) { size.toSp() },
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            modifier =
                Modifier.clearAndSetSemantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                },
        )
    } else {
        Icon(
            imageVector = fallback,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * The app's connection mark: the same sun, rotation and color as the server card's
 * connecting indicator, so the affordance is unchanged from what the app already showed.
 * It draws at [loadingSunSize] and is centred in the caller's slot, which reserves the
 * footprint the logo had so the swap costs the row no layout shift.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingSun(modifier: Modifier = Modifier) {
    val rotation by
        rememberInfiniteTransition(label = "agentLogoBusy").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(4200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "agentLogoBusyRotation",
        )
    Box(modifier = modifier.size(loadingSunSize), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(loadingSunSize)
                    .rotate(rotation)
                    .clip(MaterialShapes.VerySunny.toShape())
                    .background(MaterialTheme.colorScheme.secondary),
        )
    }
}
