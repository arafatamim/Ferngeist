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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberHomePagerMotion(hasProjects: Boolean): HomePagerMotion {
    // Projects is a page only while there is something to list; Agents alone needs no switcher.
    val tabs = if (hasProjects) HomeTab.entries else listOf(HomeTab.AGENTS)
    val state = rememberPagerState { tabs.size }
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val motion = remember(state, spring, tabs) { HomePagerMotion(state, spring, tabs) }
    LaunchedEffect(motion) { motion.followRestingPage() }
    return motion
}

internal class HomePagerMotion(
    val state: PagerState,
    private val spring: FiniteAnimationSpec<Float>,
    val tabs: List<HomeTab>,
) {
    val currentTab: HomeTab get() = tabAt(state.currentPage)

    // The tab bar moves only on a tap, a release or a rest. targetPage flips mid-drag and falls back to
    // currentPage between a drag and its fling, restarting the tab's spring at random.
    private var committedPage by mutableIntStateOf(state.currentPage)
    val selectedTab: HomeTab get() = tabAt(committedPage)

    // The page index can briefly outlive a shrunk tab list until the pager's next measure.
    private fun tabAt(page: Int): HomeTab = tabs[page.coerceAtMost(tabs.lastIndex)]

    var overshootPx by mutableFloatStateOf(0f)
        private set

    /** Commits pages reached outside [settle], such as an accessibility scroll, once the pager rests. */
    suspend fun followRestingPage() {
        snapshotFlow {
            val resting = !state.isScrollInProgress && abs(state.currentPageOffsetFraction) < PAGE_REST_TOLERANCE
            state.currentPage.takeIf { resting }
        }.filterNotNull().collect { committedPage = it }
    }

    suspend fun select(tab: HomeTab) {
        state.scroll {
            settle(state.getOffsetDistanceInPages(tabs.indexOf(tab)) * pageStride(), 0f)
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
        // Commit the destination up front so the spring's rebound cannot flip the tab.
        val destination = state.currentPage + state.currentPageOffsetFraction + distance / pageStride()
        committedPage = destination.roundToInt().coerceIn(0, tabs.lastIndex)
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

    private fun pageStride(): Int = state.layoutInfo.pageSize + state.layoutInfo.pageSpacing
}

private const val MAX_FLING_PAGES_PER_SECOND = 2f

// The pager's own rounding-error bound for a page offset.
private const val PAGE_REST_TOLERANCE = 1e-6f
