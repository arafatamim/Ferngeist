package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * The two rows every hero card shares. Both enter with the status row below the name; the
 * status then rises above it, so a hero promoted on arrival reads as settling into place
 * rather than appearing fully formed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeroBody(
    statusRow: @Composable () -> Unit,
    nameRow: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var promoted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { promoted = true }
    val progress by animateFloatAsState(
        targetValue = if (promoted) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "heroRise",
    )
    Layout(
        modifier = modifier,
        content = {
            statusRow()
            nameRow()
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val statusPlaceable = measurables[0].measure(loose.copy(minWidth = constraints.maxWidth))
        val namePlaceable = measurables[1].measure(loose.copy(minWidth = constraints.maxWidth))
        val height = constraints.constrainHeight(statusPlaceable.height + namePlaceable.height)
        layout(constraints.maxWidth, height) {
            statusPlaceable.placeRelative(0, lerp(0, height - statusPlaceable.height, progress))
            namePlaceable.placeRelative(0, lerp(height - namePlaceable.height, 0, progress))
        }
    }
}

/** The status worth naming aloud; finished and unknown make no claim. */
internal val TurnStatus.headline: TurnStatus?
    get() = takeUnless { this == TurnStatus.FINISHED || this == TurnStatus.UNKNOWN }
