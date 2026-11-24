package com.tamimarafat.ferngeist.feature.chat.activity

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModelTestBase
import com.tamimarafat.ferngeist.feature.chat.InMemoryPreferencesDataStore
import com.tamimarafat.ferngeist.feature.chat.testStores
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreenMutations
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerMode
import com.tamimarafat.ferngeist.feature.chat.ui.rememberSendHandlers
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ChatScrollHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSendTest : ChatViewModelTestBase() {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendBeforePhoneActivityRestores_doesNotWarnAboutThisPhonesOwnActivity() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val listed = System.currentTimeMillis()
            PhoneTurnClock(dataStore, Json).record("server", "chat", listed, emptySet())
            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(mapOf("serverId" to "server", "sessionId" to "chat", "updatedAt" to listed)),
                    stores = testStores().copy(phoneTurns = PhoneTurnClock(dataStore, Json)),
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
            runCurrent()

            assertNull(mutations.pendingSend.value)
            assertEquals("", mutations.messageText.text.toString())
        }

    @Test
    fun aCommandWithAnInputHintStagesInTheComposerInsteadOfSending() {
        val viewModel =
            createViewModel(
                savedStateHandle =
                    SavedStateHandle(
                        mapOf(
                            "serverId" to "hinted-command",
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
        val state =
            ChatState(
                isSessionReady = true,
                availableCommands = listOf(ChatCommand("review", inputHint = "base branch")),
            )
        val (_, sendCommand) =
            CoroutineScope(mainDispatcherRule.dispatcher).rememberSendHandlers(
                viewModel,
                state,
                mutations,
                focusManager,
                ChatScrollHandle(LazyListState()),
            )

        sendCommand("review")

        assertEquals("/review Keep my draft", mutations.messageText.text.toString())
        assertTrue(mutations.composerMode.value.isExpanded)
        assertEquals(1, mutations.composerFocusTrigger.value)
        assertNull(mutations.pendingSend.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
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
            runCurrent()

            assertEquals("Keep my draft", mutations.messageText.text.toString())
            assertTrue(mutations.composerMode.value.isExpanded)
            assertNotNull(mutations.pendingSend.value)

            mutations.pendingSend.value?.invoke()

            assertEquals("", mutations.messageText.text.toString())
            assertFalse(mutations.composerMode.value.isExpanded)
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
            composerMode = mutableStateOf(ComposerMode.EXPANDED),
            focusRequester = FocusRequester(),
            imageFocusTrigger = mutableStateOf(0),
            composerFocusTrigger = mutableStateOf(0),
        )
}
