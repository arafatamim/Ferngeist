package com.tamimarafat.ferngeist.feature.serverlist.ui.home

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberHomePagerMotion(): HomePagerMotion {
    val state = rememberPagerState { HomeTab.entries.size }
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    return remember(state, spring) { HomePagerMotion(state, spring) }
}

internal class HomePagerMotion(
    val state: PagerState,
    private val spring: FiniteAnimationSpec<Float>,
) {
    var overshootPx by mutableFloatStateOf(0f)
        private set

    suspend fun select(tab: HomeTab) {
        state.scroll {
            with(state) { updateTargetPage(tab.ordinal) }
            val pageStride = state.layoutInfo.pageSize + state.layoutInfo.pageSpacing
            settle(state.getOffsetDistanceInPages(tab.ordinal) * pageStride, 0f)
        }
    }

    @Composable
    fun flingBehavior(): TargetedFlingBehavior {
        // Keep Compose's direction and threshold rules. This two-page pager has no decay approach.
        val resolveDestination =
            PagerDefaults.flingBehavior(
                state = state,
                pagerSnapDistance = PagerSnapDistance.atMost(0),
                snapAnimationSpec = snap(),
            )
        return remember(this, resolveDestination) {
            object : TargetedFlingBehavior {
                override suspend fun ScrollScope.performFling(
                    initialVelocity: Float,
                    onRemainingDistanceUpdated: (Float) -> Unit,
                ): Float {
                    var distance = 0f
                    val remainingVelocity =
                        with(resolveDestination) {
                            val measureScope =
                                object : ScrollScope {
                                    override fun scrollBy(pixels: Float): Float {
                                        distance += pixels
                                        return pixels
                                    }
                                }
                            measureScope.performFling(initialVelocity)
                        }
                    if (distance == 0f) return remainingVelocity

                    // Limit the energy of a fast release so the full page only rebounds slightly.
                    val maxVelocity = MAX_FLING_PAGES_PER_SECOND * state.layoutInfo.pageSize
                    settle(distance, initialVelocity.coerceIn(-maxVelocity, maxVelocity), onRemainingDistanceUpdated)
                    return 0f
                }
            }
        }
    }

    suspend fun ScrollScope.settle(
        distance: Float,
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit = {},
    ) {
        if (distance == 0f) return
        var consumed = 0f
        onRemainingDistanceUpdated(distance)
        try {
            animate(0f, distance, initialVelocity, spring) { position, _ ->
                consumed += scrollBy(position - consumed)
                // The pager clamps at its end pages. Draw the unconsumed spring travel past that edge.
                overshootPx = position - consumed
                onRemainingDistanceUpdated(distance - consumed)
            }
        } finally {
            overshootPx = 0f
        }
    }
}

private const val MAX_FLING_PAGES_PER_SECOND = 2f
