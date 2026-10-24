package com.tamimarafat.ferngeist.feature.chat.ui

import android.icu.text.MeasureFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.diff.DiffStats
import com.tamimarafat.ferngeist.feature.chat.ui.diff.computeDiffStats
import com.tamimarafat.ferngeist.feature.chat.ui.files.diffs
import com.tamimarafat.ferngeist.feature.chat.ui.files.editedPaths
import com.tamimarafat.ferngeist.feature.chat.ui.files.landed
import kotlin.random.Random

/**
 * Git's entry for [path], from the working tree as it is now. [path] is usually absolute and git's
 * relative to the repo root, so a suffix on a path boundary is a match; the longest such entry wins,
 * so `src/X.kt` is not mistaken for a root `X.kt`.
 */
internal fun List<ChatChangedFile>.entryFor(path: String): ChatChangedFile? = matching(path, dirs = false)

/** Git's counts for [path]; see [entryFor] for how it is matched. Unlike it, a directory entry counts. */
internal fun List<ChatChangedFile>.statsFor(path: String): DiffStats? =
    matching(path, dirs = true)?.let { DiffStats(additions = it.added, deletions = it.removed) }

private fun List<ChatChangedFile>.matching(
    path: String,
    dirs: Boolean,
): ChatChangedFile? {
    val normalized = path.replace('\\', '/')
    return filter {
        !it.binary && (dirs || !it.isDir) && (normalized == it.path || normalized.endsWith("/" + it.path))
    }.maxByOrNull { it.path.length }
}

/** Git's status and counts for an entry: a request for the same file at a different value refetches. */
internal fun ChatChangedFile.revision() = "$status:$added:$removed"

/**
 * The working tree's changed files, provided to the newest turn only: git reports the tree as it is
 * now, so counts it gave an older turn would include everything since.
 */
internal val localWorkingTreeChanges = compositionLocalOf { emptyList<ChatChangedFile>() }

/**
 * Git's changed files that can stand for the newest turn's work: a file already changed when the
 * turn started would mix earlier work into its diff and counts, so it is left out.
 */
internal fun ChatState.turnWorkingTree(): List<ChatChangedFile> {
    val tree = gitStatus?.changed.orEmpty()
    val dirty = turnDirtyAtStart ?: return tree
    return tree.filterNot { it.path in dirty }
}

/**
 * Working-tree diffs backing the latest turn's files whose tool calls reported
 * none, with the request that loads one. Provided where the chat state is at
 * hand; empty (no-op request) anywhere else, e.g. previews.
 */
internal data class TurnGitFallback(
    val diffs: Map<String, List<ToolCallContent.Diff>> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val onRequest: (path: String, oldPath: String?, revision: String) -> Unit = { _, _, _ -> },
    /** Re-reads git's status, so a file the running turn just changed is listed. */
    val onRefresh: () -> Unit = {},
)

internal val localTurnGitFallback = compositionLocalOf { TurnGitFallback() }

/** One file a turn changed: the diffs the turn reported for it (maybe none), and the last call that touched it. */
internal class FileChange(
    val path: String,
    val diffs: List<ToolCallContent.Diff>,
    val segmentId: String,
)

/**
 * Git's counts when the working tree has the file (newest turn only), else the reported diffs:
 * summing every edit's diff overstates a file edited more than once.
 */
internal fun FileChange.stats(tree: List<ChatChangedFile>): DiffStats? =
    tree.statsFor(path) ?: diffs.takeIf { it.isNotEmpty() }?.computeDiffStats()

private const val TURN_FILE_SEPARATOR = '\u0000'

/** What a turn file's row selects: its last call's segment, then the file, so the sheet can open the whole file. */
internal fun FileChange.selectionId(): String = "$segmentId$TURN_FILE_SEPARATOR$path"

/** The segment a tool call selection names, whether it came from a call's row or a turn file's. */
internal fun String.selectedSegmentId(): String = substringBefore(TURN_FILE_SEPARATOR)

/**
 * The call a selection opens. A turn file's row opens what its counts came from (see [stats]):
 * while git counts the file, a diff-less EDIT call on it, which [gitFallbackDiffs] fills with git's
 * diff; else every diff the turn reported for it, not only its last call's. [newestTree] is the
 * working tree the newest turn counts against; older turns never use git.
 */
internal fun List<ChatMessage>.toolCallForSelection(
    selectionId: String?,
    newestTree: List<ChatChangedFile>,
): ToolCallDisplay? {
    val segmentId = selectionId?.selectedSegmentId() ?: return null
    val path = selectionId.substringAfter(TURN_FILE_SEPARATOR, "")
    val call = toolCallForSegment(segmentId) ?: return null
    val message = lastOrNull { candidate -> candidate.segments.any { it.id == segmentId } }
    val tree = if (message === lastOrNull()) newestTree else emptyList()
    val file =
        message
            ?.segments
            ?.fileChanges()
            ?.firstOrNull { it.path == path && (it.diffs.isNotEmpty() || tree.statsFor(path) != null) }
            ?: return call
    return ToolCallDisplay(
        title = call.title,
        kind = ToolKind.EDIT,
        status = call.status,
        content = if (tree.statsFor(path) != null) emptyList() else file.diffs,
        locations = listOf(path),
    )
}

