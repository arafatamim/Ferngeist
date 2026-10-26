package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.tamimarafat.ferngeist.core.common.R

/** The background carries activity while the agent's identity stays upright and in place. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActivityIconContainer(
    working: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    idleShape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    val description = stringResource(R.string.common_in_progress)
    val contentColor by animateColorAsState(
        if (working) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "activityIconColor",
    )
    Box(
        modifier.size(size).semantics { if (working) stateDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = working,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "activityBackdrop",
        ) { active ->
            if (active) {
                InProgressIndicator(
                    Modifier.size(size).clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                )
            } else {
                Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest, idleShape))
            }
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
        }
    }
}
