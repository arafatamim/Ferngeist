@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R
import kotlinx.coroutines.launch

/** One pending attachment of any kind, so the composer can lay them all out in one strip. */
private sealed interface PendingAttachment {
    data class Image(
        val data: ChatImageData,
    ) : PendingAttachment

    data class Audio(
        val data: ChatAudioData,
    ) : PendingAttachment

    data class File(
        val data: ChatFileData,
    ) : PendingAttachment

    data class Link(
        val data: ChatPromptResourceLink,
    ) : PendingAttachment
}

/** Every pending attachment in one horizontal strip, images first, as the prompt will send them. */
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
    val attachments =
        images.map(PendingAttachment::Image) +
            audios.map(PendingAttachment::Audio) +
            files.map(PendingAttachment::File) +
            links.map(PendingAttachment::Link)
    // `minus` drops the first equal element, matching which duplicate the strip keys first.
    val remove: (PendingAttachment) -> Unit = {
        when (it) {
            is PendingAttachment.Image -> onImagesChanged(images - it.data)
            is PendingAttachment.Audio -> onAudiosChanged(audios - it.data)
            is PendingAttachment.File -> onFilesChanged(files - it.data)
            is PendingAttachment.Link -> onLinksChanged(links - it.data)
        }
    }
    AnimatedAttachmentRow(attachments) { displayed -> AttachmentCarousel(displayed, remove) }
}

/**
 * Pending attachments on their own card above the text field, as an M3 multi-browse carousel:
 * the current item large, the next peeking in small.
 */
@Composable
private fun AttachmentCarousel(
    attachments: List<PendingAttachment>,
    onRemove: (PendingAttachment) -> Unit,
) {
    // The state keeps the count lambda from its first composition, so it must read live state.
    val count by rememberUpdatedState(attachments.size)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { count },
            preferredItemWidth = 240.dp,
            itemSpacing = 8.dp,
            modifier = Modifier.height(attachmentCarouselHeight).padding(12.dp),
        ) { index ->
            val item = attachments.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
            // Keyed on the count too: after a removal an equal twin can land in this slot, and
            // it must not inherit the finished exit.
            key(item, attachments.size) {
                RemovableAttachment(
                    item = item,
                    onRemove = onRemove,
                    modifier = Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.large),
                )
            }
        }
    }
}

/**
 * One carousel item. The carousel has no item animations, so removal plays its own exit —
 * shrink and fade — and only then drops the attachment from the list.
 */
@Composable
private fun RemovableAttachment(
    item: PendingAttachment,
    onRemove: (PendingAttachment) -> Unit,
    modifier: Modifier,
) {
    val presence = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val exitSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    Box(
        modifier.graphicsLayer {
            alpha = presence.value
            scaleX = lerp(REMOVED_SCALE, 1f, presence.value)
            scaleY = scaleX
        },
    ) {
        AttachmentContent(item)
        val removeLabel =
            when (item) {
                is PendingAttachment.Image -> R.string.chat_remove_image_desc
                else -> R.string.chat_remove_file_desc
            }
        AttachmentRemoveButton(
            contentDescription = stringResource(removeLabel),
            onRemove = {
                if (!presence.isRunning) {
                    scope.launch {
                        presence.animateTo(0f, exitSpec)
                        onRemove(item)
                    }
                }
            },
        )
    }
}

@Composable
private fun AttachmentContent(item: PendingAttachment) {
    when (item) {
        is PendingAttachment.Image -> PendingImage(item.data)
        is PendingAttachment.Audio ->
            PendingFileTile(item.data.name, FileAttachmentHelper.formatSize(item.data.sizeBytes)) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        is PendingAttachment.File ->
            PendingFileTile(item.data.name, FileAttachmentHelper.formatSize(item.data.sizeBytes)) {
                FileTypeIcon(item.data.name)
            }
        is PendingAttachment.Link -> PendingFileTile(item.data.name, item.data.uri) { FileTypeIcon(item.data.name) }
    }
}

// Scale a removed item shrinks to as it fades, matching the toolbar actions' exit.
private const val REMOVED_SCALE = 0.6f

// Tall enough for a photo to read, as in the M3 compose reference; padding included.
private val attachmentCarouselHeight = 184.dp

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
