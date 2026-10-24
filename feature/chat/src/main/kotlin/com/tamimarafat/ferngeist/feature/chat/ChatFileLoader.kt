package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatWorkspaceReader
import com.tamimarafat.ferngeist.feature.chat.ui.diff.diffLines
import com.tamimarafat.ferngeist.feature.chat.ui.entryFor
import io.github.diff.DeltaType
import io.github.diff.generatePatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The file sheet's file: [path] as the transcript named it. */
data class FileView(
    val path: String,
    val isLoading: Boolean = true,
    val text: String? = null,
    /** Lines (0-based) added or changed since `HEAD`. */
    val changedLines: Set<Int> = emptySet(),
    /** Lines that deleted lines sat just above; the line count for lines deleted at the end. */
    val deletedAbove: Set<Int> = emptySet(),
    /** Cut at the gateway's 1 MiB limit. */
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val error: String? = null,
    /** The commit the text and gutter are read from; null for the working tree. */
    val commit: String? = null,
    /** The ref the working tree is marked against; null for `HEAD` or a commit's own parent. */
    val compare: String? = null,
    /** Gone in this revision, so [text] is empty and there is nothing to show but the notice. */
    val deleted: Boolean = false,
)

/**
 * Reads the file a chip or an "open full file" button names into [ChatState.openFile], as the
 * revision it was asked for: git's diff when the working tree has the file changed, whose new side
 * is the whole file and whose old side marks what changed, else the plain file. A commit's file
 * and a comparison read the diff the git sheet shows for it, so the whole file matches the
 * revision the reader tapped from.
 */
internal class ChatFileLoader(
    private val scope: CoroutineScope,
    private val facade: ChatWorkspaceReader,
    private val update: (ChatState.() -> ChatState) -> Unit,
    /** The project directory the gateway reads paths relative to. */
    private val cwd: String,
    private val tree: () -> List<ChatChangedFile>,
) {
    /**
     * The read in flight; a newer open or a close replaces it, and a replaced read never lands.
     * Cancels, the cancelling side of the platform limit described in `ChatGitHistoryLoader`.
     */
    private var job: Job? = null

    fun open(
        path: String,
        commit: String? = null,
        compare: String? = null,
        oldPath: String? = null,
    ) {
        job?.cancel()
        val relative = path.relativeTo(cwd)
        if (relative == null) {
            job = null
            update {
                copy(
                    openFile =
                        FileView(
                            path,
                            isLoading = false,
                            commit = commit,
                            compare = compare,
                            error = "$path is outside the project",
                        ),
                )
            }
            return
        }
        update { copy(openFile = FileView(path, commit = commit, compare = compare)) }
        job =
            scope.launch {
                val result = runCatching { read(relative, commit, compare, oldPath) }
                if (job !== coroutineContext[Job]) return@launch
                job = null
                val file =
                    result.fold(
                        onSuccess = { view ->
                            view?.copy(path = path) ?: FileView(
                                path,
                                isLoading = false,
                                commit = commit,
                                compare = compare,
                                error = NO_WORKSPACE,
                            )
                        },
                        onFailure = { error ->
                            if (error is CancellationException) return@launch
                            val message = error.message?.takeIf { it.isNotBlank() } ?: "Failed to load $path"
                            FileView(path, isLoading = false, commit = commit, compare = compare, error = message)
                        },
                    )
                update { copy(openFile = file) }
            }
    }

    fun close() {
        job?.cancel()
        job = null
        update { copy(openFile = null) }
    }

    private suspend fun read(
        relative: String,
        commit: String?,
        compare: String?,
        oldPath: String?,
    ): FileView? {
        if (commit != null || compare != null) {
            val diff = facade.fetchGitDiff(relative, commit, oldPath, compare)?.firstOrNull()
            return diff?.marked(commit = commit, compare = compare)
        }
        val entry = tree().entryFor(relative)
        if (entry == null) {
            return facade.fetchFile(relative)?.let { file ->
                FileView(
                    "",
                    isLoading = false,
                    text = file.text,
                    binary = file.text == null,
                    truncated = file.truncated,
                )
            }
        }
        val diff = facade.fetchGitDiff(entry.path, oldPath = entry.oldPath)?.firstOrNull()
        // A deletion has no text on the new side, so the diff is what says the file is gone.
        return diff?.marked(commit = null, compare = null, deleted = entry.status == "D")
    }

    private companion object {
        const val NO_WORKSPACE = "No gateway workspace connection to read the file"
    }
}

/** [this] as a whole file, its gutter marked with what its revision changed. */
private fun ToolCallContent.Diff.marked(
    commit: String?,
    compare: String?,
    deleted: Boolean = false,
): FileView =
    markedFile(oldText, newText).copy(
        commit = commit,
        compare = compare,
        deleted = deleted || (oldText != null && newText.isEmpty()),
    )

/**
 * [this] relative to the project directory [cwd], as the gateway reads it; null for an absolute
 * path outside it, which the gateway would refuse.
 */
internal fun String.relativeTo(cwd: String): String? {
    val path = replace('\\', '/')
    val root = cwd.replace('\\', '/').trimEnd('/')
    return when {
        root.isNotEmpty() && path.startsWith("$root/") -> path.removePrefix("$root/")
        path.startsWith('/') || windowsRoot.containsMatchIn(path) -> null
        else -> path.removePrefix("./")
    }
}

private val windowsRoot = Regex("^[A-Za-z]:/")

/** [newText] as the whole file, its gutter marked with what changed since [oldText] (null: all new). */
internal fun markedFile(
    oldText: String?,
    newText: String,
): FileView {
    val newLines = newText.diffLines()
    val changed = HashSet<Int>()
    val deleted = HashSet<Int>()
    if (oldText == null) {
        changed += newLines.indices
    } else {
        val patch =
            generatePatch {
                original = oldText.diffLines()
                revised = newLines
            }
        patch.getDeltas().forEach { delta ->
            val target = delta.target
            when (delta.type) {
                DeltaType.DELETE -> deleted += target.position
                DeltaType.INSERT, DeltaType.CHANGE ->
                    changed +=
                        target.position until target.position + target.lines.size
                DeltaType.EQUAL -> Unit
            }
        }
    }
    return FileView("", isLoading = false, text = newText, changedLines = changed, deletedAbove = deleted)
}
