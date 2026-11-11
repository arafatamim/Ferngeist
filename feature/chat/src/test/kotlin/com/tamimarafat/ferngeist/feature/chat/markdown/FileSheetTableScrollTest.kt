package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.tamimarafat.ferngeist.core.git.files.FileView
import com.tamimarafat.ferngeist.core.git.ui.FileSheet
import com.tamimarafat.ferngeist.feature.chat.markdown.files.ProvideFileMarkdown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The file sheet's rendered preview is a screen that owns its scroll, not a chat bubble. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileSheetTableScrollTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wideTableInPreview_scrollsInsteadOfSqueezingColumns() {
        compose.setContent {
            MaterialTheme {
                ProvideFileMarkdown {
                    FileSheet(
                        file = FileView(path = "README.md", isLoading = false, text = PROSE_TABLE),
                        softWrap = false,
                        onSoftWrapChange = {},
                        markdownPreview = true,
                        onMarkdownPreviewChange = {},
                        onDismiss = {},
                    )
                }
            }
        }

        val table = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Preview squeezed the table instead of scrolling: max=${range.maxValue()}", range.maxValue() > 0f)
    }

    private companion object {
        /** Three columns of prose, the shape that used to squeeze to fit a wide container. */
        val PROSE_TABLE =
            """
            | Pick | Tier | Why in one line |
            |---|---|---|
            | **Moonshine Medium Streaming (245 M, MIT)** | CPU VPS, Apple Silicon, Pi 5, phones | The only family that publishes per-phrase latency on all four tiers and beats Whisper large-v3 on the Open ASR Leaderboard average while being 6x smaller. |
            | **NVIDIA Parakeet TDT 0.6B v3 (CC-BY-4.0)** | GPU 8-24 GB | 6.34 % Open ASR average across 25 European languages with native punctuation/casing, and a 640 MB int8 ONNX export that sherpa-onnx already ships. |
            """.trimIndent()
    }
}
