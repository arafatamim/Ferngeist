package com.tamimarafat.ferngeist.feature.chat.ui.composer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R
import kotlinx.coroutines.Job
import java.io.File
import java.io.IOException

/** What the agent accepts in a message, which decides what a picked file can become. */
internal data class AttachmentSupport(
    val canSendImages: Boolean,
    val supportsEmbeddedContext: Boolean,
    val canSendAudio: Boolean = false,
)

/** The attachments the composer already holds before a pick lands. */
internal data class HeldAttachments(
    val images: List<ChatImageData>,
    val files: List<ChatFileData>,
    val audios: List<ChatAudioData> = emptyList(),
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
    val newAudios = mutableListOf<ChatAudioData>()
    val fileResults = mutableListOf<FileAttachmentHelper.Result>()
    val audioResults = mutableListOf<FileAttachmentHelper.Result>()
    var imagesDropped = 0
    var unsupportedCount = 0
    for (uri in uris) {
        val mimeType = context.contentResolver.getType(uri)
        val isImage = mimeType?.startsWith("image/") == true
        val isAudio = mimeType?.startsWith("audio/") == true
        when {
            isImage && support.canSendImages -> {
                val image = ImageAttachmentHelper.uriToChatImageData(context.contentResolver, uri)
                if (image != null) newImages += image else imagesDropped++
            }
            isAudio && support.canSendAudio -> {
                val result = FileAttachmentHelper.uriToChatFileData(context.contentResolver, uri)
                audioResults += result
                if (result is FileAttachmentHelper.Result.Success) newAudios += result.file.toAudioData()
            }
            support.supportsEmbeddedContext ->
                fileResults += FileAttachmentHelper.uriToChatFileData(context.contentResolver, uri)
            else -> unsupportedCount++
        }
    }
    val newFiles = fileResults.filterIsInstance<FileAttachmentHelper.Result.Success>().map { it.file }
    val tooLargeCount =
        fileResults.count { it is FileAttachmentHelper.Result.TooLarge } +
            audioResults.count { it is FileAttachmentHelper.Result.TooLarge }
    return combinePickedAttachments(
        held,
        HeldAttachments(newImages, newFiles, newAudios),
        resources,
        PickTally(unsupported = unsupportedCount, tooLarge = tooLargeCount, imagesDropped = imagesDropped),
    )
}

private fun combinePickedAttachments(
    held: HeldAttachments,
    picked: HeldAttachments,
    resources: Resources,
    tally: PickTally,
): PickResult {
    val combinedImages = (held.images + picked.images).take(ImageAttachmentHelper.MAX_IMAGES)
    val imagesCapped = (held.images.size + picked.images.size) - combinedImages.size
    val combinedFiles = (held.files + picked.files).take(FileAttachmentHelper.MAX_FILES)
    val combinedAudios = (held.audios + picked.audios).take(FileAttachmentHelper.MAX_FILES)
    val filesCapped =
        (held.files.size + picked.files.size) - combinedFiles.size +
            (held.audios.size + picked.audios.size) - combinedAudios.size

    return PickResult(
        attachments = HeldAttachments(combinedImages, combinedFiles, combinedAudios),
        feedback =
            buildPickFeedbackMessage(
                resources,
                tally.copy(imagesCapped = imagesCapped, filesCapped = filesCapped),
            ),
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

private fun ChatFileData.toAudioData(): ChatAudioData =
    ChatAudioData(
        name = name,
        base64 = base64,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )

/** The composer's ways to attach: the photo picker, the camera and the document picker. */
internal data class AttachmentPickers(
    val pickPhotos: () -> Unit,
    /** Null when no camera app can handle image capture. */
    val takePhoto: (() -> Unit)?,
    val pickFiles: () -> Unit,
)

/** Launchers for [AttachmentPickers]; every pick lands in [attachUris]. */
@Composable
internal fun rememberAttachmentPickers(
    attachUris: (List<Uri>) -> Job?,
    onCameraFailure: (String) -> Unit,
): AttachmentPickers {
    val context = LocalContext.current
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { attachUris(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attachUris(it) }
    // Saveable: the camera app can outlive this process, and its result only says whether it wrote.
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = captureUri?.toUri() ?: return@rememberLauncherForActivityResult
            captureUri = null
            val deleteCapture: () -> Unit = { context.contentResolver.delete(uri, null, null) }
            val ingest = if (saved) attachUris(listOf(uri)) else null
            ingest?.invokeOnCompletion { deleteCapture() } ?: deleteCapture()
        }
    val hasCamera =
        remember(context) { Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null }
    return remember(context, photos, files, camera, hasCamera, onCameraFailure) {
        val launchCamera: () -> Unit = {
            if (captureUri == null) {
                runCatching {
                    val uri = context.newCaptureUri()
                    captureUri = uri.toString()
                    camera.launch(uri)
                }.onFailure { failure ->
                    if (failure !is IOException && failure !is ActivityNotFoundException) throw failure
                    captureUri?.let { context.contentResolver.delete(it.toUri(), null, null) }
                    captureUri = null
                    onCameraFailure(context.getString(R.string.chat_camera_unavailable))
                }
            }
        }
        AttachmentPickers(
            pickPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            takePhoto = launchCamera.takeIf { hasCamera },
            pickFiles = { files.launch(arrayOf("*/*")) },
        )
    }
}

/**
 * A fresh file for the camera app to write into, shared through the chat's file provider.
 * Each composer owns its capture until ingestion completes, including when multiple panes are open.
 */
private fun Context.newCaptureUri(): Uri {
    val dir = File(cacheDir, CAMERA_CAPTURE_DIR).apply { mkdirs() }
    val file = File.createTempFile("capture-", ".jpg", dir)
    return FileProvider.getUriForFile(this, "$packageName.artifacts", file)
}

// Matches the camera path in res/xml/artifact_paths.xml.
private const val CAMERA_CAPTURE_DIR = "camera-captures"
