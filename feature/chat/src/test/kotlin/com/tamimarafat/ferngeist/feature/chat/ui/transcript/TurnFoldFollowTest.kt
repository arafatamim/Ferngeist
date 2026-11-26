package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.TurnRunway
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The turn below an older one follows that turn's fold. In the app's [SharedTransitionLayout], a
 * lookahead scope, the list placed it at its final offset in the fold's first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TurnFoldFollowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the next turn moves with an older turn's fold, open and closed`() {
        val ys = mutableListOf<Float>()
        val tracked = Modifier.onGloballyPositioned { ys += it.positionInRoot().y }
        compose.setContent {
            SharedTransitionLayout {
                LazyColumn {
                    items(listOf(older, next), key = { it.first.id }) { turn ->
                        TurnItem(
                            turn = turn,
                            isLast = turn === next,
                            awaitingHydration = false,
                            markdownDocuments = emptyMap(),
                            workingTree = emptyList(),
                            runway = TurnRunway.None,
                            onPromptHeight = {},
                            onThoughtClick = {},
                            onToolCallClick = {},
                            onRetryMessage = null,
                            modifier = if (turn === next) tracked else Modifier,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        listOf("open", "close").forEach { fold ->
            val from = ys.last()
            ys.clear()
            // The older turn's tool group header; the first clickable in the tree.
            compose.onAllNodes(hasClickAction())[0].performClick()
            compose.waitForIdle()
            val to = ys.last()
            assertTrue("$fold should move the next turn, got $ys", from != to)
            assertTrue("$fold jumped the next turn: $ys", ys.any { it != from && it != to })
        }
    }

    private val older =
        Turn(
            ChatMessage(id = "p1", contentBlocks = listOf(ContentBlock.Text("first"))),
            listOf(
                ChatMessage(
                    id = "reply",
                    role = ChatMessage.Role.ASSISTANT,
                    segments =
                        persistentListOf(
                            *(0 until 3)
                                .map {
                                    AssistantSegment(
                                        id = "c$it",
                                        kind = AssistantSegment.Kind.TOOL_CALL,
                                        toolCall =
                                            ToolCallDisplay(
                                                title = "call $it",
                                                kind = ToolKind.READ,
                                                status = ToolCallStatus.COMPLETED,
                                            ),
                                    )
                                }.toTypedArray(),
                            AssistantSegment(contentBlock = ContentBlock.Text("done")),
                        ),
                ),
            ),
        )

    private val next = Turn(ChatMessage(id = "p2", contentBlocks = listOf(ContentBlock.Text("second"))), emptyList())
}
