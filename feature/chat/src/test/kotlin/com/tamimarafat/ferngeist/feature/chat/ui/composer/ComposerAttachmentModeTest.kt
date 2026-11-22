package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreenMutations
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerAttachmentModeTest {
    @Test
    fun acceptingAFileOpensTheFullscreenComposer() {
        val draft = draft(ComposerMode.COLLAPSED)

        draft.applyPickedAttachments(previous = empty, attachments = files)

        assertEquals(ComposerMode.FULLSCREEN, draft.composerMode.value)
    }

    @Test
    fun anUnchangedAttachmentListDoesNotReopenTheFullscreenComposer() {
        val draft = draft(ComposerMode.EXPANDED)
        draft.applyPickedAttachments(previous = empty, attachments = files)
        draft.composerMode.value = ComposerMode.EXPANDED

        draft.applyPickedAttachments(previous = files, attachments = files)

        assertEquals(ComposerMode.EXPANDED, draft.composerMode.value)
    }

    @Test
    fun attachingAWorkspaceLinkOpensTheFullscreenComposer() {
        val draft = draft(ComposerMode.EXPANDED)

        draft.attachResourceLink(link)

        assertEquals(ComposerMode.FULLSCREEN, draft.composerMode.value)
    }

    @Test
    fun aDuplicateWorkspaceLinkDoesNotReopenTheFullscreenComposer() {
        val draft = draft(ComposerMode.EXPANDED)
        draft.attachResourceLink(link)
        draft.composerMode.value = ComposerMode.EXPANDED

        draft.attachResourceLink(link)

        assertEquals(ComposerMode.EXPANDED, draft.composerMode.value)
    }

    private val empty = HeldAttachments(images = emptyList(), files = emptyList())

    private val files =
        HeldAttachments(
            images = emptyList(),
            files = listOf(ChatFileData("notes.txt", "bm90ZXM=", "text/plain", 5)),
        )

    private val link = ChatPromptResourceLink(name = "notes.txt", uri = "file:///workspace/notes.txt")

    private fun draft(mode: ComposerMode): ChatScreenMutations =
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
            messageText = TextFieldState(),
            selectedImages = mutableStateOf(emptyList()),
            selectedFiles = mutableStateOf(emptyList()),
            composerMode = mutableStateOf(mode),
            focusRequester = FocusRequester(),
            imageFocusTrigger = mutableStateOf(0),
            composerFocusTrigger = mutableStateOf(0),
        )
}
