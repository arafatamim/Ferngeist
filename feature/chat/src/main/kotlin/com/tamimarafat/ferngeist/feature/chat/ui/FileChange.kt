package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val PATH_KEYS = listOf("file_path", "filePath", "path", "filename")
private val CONTENT_KEYS = listOf("content", "contents", "text", "file_text")

// Search-and-replace pairs as agents name them in an edit's raw input.
private val REPLACEMENT_KEYS =
    listOf(
        "old_string" to "new_string",
        "oldString" to "newString",
        "old_str" to "new_str",
        "old_text" to "new_text",
        "oldText" to "newText",
    )

/**
 * The call's diffs. When an EDIT call reports none, they are rebuilt exactly from its raw input:
 * a write's contents count as a new file, every line added; a search-and-replace (or each entry of
 * an `edits` list) becomes a diff of the replaced text.
 */
internal fun ToolCallDisplay.diffs(): List<ToolCallContent.Diff> {
    val reported = content.orEmpty().filterIsInstance<ToolCallContent.Diff>()
    val path = editedPath()
    val input = rawInput as? JsonObject
    return if (reported.isEmpty() && path != null && input != null) input.inferredDiffs(path) else reported
}

// ponytail: a replace-all edit counts its replacement once; multiply by occurrences if that misleads.
private fun JsonObject.inferredDiffs(path: String): List<ToolCallContent.Diff> {
    val written = firstString(CONTENT_KEYS)
    val edits = (get("edits") as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(this)
    return if (written != null) {
        listOf(ToolCallContent.Diff(path = path, oldText = null, newText = written))
    } else {
        edits.mapNotNull { it.replacement(path) }
    }
}

private fun JsonObject.replacement(path: String): ToolCallContent.Diff? =
    REPLACEMENT_KEYS.firstNotNullOfOrNull { (oldKey, newKey) ->
        val old = firstString(listOf(oldKey))
        val new = firstString(listOf(newKey))
        if (old != null && new != null) ToolCallContent.Diff(path = path, oldText = old, newText = new) else null
    }

/** The file an EDIT call names in its raw input, whether or not it reported what it changed. */
private fun ToolCallDisplay.editedPath(): String? =
    (rawInput as? JsonObject)?.firstString(PATH_KEYS)?.takeIf { kind == ToolKind.EDIT }

/**
 * Every file an EDIT call says it touched: its raw-input path, or else its ACP `locations`, which
 * name files even for agents that report nothing else about a write.
 */
private fun ToolCallDisplay.editedPaths(): List<String> =
    editedPath()?.let(::listOf) ?: locations.orEmpty().takeIf { kind == ToolKind.EDIT }.orEmpty()

/**
 * Git's counts for [path], from the working tree as it is now. [path] is usually absolute and git's
 * relative to the repo root, so a suffix on a path boundary is a match.
 */
internal fun List<GatewayChangedFile>.statsFor(path: String): DiffStats? {
    val normalized = path.replace('\\', '/')
    return firstOrNull { !it.binary && (normalized == it.path || normalized.endsWith("/" + it.path)) }
        ?.let { DiffStats(additions = it.added, deletions = it.removed) }
}

/**
 * The working tree's changed files, provided to the newest turn only: git reports the tree as it is
 * now, so counts it gave an older turn would include everything since.
 */
internal val LocalWorkingTreeChanges = compositionLocalOf { emptyList<GatewayChangedFile>() }

private fun JsonObject.firstString(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key -> (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content }

/** One file a turn changed: the diffs the turn reported for it (maybe none), and the last call that touched it. */
internal class FileChange(
    val path: String,
    val diffs: List<ToolCallContent.Diff>,
    val segmentId: String,
)

/**
 * The files a turn changed, in the order each was first touched. A failed call's diff never
 * landed, so it is left out. ponytail: repeated edits to one file sum their counts rather than
 * netting them; diff first-old against last-new if that ever misleads.
 */
internal fun List<AssistantSegment>.fileChanges(): List<FileChange> {
    val byPath = LinkedHashMap<String, FileChange>()
    for (segment in this) {
        val call = segment.toolCall?.takeIf { it.status != ToolCallStatus.FAILED } ?: continue
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
 * The turn's edits at its end: "3 files changed" with the total diff, folding open into one row per
 * file. A file whose calls reported no diff takes git's counts while it is the newest turn, and
 * otherwise shows none.
 */
@Composable
internal fun TurnChanges(
    segments: List<AssistantSegment>,
    onToolCallClick: (String) -> Unit,
    foldsInFlight: MutableIntState,
    modifier: Modifier = Modifier,
) {
    val files = remember(segments) { segments.fileChanges() }
    if (files.isEmpty()) return
    val tree = LocalWorkingTreeChanges.current
    val stats =
        remember(files, tree) {
            files.map { file -> file.diffs.takeIf { it.isNotEmpty() }?.computeDiffStats() ?: tree.statsFor(file.path) }
        }
    val total =
        stats.filterNotNull().takeIf { it.isNotEmpty() }?.let { counted ->
            DiffStats(additions = counted.sumOf { it.additions }, deletions = counted.sumOf { it.deletions })
        }
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ToolGroupHeader(
            // "3 files changed", not "Edited 3 files": an outcome, so it doesn't read as one more call.
            summary = pluralStringResource(R.plurals.chat_git_files_changed, files.size, files.size),
            stats = total,
            open = open,
            active = false,
            onClick = { open = !open },
        )
        FoldBody(open = open, foldsInFlight = foldsInFlight) {
            SettledRail(rowCount = files.size) {
                files.forEachIndexed { index, file ->
                    // Shaped like a rail row: the branch leads straight into the path.
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(RAIL_ROW_HEIGHT)
                                .clickable { onToolCallClick(file.segmentId) }
                                .padding(start = RAIL_TEXT_START),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            // The file name is the part worth keeping.
                            overflow = TextOverflow.StartEllipsis,
                            modifier = Modifier.weight(1f),
                        )
                        stats[index]?.let { DiffStatsRow(it, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}
