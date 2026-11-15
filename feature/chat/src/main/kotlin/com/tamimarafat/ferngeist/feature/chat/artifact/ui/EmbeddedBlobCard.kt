package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.blob.BLOB_CACHE_BUDGET_BYTES
import com.tamimarafat.ferngeist.core.common.blob.BLOB_CACHE_DIRECTORY
import com.tamimarafat.ferngeist.core.common.blob.BlobCache
import com.tamimarafat.ferngeist.core.model.CachedBlob
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.CachedArtifact
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.exportArtifact
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.shareArtifact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
internal fun EmbeddedBlobCard(
    blob: CachedBlob,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val cache = remember(context) { BlobCache(File(context.cacheDir, BLOB_CACHE_DIRECTORY)) }
    val scope = rememberCoroutineScope()
    var failed by remember(blob) { mutableStateOf(blob.key == null) }
    var loading by remember { mutableStateOf(false) }
    val present by produceState<Boolean?>(null, blob.key) {
        value = withContext(io) { runCatching { blob.key?.let(cache::contains) == true }.getOrDefault(false) }
    }
    val download = rememberBlobDownload(blob, cache, io) { failed = true }
    AttachmentCard(
        path = blob.name,
        label = blob.name,
        size = blob.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
        loading = loading,
        failed = failed || present == false,
        onOpen = {
            if (!loading) {
                scope.launch {
                    loading = true
                    failed = !openBlob(context, cache, blob, io)
                    loading = false
                }
            }
        },
        onDownload = download,
        modifier = Modifier.widthIn(max = 128.dp).fillMaxWidth().aspectRatio(1f),
        failureMessage =
            stringResource(
                if ((blob.sizeBytes ?: 0) > BLOB_CACHE_BUDGET_BYTES) {
                    R.string.embedded_blob_too_large
                } else {
                    R.string.embedded_blob_unavailable
                },
            ),
    )
}

@Composable
private fun rememberBlobDownload(
    blob: CachedBlob,
    cache: BlobCache,
    io: CoroutineDispatcher,
    onFailure: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (pending && uri != null) {
                scope.launch {
                    val saved = saveBlob(context, cache, blob, uri, io)
                    if (!saved) onFailure()
                    val message =
                        context.getString(
                            if (saved) R.string.artifact_saved else R.string.artifact_save_failed,
                            blob.name,
                        )
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            pending = false
        }
    return {
        pending = true
        picker.launch(blob.name)
    }
}

private suspend fun openBlob(
    context: Context,
    cache: BlobCache,
    blob: CachedBlob,
    io: CoroutineDispatcher,
): Boolean =
    succeeds {
        val artifact =
            withContext(io) {
                blob.key?.let { key ->
                    cache.use(key) { file ->
                        val cached = CachedArtifact(file, blob.mimeType, file.length())
                        cached.copy(file = shareArtifact(cached, File(context.cacheDir, "artifact-shares"), blob.name))
                    }
                } ?: throw IOException("Attachment unavailable")
            }
        if (!context.view(artifact)) Toast.makeText(context, R.string.artifact_no_viewer, Toast.LENGTH_SHORT).show()
    }

private suspend fun saveBlob(
    context: Context,
    cache: BlobCache,
    blob: CachedBlob,
    destination: Uri,
    io: CoroutineDispatcher,
): Boolean {
    var saved = false
    try {
        saved =
            succeeds {
                withContext(io) {
                    val result =
                        blob.key?.let { key ->
                            cache.use(key) { file ->
                                exportArtifact(CachedArtifact(file, blob.mimeType, file.length())) {
                                    context.contentResolver.openOutputStream(destination, "wt")
                                }
                            }
                        }
                    if (result == null) throw IOException("Attachment unavailable")
                }
            }
        return saved
    } finally {
        if (!saved) {
            withContext(NonCancellable + io) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            }
        }
    }
}
