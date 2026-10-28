package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * The background carries activity while the agent's identity stays upright and in place.
 * Working moves; a turn awaiting attention holds a static, differently shaped accent, and
 * the row's status label names both.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActivityIconContainer(
    status: TurnStatus,
    size: Dp,
    modifier: Modifier = Modifier,
    idleShape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    val description = turnStatusLabel(status)
    Box(
        modifier.size(size).semantics { description?.let { stateDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = status,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "activityBackdrop",
        ) { active ->
            when (active) {
                TurnStatus.WORKING ->
                    InProgressIndicator(
                        Modifier.size(size).clearAndSetSemantics {},
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                TurnStatus.NEEDS_ATTENTION ->
                    Box(
                        Modifier
                            .size(size)
                            .background(MaterialTheme.colorScheme.tertiary, MaterialShapes.Cookie9Sided.toShape()),
                    )
                else ->
                    Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest, idleShape))
            }
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(
                LocalContentColor provides
                    if (status == TurnStatus.NEEDS_ATTENTION) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                content = content,
            )
        }
    }
}
