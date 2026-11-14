package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactReader
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.ArtifactCache
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.CachedArtifact
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.exportArtifact
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.shareArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What an attachment card does with its file, bound to the chat that referenced it. */
@Stable
internal class ArtifactHost(
    /** Fetches [path] and opens it; throws when it cannot be read. */
    val open: suspend (path: String) -> Unit,
    /** Asks where to save [path] as [name], then saves it. */
    val download: (path: String, name: String) -> Unit,
    /** Describes [paths] without reading them, one entry per path in order; throws when the host cannot be asked. */
    val stat: suspend (paths: List<String>) -> List<ChatArtifactStat>,
)

/** Null where the chat has no gateway workspace, so no card renders. */
internal val localArtifactHost = compositionLocalOf<ArtifactHost?> { null }

/** The files the turn's tool calls name, so a bare name in prose resolves to the file the turn touched. */
internal val localTurnPaths = compositionLocalOf<Set<String>> { emptySet() }

/** Runs [block], false when it failed; cancellation still propagates. */
internal suspend fun succeeds(block: suspend () -> Unit): Boolean =
    runCatching { block() }.onFailure { if (it is CancellationException) throw it }.isSuccess

private data class PendingArtifactDownload(
    val path: String,
    val identity: String,
)

private val pendingDownloadSaver =
    listSaver<PendingArtifactDownload?, String>(
        save = { pending -> pending?.let { listOf(it.path, it.identity) }.orEmpty() },
        restore = { saved -> saved.takeIf { it.isNotEmpty() }?.let { PendingArtifactDownload(it[0], it[1]) } },
    )

/**
 * The chat's [ArtifactHost]: files open in another app through a read-only content URI, and save
 * through the system picker. Null when [enabled] is false.
 */
@Composable
internal fun rememberArtifactHost(
    reader: () -> ChatArtifactReader?,
    cache: ArtifactCache,
    io: CoroutineDispatcher,
    snackbar: SnackbarHostState,
    enabled: Boolean,
): ArtifactHost? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentReader by rememberUpdatedState(reader)
    // The reader and path a Download chose, kept while the picker is open so the save reads
    // from the chat the card belongs to even if it reconnects meanwhile.
    var pending by rememberSaveable(stateSaver = pendingDownloadSaver) {
        mutableStateOf<PendingArtifactDownload?>(null)
    }
    var pendingReader by remember { mutableStateOf<ChatArtifactReader?>(null) }
    val noViewer = stringResource(R.string.artifact_no_viewer)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val request = pending ?: return@rememberLauncherForActivityResult
            val bound = pendingReader ?: currentReader()?.takeIf { it.identity == request.identity }
            pending = null
            pendingReader = null
            // No destination means the reader cancelled: nothing is saved and nothing is reported.
            if (uri != null) scope.launch { snackbar.showSnackbar(context.save(cache, io, bound, request.path, uri)) }
        }
    if (!enabled) return null
    return remember(context, cache, picker, snackbar, noViewer) {
        ArtifactHost(
            open = { path ->
                val bound = currentReader() ?: throw IOException("No gateway workspace to read $path from")
                cache.use(bound, path) { artifact ->
                    val shared = withContext(io) { shareArtifact(artifact, File(context.cacheDir, "artifact-shares")) }
                    if (!context.view(artifact.copy(file = shared))) snackbar.showSnackbar(noViewer)
                }
            },
            download = { path, name ->
                currentReader()?.let {
                    pending = PendingArtifactDownload(path, it.identity)
                    pendingReader = it
                    picker.launch(name)
                }
            },
            stat = { paths ->
                val bound = currentReader() ?: throw IOException("No gateway workspace to describe files from")
                bound.stat(paths)
            },
        )
    }
}

/** The snackbar message for saving [path] to [destination]; a failed save removes the partial document. */
private suspend fun Context.save(
    cache: ArtifactCache,
    io: CoroutineDispatcher,
    reader: ChatArtifactReader?,
    path: String,
    destination: Uri,
): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    var saved = false
    try {
        saved =
            succeeds {
                cache.use(checkNotNull(reader) { "The original gateway is unavailable" }, path) { artifact ->
                    val open = { contentResolver.openOutputStream(destination, "wt") }
                    withContext(io) { exportArtifact(artifact, open) }
                }
            }
        return getString(if (saved) R.string.artifact_saved else R.string.artifact_save_failed, name)
    } finally {
        if (!saved) {
            withContext(NonCancellable + io) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
            }
        }
    }
}

/** Opens [artifact] in another app under a temporary read grant; false when no app can show it. */
private fun Context.view(artifact: CachedArtifact): Boolean {
    val uri = FileProvider.getUriForFile(this, "$packageName.artifacts", artifact.file)
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, artifact.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { startActivity(view) }.onFailure { if (it !is ActivityNotFoundException) throw it }.isSuccess
}
