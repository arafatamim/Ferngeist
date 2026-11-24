package com.tamimarafat.ferngeist.core.common.ui.refresh

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.rotate
import com.tamimarafat.ferngeist.core.common.ui.loading.ContainedLoadingIndicator
import com.tamimarafat.ferngeist.core.common.ui.loading.LoadingIndicatorDefaults

/**
 * Pull-to-refresh indicator that shows our [ContainedLoadingIndicator] instead of Material's copy:
 * it morphs with the pull distance, then loops while [isRefreshing]. Mirrors
 * [PullToRefreshDefaults.LoadingIndicator], whose sizing constants are internal.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PullToRefreshLoadingIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = PullToRefreshDefaults.loadingIndicatorContainerColor
    val indicatorColor = PullToRefreshDefaults.loadingIndicatorColor
    val indicatorSize =
        Modifier.requiredSize(
            LoadingIndicatorDefaults.containerWidth,
            LoadingIndicatorDefaults.containerHeight,
        )
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier.size(LoadingIndicatorDefaults.containerWidth, LoadingIndicatorDefaults.containerHeight),
        containerColor = containerColor,
        elevation = PullToRefreshDefaults.LoadingIndicatorElevation,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(
                    modifier = indicatorSize,
                    containerColor = containerColor,
                    indicatorColor = indicatorColor,
                )
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier =
                        indicatorSize.drawWithContent {
                            // Past the threshold, spin on from the end of the morph until refreshing starts.
                            val overshoot = state.distanceFraction - 1f
                            if (overshoot >
                                0f
                            ) {
                                rotate(-overshoot * HALF_TURN_DEGREES) { this@drawWithContent.drawContent() }
                            } else {
                                drawContent()
                            }
                        },
                    containerColor = containerColor,
                    indicatorColor = indicatorColor,
                )
            }
        }
    }
}

private const val HALF_TURN_DEGREES = 180f
