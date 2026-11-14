@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink

@Composable
internal fun AnimatedComposerAttachments(
    images: List<ChatImageData>,
    audios: List<ChatAudioData>,
    files: List<ChatFileData>,
    links: List<ChatPromptResourceLink>,
    onImagesChanged: (List<ChatImageData>) -> Unit,
    onAudiosChanged: (List<ChatAudioData>) -> Unit,
    onFilesChanged: (List<ChatFileData>) -> Unit,
    onLinksChanged: (List<ChatPromptResourceLink>) -> Unit,
) {
    // -- Selected image thumbnails --
    AnimatedAttachmentRow(images) { displayed ->
        ImageThumbnailRow(images = displayed, onRemove = { index ->
            val currentIndex = displayed.getOrNull(index)?.let(images::indexOf) ?: -1
            if (currentIndex >= 0) {
                onImagesChanged(images.toMutableList().also { it.removeAt(currentIndex) })
            }
        })
    }
    AnimatedAttachmentRow(audios) { displayed ->
        AudioChipRow(audios = displayed, onRemove = { index ->
            val currentIndex = displayed.getOrNull(index)?.let(audios::indexOf) ?: -1
            if (currentIndex >= 0) {
                onAudiosChanged(audios.toMutableList().also { it.removeAt(currentIndex) })
            }
        })
    }
    AnimatedAttachmentRow(files) { displayed ->
        FileChipRow(files = displayed, onRemove = { index ->
            val currentIndex = displayed.getOrNull(index)?.let(files::indexOf) ?: -1
            if (currentIndex >= 0) {
                onFilesChanged(files.toMutableList().also { it.removeAt(currentIndex) })
            }
        })
    }
    AnimatedAttachmentRow(links) { displayed ->
        ResourceLinkChipRow(links = displayed, onRemove = { index ->
            val currentIndex = displayed.getOrNull(index)?.let(links::indexOf) ?: -1
            if (currentIndex >= 0) {
                onLinksChanged(links.toMutableList().also { it.removeAt(currentIndex) })
            }
        })
    }
}

@Composable
private fun <T> AnimatedAttachmentRow(
    items: List<T>,
    content: @Composable (List<T>) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = items.isNotEmpty()
    var last by remember { mutableStateOf(items) }
    val exitFinished = visibility.isIdle && !visibility.currentState
    SideEffect {
        if (items.isNotEmpty()) {
            last = items
        } else if (exitFinished) {
            last = emptyList()
        }
    }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visibleState = visibility,
        enter = expandVertically(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        content(items.ifEmpty { last })
    }
}
