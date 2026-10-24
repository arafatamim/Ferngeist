package com.tamimarafat.ferngeist.feature.chat.ui.composer

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R

/** What the agent accepts in a message, which decides what a picked file can become. */
internal data class AttachmentSupport(
    val canSendImages: Boolean,
    val supportsEmbeddedContext: Boolean,
)

/** The attachments the composer already holds before a pick lands. */
internal data class HeldAttachments(
    val images: List<ChatImageData>,
    val files: List<ChatFileData>,
)

/** The attachments after a pick, with the feedback to show when some were dropped or capped. */
internal data class PickResult(
    val attachments: HeldAttachments,
    val feedback: String?,
)

/** How many picked items were dropped or capped, for the feedback message. */
private data class PickTally(
    val unsupported: Int = 0,
    val tooLarge: Int = 0,
    val imagesDropped: Int = 0,
    val imagesCapped: Int = 0,
    val filesCapped: Int = 0,
)

/**
 * Processes picked URIs into images and file attachments, returning the combined
 * lists (capped at max) and an optional feedback message for the user.
 * Extracted from [ChatScreen] to reduce its cyclomatic complexity.
 */
internal suspend fun processPickedUris(
    uris: List<Uri>,
    context: Context,
    support: AttachmentSupport,
    held: HeldAttachments,
    resources: Resources,
): PickResult {
    val newImages = mutableListOf<ChatImageData>()
    val fileResults = mutableListOf<FileAttachmentHelper.Result>()
    var imagesDropped = 0
    var unsupportedCount = 0
    for (uri in uris) {
        val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true
        when {
            isImage && support.canSendImages -> {
                val image = ImageAttachmentHelper.uriToChatImageData(context.contentResolver, uri)
                if (image != null) newImages += image else imagesDropped++
            }
            support.supportsEmbeddedContext ->
                fileResults += FileAttachmentHelper.uriToChatFileData(context.contentResolver, uri)
            else -> unsupportedCount++
        }
    }
    val newFiles = fileResults.filterIsInstance<FileAttachmentHelper.Result.Success>().map { it.file }
    val tooLargeCount = fileResults.count { it is FileAttachmentHelper.Result.TooLarge }

    val combinedImages = (held.images + newImages).take(ImageAttachmentHelper.MAX_IMAGES)
    val imagesCapped = (held.images.size + newImages.size) - combinedImages.size
    val combinedFiles = (held.files + newFiles).take(FileAttachmentHelper.MAX_FILES)
    val filesCapped = (held.files.size + newFiles.size) - combinedFiles.size

    val tally =
        PickTally(
            unsupported = unsupportedCount,
            tooLarge = tooLargeCount,
            imagesDropped = imagesDropped,
            imagesCapped = imagesCapped,
            filesCapped = filesCapped,
        )
    return PickResult(
        attachments = HeldAttachments(combinedImages, combinedFiles),
        feedback = buildPickFeedbackMessage(resources, tally),
    )
}

private fun buildPickFeedbackMessage(
    resources: Resources,
    tally: PickTally,
): String? =
    when {
        tally.unsupported > 0 ->
            resources.getQuantityString(
                R.plurals.chat_attachments_unsupported,
                tally.unsupported,
                tally.unsupported,
            )
        tally.tooLarge > 0 ->
            resources.getQuantityString(
                R.plurals.chat_files_too_large,
                tally.tooLarge,
                tally.tooLarge,
            )
        tally.imagesDropped > 0 ->
            resources.getQuantityString(
                R.plurals.chat_images_dropped,
                tally.imagesDropped,
                tally.imagesDropped,
            )
        tally.imagesCapped > 0 ->
            resources.getQuantityString(
                R.plurals.chat_images_capped,
                ImageAttachmentHelper.MAX_IMAGES,
                ImageAttachmentHelper.MAX_IMAGES,
            )
        tally.filesCapped > 0 ->
            resources.getQuantityString(
                R.plurals.chat_files_capped,
                FileAttachmentHelper.MAX_FILES,
                FileAttachmentHelper.MAX_FILES,
            )
        else -> null
    }
