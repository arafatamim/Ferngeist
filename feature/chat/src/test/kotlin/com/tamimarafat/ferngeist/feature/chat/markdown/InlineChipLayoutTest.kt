package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
class InlineChipLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wrappedChipReservesSpaceBetweenTranscriptLines() {
        val text =
            buildAnnotatedString {
                append("I moved the plan into this worktree at\n")
                appendInlineContent("file", "docs/superpowers/plans/2026-11-10-transcription-scroll-restructure.md")
                append("\nIt is no longer in the master checkout.")
            }
        compose.setContent {
            RevealText(
                text = text,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                reveal = null,
                modifier = Modifier.width(320.dp).testTag("transcript"),
                inlineContent =
                    mapOf(
                        "file" to
                            InlineTextContent(Placeholder(280.sp, 40.sp, PlaceholderVerticalAlign.TextCenter)) {
                                Box(Modifier)
                            },
                    ),
            )
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("transcript").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val chip = requireNotNull(layout.placeholderRects.single())
        val chipLine = layout.getLineForOffset(text.indexOf("docs/"))

        assertTrue("Chip covers the preceding line: $chip", chip.top >= layout.getLineBottom(chipLine - 1))
        assertTrue("Chip covers the following line: $chip", chip.bottom <= layout.getLineTop(chipLine + 1))
    }
}
