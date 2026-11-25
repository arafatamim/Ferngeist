@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ui.ChatComposerBar
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerMode

@Preview(showBackground = true, widthDp = 380)
@Composable
internal fun CollapsedComposerBarPreview() = ComposerBarPreview(expanded = false)

@Preview(showBackground = true, widthDp = 380)
@Composable
internal fun ExpandedComposerBarPreview() = ComposerBarPreview(expanded = true)

@Preview(showBackground = true, widthDp = 380)
@Composable
internal fun ExpandedComposerWithAttachmentsPreview() =
    ComposerBarPreview(
        expanded = true,
        // A 32px four-colour PNG: enough for the thumbnail to decode in layoutlib.
        images = listOf(ChatImageData(base64 = PREVIEW_IMAGE_BASE64, mimeType = "image/png")),
        files =
            listOf(
                ChatFileData(
                    name = "trip-itinerary.pdf",
                    base64 = "",
                    mimeType = "application/pdf",
                    sizeBytes = 184_320,
                ),
            ),
    )

private const val PREVIEW_IMAGE_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAOklEQVR42mOoWPWMJPRrXwVJiGHUglELRi0YtWDUgiFhQc+JaSShEz2XSEKjFoxaMGrBqAWjFgwJCwBj6Uh5CSSJawAAAABJRU5ErkJggg=="

private val previewModelAndEffort =
    listOf(
        ChatConfigOption.Select(
            id = "model",
            name = "Model",
            category = ChatConfigCategory.Model,
            currentValue = "sonnet",
            choices =
                listOf(
                    ChatConfigChoice("sonnet", "Sonnet 5.5", "sonnet"),
                    ChatConfigChoice("opus", "Opus 5.5", "opus"),
                ),
        ),
        ChatConfigOption.Select(
            id = "effort",
            name = "Effort",
            category = ChatConfigCategory.ThoughtLevel,
            currentValue = "high",
            choices = listOf(ChatConfigChoice("low", "Low", "low"), ChatConfigChoice("high", "High", "high")),
        ),
    )

@Composable
private fun ComposerBarPreview(
    expanded: Boolean,
    images: List<ChatImageData> = emptyList(),
    files: List<ChatFileData> = emptyList(),
) {
    MaterialExpressiveTheme {
        SharedTransitionLayout(Modifier.background(MaterialTheme.colorScheme.surface).padding(vertical = 16.dp)) {
            ChatComposerBar(
                state = ChatState(configOptions = previewModelAndEffort, agentName = "Claude"),
                toolbarConfigOptions = emptyList(),
                recentModelValues = emptyList(),
                mode = if (expanded) ComposerMode.EXPANDED else ComposerMode.COLLAPSED,
                onModeChange = {},
                messageText = TextFieldState("Ana!!\nThis place looks absolutely incredible!"),
                mentionState = ComposerSuggestionState(null, emptyList(), false),
                onMentionSelected = { _, _ -> },
                inputAlpha = { 1f },
                buttonsAlpha = { 1f },
                showModeButton = true,
                modeOption = ChatConfigOption.Select(id = "mode", name = "Mode", currentValue = "code"),
                currentModeLabel = "Code",
                showStopAction = false,
                focusRequester = FocusRequester(),
                focusEditor = {},
                onFocusCleared = {},
                onHeightChanged = {},
                onSend = {},
                onCancelStreaming = {},
                onSetStringConfigOption = { _, _ -> },
                onSetBooleanConfigOption = { _, _ -> },
                onShowCommands = {},
                onShowConfigOptionPicker = {},
                showJumpToBottom = true,
                onJumpToBottom = {},
                canSendImages = true,
                selectedImages = images,
                onImagesChanged = {},
                canSendFiles = true,
                selectedFiles = files,
                onFilesChanged = {},
                canSendAudio = false,
                selectedAudios = emptyList(),
                onAudiosChanged = {},
                selectedResourceLinks = emptyList(),
                onResourceLinksChanged = {},
                attachmentPickers = AttachmentPickers(pickPhotos = {}, takePhoto = {}, pickFiles = {}),
                dropTargetActive = false,
                onAttachUris = {},
                snackbarHostState = SnackbarHostState(),
                sharedTransitionScope = this,
            )
        }
    }
}
