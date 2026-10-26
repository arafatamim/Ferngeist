package com.tamimarafat.ferngeist.feature.chat.activity

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModelTestBase
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreenMutations
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScrollHandle
import com.tamimarafat.ferngeist.feature.chat.ui.rememberSendHandlers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSendTest : ChatViewModelTestBase() {
    @Test
    fun guardedSend_keepsDraftUntilConfirmation() =
        runTest {
            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "guarded-send",
                                "sessionId" to "chat",
                                "updatedAt" to System.currentTimeMillis(),
                            ),
                        ),
                )
            val mutations = mutations()
            val focusManager =
                object : FocusManager {
                    override fun clearFocus(force: Boolean) = Unit

                    override fun moveFocus(focusDirection: FocusDirection): Boolean = false
                }
            val (send, _) =
                rememberSendHandlers(viewModel, ChatState(), mutations, focusManager, ChatScrollHandle(LazyListState()))

            send()

            assertEquals("Keep my draft", mutations.messageText.text.toString())
            assertTrue(mutations.composerExpanded.value)
            assertNotNull(mutations.pendingSend.value)

            mutations.pendingSend.value?.invoke()

            assertEquals("", mutations.messageText.text.toString())
            assertFalse(mutations.composerExpanded.value)
        }

    private fun mutations() =
        ChatScreenMutations(
            snackbarHostState = SnackbarHostState(),
            selectedConfigPickerOptionId = mutableStateOf(null),
            selectedThoughtSegmentId = mutableStateOf(null),
            selectedToolCallSegmentId = mutableStateOf(null),
            showCommandsDialog = mutableStateOf(false),
            pendingSend = mutableStateOf(null),
            showConnectionStatusDialog = mutableStateOf(false),
            showGitStatusSheet = mutableStateOf(false),
            showSwitcherSheet = mutableStateOf(false),
            composerContentHeightPx = mutableStateOf(0),
            messageText = TextFieldState("Keep my draft"),
            selectedImages = mutableStateOf(emptyList()),
            selectedFiles = mutableStateOf(emptyList()),
            composerExpanded = mutableStateOf(true),
            focusRequester = FocusRequester(),
            imageFocusTrigger = mutableStateOf(0),
        )
}
