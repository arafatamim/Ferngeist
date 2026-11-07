package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectFilterChipsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun jitteryMouseDrag_revealsHiddenClearActionWithoutOpeningSheet() {
        var edits = 0
        var cleared = 0
        showFilters(onEdit = { edits++ }, onClearScope = { cleared++ })
        val clearWorktree = compose.onNodeWithContentDescription("Clear Worktree: $WORKTREE filter")
        clearWorktree.assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Agent: OpenCode V2").performMouseInput { click() }
        assertEquals(1, edits)

        compose.onNode(hasScrollAction()).performMouseInput {
            moveTo(centerRight)
            press()
            // A real mouse rarely starts perfectly horizontal; the app bar's vertical drag must not win.
            moveBy(Offset(0f, 2f))
            moveTo(centerLeft, delayMillis = 300)
            release()
        }

        assertEquals(1, edits)
        clearWorktree.assertIsDisplayed().performMouseInput { click() }
        assertEquals(1, cleared)
    }

    @Test
    fun touchSwipe_revealsHiddenClearAction() {
        showFilters()
        val clearWorktree = compose.onNodeWithContentDescription("Clear Worktree: $WORKTREE filter")
        clearWorktree.assertIsNotDisplayed()
        compose.onNode(hasScrollAction()).performTouchInput { swipeLeft() }
        clearWorktree.assertIsDisplayed()
    }

    @Test
    fun projectChip_editsAndClears() {
        var edits = 0
        var cleared = 0
        compose.setContent {
            MaterialTheme {
                ProjectFilterChips(
                    agentName = null,
                    agentIconUrl = null,
                    scopeName = null,
                    projectName = "Ferngeist",
                    onEdit = { edits++ },
                    onClearAgent = {},
                    onClearScope = {},
                    onClearProject = { cleared++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Project: Ferngeist").assertIsDisplayed().performClick()
        assertEquals(1, edits)
        compose.onNodeWithContentDescription("Clear Project: Ferngeist filter").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun clearingLastFilter_collapsesThroughIntermediateHeight() {
        val scope = mutableStateOf<String?>(WORKTREE)
        var height = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).onSizeChanged { height = it.height }) {
                    ProjectFilterChips(
                        agentName = null,
                        agentIconUrl = null,
                        scopeName = scope.value,
                        onEdit = {},
                        onClearAgent = {},
                        onClearScope = { scope.value = null },
                    )
                }
            }
        }
        compose.waitForIdle()
        val expandedHeight = height
        assertTrue("The filter starts visible", expandedHeight > 0)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertTrue(
            "Collapse must retain an intermediate height: $height / $expandedHeight",
            height in 1 until expandedHeight,
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, height)
    }

    // Mirrors ProjectScreen: the app bar brings the vertical drag that competes with a mouse drag, and
    // SharedTransitionLayout is what froze a placement-only scroll offset in its title slot.
    @OptIn(
        ExperimentalMaterial3Api::class,
        ExperimentalMaterial3ExpressiveApi::class,
        ExperimentalSharedTransitionApi::class,
    )
    private fun showFilters(
        onEdit: () -> Unit = {},
        onClearScope: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SharedTransitionLayout {
                    TwoRowsTopAppBar(
                        modifier = Modifier.width(320.dp),
                        title = { Text("Ferngeist") },
                        subtitle = {
                            Column {
                                Text("australis-tamim")
                                ProjectFilterChips(
                                    agentName = "OpenCode V2",
                                    agentIconUrl = null,
                                    scopeName = WORKTREE,
                                    onEdit = onEdit,
                                    onClearAgent = {},
                                    onClearScope = onClearScope,
                                )
                            }
                        },
                        collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
                        expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight,
                        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    )
                }
            }
        }
    }

    private companion object {
        const val WORKTREE = "arafatamim/worktree-first-seamless-chat-resume"
    }
}
