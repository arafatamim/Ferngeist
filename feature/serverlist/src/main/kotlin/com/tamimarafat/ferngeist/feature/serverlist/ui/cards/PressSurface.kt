package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.motion.rememberPressShape
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick

/**
 * The pressable shell every home card shares: press scale, a tone that recolors in place,
 * secondary-click and long-press actions, and the shared bounds the screen it opens grows
 * out of. [sharedBoundsKey] null leaves the card out of shared-element transitions.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PressSurface(
    shape: RoundedCornerShape,
    color: Color,
    contentColor: Color,
    onClick: () -> Unit,
    sharedBoundsKey: Any?,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressShape = rememberPressShape(shape, interactionSource)
    // Status changes recolor the card in place; an effects spec so the tone never overshoots.
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "cardColor",
    )
    Surface(
        color = animatedColor,
        contentColor = contentColor,
        shape = pressShape,
        modifier =
            modifier
                .sharedCardBounds(sharedBoundsKey, sharedTransitionScope, animatedContentScope)
                .fillMaxWidth()
                .clip(pressShape)
                .handCursor(enabled)
                .then(
                    if (onLongClick != null) {
                        Modifier.onSecondaryClick(enabled = enabled) { onLongClick() }
                    } else {
                        Modifier
                    },
                ).combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    role = Role.Button,
                ),
        content = content,
    )
}

/** The shared bounds a card and the screen it opens into match on. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedCardBounds(
    key: Any?,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
): Modifier =
    if (key == null) {
        this
    } else {
        with(sharedTransitionScope) {
            sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedContentScope,
                enter = fadeIn(),
                exit = fadeOut(),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
            )
        }
    }

/** The shared bounds a card and the screen it opens into match on. */
