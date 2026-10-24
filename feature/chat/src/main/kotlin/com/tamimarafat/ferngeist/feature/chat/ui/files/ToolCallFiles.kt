package com.tamimarafat.ferngeist.feature.chat.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.ui.entryFor
import com.tamimarafat.ferngeist.feature.chat.ui.fileName
import com.tamimarafat.ferngeist.feature.chat.ui.localTurnGitFallback
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkingTreeChanges
import com.tamimarafat.ferngeist.feature.chat.ui.revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val pathKeys = listOf("file_path", "filePath", "path", "filename")

internal val contentKeys = listOf("content", "contents", "text", "file_text")

// Search-and-replace pairs as agents name them in an edit's raw input.

internal val replacementKeys =
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

internal fun JsonObject.inferredDiffs(path: String): List<ToolCallContent.Diff> {
    val written = firstString(contentKeys)
    val edits = (get("edits") as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(this)
    return if (written != null) {
        listOf(ToolCallContent.Diff(path = path, oldText = null, newText = written))
    } else {
        edits.mapNotNull { it.replacement(path) }
    }
}

internal fun JsonObject.replacement(path: String): ToolCallContent.Diff? =
    replacementKeys.firstNotNullOfOrNull { (oldKey, newKey) ->
        val old = firstString(listOf(oldKey))
        val new = firstString(listOf(newKey))
        if (old != null && new != null) ToolCallContent.Diff(path = path, oldText = old, newText = new) else null
    }

/** The file an EDIT call names in its raw input, whether or not it reported what it changed. */
internal fun ToolCallDisplay.editedPath(): String? =
    (rawInput as? JsonObject)?.firstString(pathKeys)?.takeIf { kind == ToolKind.EDIT }

/**
 * Every file an EDIT call says it touched: its raw-input path, or else its ACP `locations`, which
 * name files even for agents that report nothing else about a write.
 */
internal fun ToolCallDisplay.editedPaths(): List<String> =
    editedPath()?.let(::listOf) ?: locations.orEmpty().takeIf { kind == ToolKind.EDIT }.orEmpty()

internal val fileKinds = setOf(ToolKind.READ, ToolKind.EDIT, ToolKind.DELETE, ToolKind.MOVE)

/** The file a read, edit, delete or move works on, from its raw input or else its first location. */
internal fun ToolCallDisplay.filePath(): String? =
    ((rawInput as? JsonObject)?.firstString(pathKeys) ?: locations?.firstOrNull())
        ?.takeIf { kind in fileKinds && it.isNotBlank() }

/** The call's title, with its file's name added when the agent's title leaves it out ("Read" → "Read a.kt"). */
internal fun ToolCallDisplay.titleWithFile(): String {
    val name = filePath()?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')
    return if (name.isNullOrEmpty() || title.contains(name)) title else "$title $name"
}

/** The title without its file ("Read src/A.kt" → "Read"), for a row that shows the file as a [FileChip]. */
internal fun ToolCallDisplay.titleWithoutFile(): String {
    val name = filePath()?.fileName()?.takeIf { it.isNotEmpty() } ?: return title
    return title.replaceFirst(Regex("""\S*${Regex.escape(name)}\S*"""), "").trim()
}

internal fun JsonObject.firstString(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key -> (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content }

/** A failed call's change never happened, and one awaiting permission has not happened yet. */
internal fun ToolCallDisplay.landed(): Boolean = status != ToolCallStatus.FAILED && permissionOptions.isNullOrEmpty()

/**
 * What the tool call sheet's diff slot shows for an EDIT call with no exact diff of its own: none
 * reported, and none rebuilt from its input but a whole-file write, which cannot tell an overwrite
 * from a new file. It is the working tree's diffs for the files the call touched, requested on first
 * display and again whenever git's entry for a file changes. Empty until git answers, for a file git
 * no longer lists (committed or reverted), and wherever no tree is provided (see [turnWorkingTree]).
 */
@Composable
internal fun ToolCallDisplay.gitFallbackDiffs(): List<ToolCallContent.Diff> {
    val tree = localWorkingTreeChanges.current
    val fallback = localTurnGitFallback.current
    val wanted =
        remember(this) {
            kind == ToolKind.EDIT &&
                content.orEmpty().none { it is ToolCallContent.Diff } &&
                diffs().all { it.oldText == null }
        }
    // The status may predate this call, so it is re-read once when the sheet opens.
    LaunchedEffect(wanted) { if (wanted) fallback.onRefresh() }
    val entries =
        remember(this, tree, wanted) {
            if (wanted) {
                editedPaths().mapNotNull { tree.entryFor(it) }.distinctBy { it.path }
            } else {
                emptyList()
            }
        }
    LaunchedEffect(entries) {
        entries.forEach { fallback.onRequest(it.path, it.oldPath, it.revision()) }
    }
    return entries.flatMap { fallback.diffs[it.path].orEmpty() }
}
