package com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ui.ChatComposerBar
import com.tamimarafat.ferngeist.feature.chat.ui.composer.AttachmentPickers
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ComposerSuggestionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h800dp")
class ComposerFullscreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun draggingFabUp_opensInlineComposerBeforeRelease() {
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize().testTag("chat-pane"), contentAlignment = Alignment.BottomCenter) {
                    ComposerInNarrowPane(Modifier.width(280.dp), initialMode = ComposerMode.COLLAPSED)
                }
            }
        }

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat").performTouchInput {
            down(center)
            moveBy(Offset(0f, -96.dp.toPx()))
        }

        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("chat-pane").performTouchInput { up() }
    }

    @Test
    fun draggingInlineComposerDown_shortDragMovesPanelAndSpringsBack() {
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize().testTag("chat-pane"), contentAlignment = Alignment.BottomCenter) {
                    ComposerInNarrowPane(Modifier.width(280.dp))
                }
            }
        }

        val closeButton = compose.onNodeWithContentDescription("Close composer")
        val restingTop = closeButton.getUnclippedBoundsInRoot().top
        closeButton.performTouchInput {
            down(center)
            moveBy(Offset(0f, 40.dp.toPx()))
        }
        assertTrue(closeButton.getUnclippedBoundsInRoot().top > restingTop)

        compose.onNodeWithTag("chat-pane").performTouchInput { up() }
        assertTrue(closeButton.getUnclippedBoundsInRoot().top == restingTop)
    }

    @Test
    fun draggingEditor_movesPanelBeforeReleaseAndKeepsDraftThroughFullscreen() {
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize().testTag("chat-pane"), contentAlignment = Alignment.BottomCenter) {
                    ComposerInNarrowPane(Modifier.width(280.dp))
                }
            }
        }

        val editor = compose.onNode(hasSetTextAction())
        val inlineClose = compose.onNodeWithContentDescription("Close composer")
        val beforeDrag = inlineClose.getUnclippedBoundsInRoot().top
        editor.performTouchInput {
            down(center)
            moveBy(Offset(0f, -170f))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(inlineClose.getUnclippedBoundsInRoot().top < beforeDrag)
        editor.performTouchInput { moveBy(Offset(0f, -30f)) }
        compose.onNode(isDialog()).assertWidthIsEqualTo(480.dp).assertHeightIsEqualTo(800.dp)
        compose.onNodeWithTag("chat-pane").performTouchInput { up() }
        val closeButton = compose.onNodeWithContentDescription("Exit full-screen composer")
        assertTrue(
            "Full-screen controls should have an inner inset",
            closeButton.getUnclippedBoundsInRoot().left >= 12.dp,
        )
        compose
            .onNode(hasSetTextAction())
            .assertHeightIsAtLeast(500.dp)
            .assertTextContains("Original draft")
            .performTextReplacement("Revised draft")

        val beforeRestore = closeButton.getUnclippedBoundsInRoot().top
        editor.performTouchInput {
            down(center)
            moveBy(Offset(0f, 170f))
        }
        assertTrue(closeButton.getUnclippedBoundsInRoot().top > beforeRestore)
        compose.onNode(isDialog()).assertExists()
        editor.performTouchInput { moveBy(Offset(0f, 30f)) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Revised draft")
    }

    @Test
    @Config(qualifiers = "w480dp-h200dp")
    fun fullScreenComposer_shortWindow_keepsThreeLineEditor() {
        compose.setContent {
            MaterialExpressiveTheme {
                ComposerInNarrowPane(Modifier.width(280.dp), ComposerMode.FULLSCREEN)
            }
        }

        // Header, send row and insets leave less than this; the editor keeps it and the stack scrolls.
        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(72.dp)
    }

    @Test
    @Config(qualifiers = "w480dp-h200dp")
    fun fullScreenComposer_scrolledToSend_keepsBackButtonPinned() {
        compose.setContent {
            MaterialExpressiveTheme {
                ComposerInNarrowPane(Modifier.width(280.dp), ComposerMode.FULLSCREEN)
            }
        }
        val back = compose.onNodeWithContentDescription("Exit full-screen composer")
        val restingTop = back.getBoundsInRoot().top

        compose.onNodeWithContentDescription("Send").performScrollTo()

        assertEquals(restingTop, back.getBoundsInRoot().top)
    }

    @Test
    fun fullScreenBackButton_closesComposer() {
        compose.setContent {
            MaterialExpressiveTheme {
                ComposerInNarrowPane(Modifier.width(280.dp), ComposerMode.FULLSCREEN)
            }
        }

        compose.onNodeWithContentDescription("Exit full-screen composer").performClick()

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Close composer").assertDoesNotExist()
    }

    @Test
    fun systemBack_closesExpandedAndFullScreenComposer() {
        compose.setContent {
            MaterialExpressiveTheme {
                ComposerInNarrowPane(Modifier.width(280.dp))
            }
        }

        compose.onNodeWithContentDescription("Close composer").assertExists()
        val activity = compose.activity
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Close composer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat").performClick()
        expandThroughAccessibility()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Close composer").assertDoesNotExist()
    }

    @Test
    fun predictiveBack_movesBothPanelsWithGestureAndRestoresOnCancel() {
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ComposerInNarrowPane(Modifier.width(280.dp))
                }
            }
        }

        val inlineClose = "Close composer"
        assertPredictiveBackMotion(compose.activity.onBackPressedDispatcher, inlineClose)
        expandThroughAccessibility()
        compose.onNode(isDialog()).assertExists()

        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        assertPredictiveBackMotion(dialog.onBackPressedDispatcher, "Exit full-screen composer")
        compose.runOnIdle {
            dialog.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            dialog.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.5f))
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription(inlineClose).assertDoesNotExist()
    }

    private fun assertPredictiveBackMotion(
        dispatcher: OnBackPressedDispatcher,
        closeDescription: String,
    ) {
        val close = compose.onNodeWithContentDescription(closeDescription)
        val initialTop = close.getUnclippedBoundsInRoot().top
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.25f))
        }
        val partialTop = close.getUnclippedBoundsInRoot().top
        assertTrue(partialTop > initialTop)
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        assertTrue(close.getUnclippedBoundsInRoot().top > partialTop)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        assertTrue(close.getUnclippedBoundsInRoot().top == initialTop)
    }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun modelAndEffortSelector_sitsAcrossFromCloseInBothModes() {
        val configOptions =
            listOf(
                ChatConfigOption.Select(
                    id = "model",
                    name = "Model",
                    category = ChatConfigCategory.Model,
                    currentValue = "a",
                    choices =
                        listOf(ChatConfigChoice("a", "Claude Sonnet", "a"), ChatConfigChoice("b", "Model B", "b")),
                ),
                ChatConfigOption.Select(
                    id = "effort",
                    name = "Effort",
                    category = ChatConfigCategory.ThoughtLevel,
                    currentValue = "low",
                    choices = listOf(ChatConfigChoice("low", "Low", "low"), ChatConfigChoice("high", "High", "high")),
                ),
            )
        compose.setContent {
            MaterialExpressiveTheme {
                ComposerInNarrowPane(Modifier.width(280.dp), configOptions = configOptions)
            }
        }

        assertSelectorInHeader("Close composer")
        expandThroughAccessibility()
        assertSelectorInHeader("Exit full-screen composer")
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithText("Model B").assertExists()
    }

    private fun expandThroughAccessibility() {
        compose
            .onNodeWithContentDescription("Close composer")
            .performCustomAccessibilityActionWithLabel("Expand composer to full screen")
    }

    private fun assertSelectorInHeader(closeLabel: String) {
        val close = compose.onNodeWithContentDescription(closeLabel).getUnclippedBoundsInRoot()
        val model = compose.onNodeWithText("Claude Sonnet", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The effort rides in the model button, after the name.
        val effort =
            compose
                .onNodeWithText(
                    "· Low",
                    substring = true,
                    useUnmergedTree = true,
                ).getUnclippedBoundsInRoot()
        assertTrue(model.left >= close.right)
        assertTrue(effort.left >= model.right)
        assertTrue((model.top + model.bottom) / 2 in close.top..close.bottom)
        assertTrue((effort.top + effort.bottom) / 2 in close.top..close.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("Claude Sonnet", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val visibleEnd = layout.getLineEnd(0, visibleEnd = true)
        assertTrue(
            "The model name should fit on one line beside $closeLabel",
            layout.lineCount == 1 && !layout.isLineEllipsized(0) && visibleEnd == "Claude Sonnet".length,
        )
    }

    @Composable
    private fun ComposerInNarrowPane(
        modifier: Modifier,
        initialMode: ComposerMode = ComposerMode.EXPANDED,
        configOptions: List<ChatConfigOption> = emptyList(),
    ) {
        var mode by remember { mutableStateOf(initialMode) }
        SharedTransitionLayout {
            ChatComposerBar(
                modifier = modifier,
                state = ChatState(configOptions = configOptions),
                toolbarConfigOptions = emptyList(),
                recentModelValues = emptyList(),
                mode = mode,
                onModeChange = { mode = it },
                messageText = remember { TextFieldState("Original draft") },
                mentionState = ComposerSuggestionState(null, emptyList(), false),
                onMentionSelected = { _, _ -> },
                inputAlpha = { 1f },
                buttonsAlpha = { 1f },
                showModeButton = false,
                modeOption = null,
                currentModeLabel = "",
                showStopAction = false,
                focusRequester = remember { FocusRequester() },
                focusEditor = {},
                onFocusCleared = {},
                onHeightChanged = {},
                onSend = {},
                onCancelStreaming = {},
                onSetStringConfigOption = { _, _ -> },
                onSetBooleanConfigOption = { _, _ -> },
                onShowCommands = {},
                onShowConfigOptionPicker = {},
                showJumpToBottom = false,
                onJumpToBottom = {},
                canSendImages = false,
                selectedImages = emptyList(),
                onImagesChanged = {},
                canSendFiles = false,
                selectedFiles = emptyList(),
                onFilesChanged = {},
                canSendAudio = false,
                selectedAudios = emptyList(),
                onAudiosChanged = {},
                selectedResourceLinks = emptyList(),
                onResourceLinksChanged = {},
                attachmentPickers = AttachmentPickers(pickPhotos = {}, takePhoto = null, pickFiles = {}),
                dropTargetActive = false,
                onAttachUris = {},
                snackbarHostState = remember { SnackbarHostState() },
                sharedTransitionScope = this,
            )
        }
    }
}