/**
 * The files a turn changed, in the order each was first touched. A failed call's diff never
 * landed, so it is left out. ponytail: repeated edits to one file sum their counts rather than
 * netting them; diff first-old against last-new if that ever misleads.
 */
internal fun List<AssistantSegment>.fileChanges(): List<FileChange> {
    val byPath = LinkedHashMap<String, FileChange>()
    for (segment in this) {
        val call = segment.toolCall?.takeIf { it.landed() } ?: continue
        val diffs = call.diffs()
        // An edit with nothing to count still changed its file: listed, with no diff blocks.
        if (diffs.isEmpty()) {
            call.editedPaths().forEach { path ->
                byPath[path] = FileChange(path, byPath[path]?.diffs.orEmpty(), segment.id)
            }
        }
        diffs.forEach { diff ->
            byPath[diff.path] = FileChange(diff.path, byPath[diff.path]?.diffs.orEmpty() + diff, segment.id)
        }
    }
    return byPath.values.toList()
}

/**
 * The turn's close: how long it ran and when it finished, then its edits, "3 files changed" with the
 * total diff, folding open into one row per file. A file whose calls reported no diff takes git's
 * counts while it is the newest turn, and otherwise shows none. With no edits, only the time.
 */
@Composable
internal fun TurnChanges(
    segments: List<AssistantSegment>,
    turn: TurnTiming?,
    onToolCallClick: (String) -> Unit,
    foldsInFlight: MutableIntState,
    modifier: Modifier = Modifier,
) {
    val files = remember(segments) { segments.fileChanges() }
    val timing = turn?.let { turnSummary(it) }
    val separator = stringResource(R.string.chat_turn_separator)
    if (files.isEmpty()) {
        timing?.let {
            PhrasesText(
                text = it,
                separator = separator,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = modifier.padding(vertical = 4.dp),
            )
        }
        return
    }
    val tree = localWorkingTreeChanges.current
    val stats =
        remember(files, tree) {
            files.map { it.stats(tree) }
        }
    val counted = stats.filterNotNull()
    val total =
        counted.takeIf { it.isNotEmpty() }?.let {
            DiffStats(additions = it.sumOf { s -> s.additions }, deletions = it.sumOf { s -> s.deletions })
        }
    // One counted file's stats would repeat the header's total, so only the header shows them.
    val rowStats = counted.size > 1
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ToolGroupHeader(
            // "3 files changed", not "Edited 3 files": an outcome, so it doesn't read as one more call.
            summary =
                joinPhrases(
                    listOfNotNull(
                        timing,
                        pluralStringResource(R.plurals.chat_git_files_changed, files.size, files.size),
                    ),
                    separator,
                ),
            separator = separator,
            stats = total,
            open = open,
            active = false,
            onClick = { open = !open },
            chevronAtEnd = true,
        )
        FoldBody(open = open, foldsInFlight = foldsInFlight) {
            SettledRail(rowCount = files.size) {
                files.forEachIndexed { index, file ->
                    // Shaped like a rail row: the branch leads straight into the path.
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(railRowHeight)
                                .handCursor()
                                .clickable { onToolCallClick(file.selectionId()) }
                                .padding(start = railTextStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The name and its directory share one flexible slot. Measured loose, a long
                        // name takes the whole row before the stats are measured and pushes them off.
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileChip(file.path)
                            Text(
                                text = file.path.replace('\\', '/').substringBeforeLast('/', ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                        }
                        stats[index]?.takeIf { rowStats }?.let {
                            DiffStatsRow(it, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** "Brewed for 1m 20s · done 2:24 AM": the verb is seeded by the turn, so it stays put across reloads. */
@Composable
private fun turnSummary(turn: TurnTiming): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val verb =
        remember(turn.startedAt, resources) {
            val verbs = resources.getStringArray(R.array.chat_turn_verbs)
            verbs[Random(turn.startedAt).nextInt(verbs.size)]
        }
    val elapsed = elapsedOf((turn.finishedAt - turn.startedAt).coerceAtLeast(MS_PER_SECOND)) ?: Elapsed(0, 0, 1)
    // Today reads as a time of day; anything older needs its date too.
    val flags =
        DateUtils.FORMAT_SHOW_TIME or
            if (DateUtils.isToday(turn.finishedAt)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    val done = remember(turn.finishedAt, flags) { DateUtils.formatDateTime(context, turn.finishedAt, flags) }
    return joinPhrases(
        listOf(
            stringResource(R.string.chat_turn_took, verb, formatElapsed(elapsed, MeasureFormat.FormatWidth.NARROW)),
            stringResource(R.string.chat_turn_done, done),
        ),
        stringResource(R.string.chat_turn_separator),
    )
}
