package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.TurnRunway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A held turn lets go of its runway height gradually, so the prompt below it does not jump. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TurnItemHeldRunwayTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `releasing a held turn eases it down to its natural height`() {
        val heldPx = 2000
        var runway: TurnRunway by mutableStateOf(TurnRunway.Held(heldPx))
        val heights = mutableListOf<Int>()
        val prompt = ChatMessage(role = ChatMessage.Role.USER, contentBlocks = listOf(ContentBlock.Text("hi")))
        compose.setContent {
            LazyColumn {
                item {
                    TurnItem(
                        turn = Turn(prompt, emptyList()),
                        isLast = true,
                        awaitingHydration = false,
                        markdownDocuments = emptyMap(),
                        workingTree = emptyList(),
                        runway = runway,
                        onPromptHeight = {},
                        onThoughtClick = {},
                        onToolCallClick = {},
                        onRetryMessage = null,
                        modifier = Modifier.onSizeChanged { heights += it.height },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(heldPx, heights.last())

        compose.runOnIdle { runway = TurnRunway.None }
        compose.waitForIdle()
        val natural = heights.last()

        assertTrue("natural height $natural should be under the hold", natural < heldPx)
        // Dropped in one frame, the row goes straight from the hold to its natural height.
        assertTrue("no height between $natural and $heldPx in $heights", heights.any { it in natural + 1 until heldPx })
    }
}
