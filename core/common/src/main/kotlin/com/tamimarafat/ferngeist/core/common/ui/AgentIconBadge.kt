package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.iconUrl

/** Tool call card's container-to-icon ratio (32dp slot, 20dp icon). */
private const val CONTAINER_ICON_RATIO = 1.6f

/**
 * The connecting sun's size. Matches the indicator this replaced, so the mark is
 * drawn at exactly the size the app always showed for a connecting card.
 */
private val LOADING_SUN_SIZE = 24.dp

/** The sun's arrival: overshoots slightly then settles, as the old indicator did. */
private val BUSY_POP_SPEC =
    spring<Float>(dampingRatio = 0.62f, stiffness = 500f)

/** Cross-fade leg of the swap, kept short so the pop reads as the motion. */
private const val FADE_MILLIS = 150

/**
 * An agent's registry logo, or [fallback] when it has none.
 *
 * The ACP registry publishes logos as SVG — monochrome masks rather than brand art —
 * so the logo sits in the same tonal role as the glyph it replaces instead of
 * fighting the theme.
 *
 * The logo is carried in a rounded container, matching the tool call card's status
 * indicator: a bare glyph on a card reads as loose decoration, while a contained one
 * reads as an avatar slot and lines up with the text beside it. The container is
 * neutral rather than the tool call card's `primary` fill — these are identity marks,
 * not status, and a tinted chip on every card would be noise.
 *
 * The badge is identity, never content: the name is always shown alongside it. Custom
 * and embedded agents, including every agent this app adds itself, have no registry
 * entry and therefore never resolve a URL; a failed load shows [fallback] too, so an
 * unreachable CDN degrades to the existing glyph rather than leaving an empty gap.
 *
 * When [loading] is set the badge shows the app's busy sun in place of the logo,
 * crossfaded. The sun is deliberately *not* wrapped in the rounded container: it is
 * the app's established connection affordance and already reads as a standalone mark
 * elsewhere, so enclosing it would change how it looks rather than just where it
 * appears. It covers the same [containerSize] footprint the logo did, so the swap
 * costs the row no layout shift.
 *
 * @param size the logo's size; the container is [containerSize] around it.
 * @param containerSize the rounded slot. Defaults to 1.6x [size], the tool call
 *   card's container-to-icon ratio, so the two marks read as the same kind of thing.
 * @param loading shows the busy sun instead of the logo.
 */
@Composable
fun AgentIconBadge(
    iconUrl: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
    containerSize: Dp? = size * CONTAINER_ICON_RATIO,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    loading: Boolean = false,
) {
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            // The old indicator popped in with an overshoot; a plain crossfade read
            // flat by comparison. Scaling from a touch under the resting size gives
            // the mark the same springy arrival it always had, in the same direction.
            (fadeIn(animationSpec = tween(FADE_MILLIS)) + scaleIn(animationSpec = BUSY_POP_SPEC, initialScale = 0.8f))
                .togetherWith(fadeOut(animationSpec = tween(FADE_MILLIS)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "agentIconBusy",
    ) { isLoading ->
        if (isLoading) {
            LoadingSun(
                size = LOADING_SUN_SIZE,
                containerSize = containerSize ?: size * CONTAINER_ICON_RATIO,
                modifier = modifier,
            )
        } else {
            BadgeContainer(modifier = modifier, containerSize = containerSize, containerColor = containerColor) {
                if (iconUrl == null) {
                    FallbackGlyph(fallback, size, tint, contentDescription)
                } else {
                    var failed by remember(iconUrl) { mutableStateOf(false) }
                    Crossfade(targetState = failed, label = "agentIconFallback") { isFailed ->
                        if (isFailed) {
                            FallbackGlyph(fallback, size, tint, contentDescription)
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
fun AgentIconBadge(
    target: LaunchableTarget,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
    containerSize: Dp? = size * CONTAINER_ICON_RATIO,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    loading: Boolean = false,
) = AgentIconBadge(
    iconUrl = target.iconUrl,
    fallback = fallback,
    modifier = modifier,
    size = size,
    tint = tint,
    contentDescription = contentDescription,
    containerSize = containerSize,
    containerColor = containerColor,
    loading = loading,
)

/**
 * The tool call card's status indicator, in neutral colors: a rounded container
 * that keeps the logo in the same slot and proportion as the icon it replaces.
 */
@Composable
private fun BadgeContainer(
    containerSize: Dp?,
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (containerSize == null) {
        // The caller supplies its own shaped backdrop (the hero card's clover), so
        // adding a frame here would stack one shape inside another.
        Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
        return
    }
    Surface(
        modifier = modifier.size(containerSize),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun FallbackGlyph(
    fallback: ImageVector,
    size: Dp,
    tint: Color,
    contentDescription: String?,
) {
    Icon(
        imageVector = fallback,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/**
 * The app's connection mark, shown bare.
 *
 * Same sun, rotation and color as the server card's connecting indicator, so the
 * affordance is unchanged from what the app already showed. It is intentionally not
 * wrapped in [BadgeContainer]: the container exists to frame a logo as an avatar
 * slot, and enclosing the sun would alter a mark the user already knows rather than
 * just relocating it. It reserves [containerSize] for layout but draws at [size],
 * so the row never shifts as it swaps; sizing the mark to the container instead would
 * make the sun read larger than it did, since the sun's bounding box exceeds the
 * circle it actually fills.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingSun(
    size: Dp,
    containerSize: Dp,
    modifier: Modifier = Modifier,
) {
    val rotation by
        rememberInfiniteTransition(label = "agentIconBusy").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(4200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "agentIconBusyRotation",
        )
    // Reserve the container's footprint so the title does not shift, but draw the
    // mark at its original size: the sun's bounding box is larger than the circle
    // it fills, so scaling it to the container would read as a bigger indicator.
    Box(modifier = modifier.size(containerSize), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .rotate(rotation)
                    .clip(MaterialShapes.VerySunny.toShape())
                    .background(MaterialTheme.colorScheme.secondary),
        )
    }
}
