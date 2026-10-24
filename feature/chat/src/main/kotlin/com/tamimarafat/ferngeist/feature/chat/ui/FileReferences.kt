package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.feature.chat.markdown.InlineChips
import com.tamimarafat.ferngeist.feature.chat.ui.files.filePath

/** The files a turn's tool calls name: what each read, edit, delete or move works on, and every diff's file. */
internal fun List<AssistantSegment>.fileReferences(): Set<String> =
    mapNotNullTo(LinkedHashSet()) { it.toolCall?.filePath() } + fileChanges().map { it.path }

// Runs of path characters; ':' and '#' end a token, so "A.kt:42" chips the name and keeps the line.
private val pathToken = Regex("""[\w.~@+\-/\\]+""")

/**
 * Where [literal] names a file, and the chip id each opens ([chipCandidates]). The [workspace]'s
 * files, by name, come first: one match opens it, several open a picker. Then [paths] (the turn's
 * files), which cover a list the gateway cut short or doesn't have: a bare name matches when
 * exactly one has it. A path matches one it equals or ends on, at a path boundary, in either
 * direction (an absolute mention of a relative call path, or the reverse). A relative path nothing
 * matches opens as written. A name the turn's files share stays text.
 */
internal fun findFileReferences(
    literal: String,
    paths: Set<String>,
    workspace: Map<String, List<String>>? = null,
): List<Pair<IntRange, String>> =
    pathToken
        .findAll(literal)
        .mapNotNull { match ->
            // A sentence-ending period is not part of the name.
            val end = match.range.first + match.value.trimEnd('.').length
            val start = match.range.first + if (match.value.startsWith("./")) 2 else 0
            val mention = literal.substring(start, end).replace('\\', '/')
            val hits =
                paths.filter {
                    val path = it.replace('\\', '/')
                    if ('/' in mention) {
                        path == mention || path.endsWith("/$mention") || mention.endsWith("/$path")
                    } else {
                        path.fileName() == mention
                    }
                }
            val listed = workspace?.listed(mention).orEmpty()
            val file =
                when {
                    listed.isNotEmpty() -> listed.joinToString(CANDIDATE_SEPARATOR)
                    hits.isEmpty() -> mention.takeIf { relativeFilePath.matches(it) }
                    else -> hits.sameFile()
                }
            file?.let { (start until end) to it }
        }.toList()

/** The files a chip id names: one to open, or the candidates a shared name offers. */
internal fun chipCandidates(id: String): List<String> = id.split(CANDIDATE_SEPARATOR)

private const val CANDIDATE_SEPARATOR = "\n"

/**
 * The listed files [mention] names. A bare name needs a dot to look the list up, so prose words
 * stay text in a project with a `run` or `test` script.
 */
private fun Map<String, List<String>>.listed(mention: String): List<String> {
    if ('/' !in mention && '.' !in mention) return emptyList()
    return get(mention.fileName()).orEmpty().filter {
        '/' !in mention || it == mention || it.endsWith("/$mention") || mention.endsWith("/$it")
    }
}

/**
 * A relative path to a file with an extension, `dir/name.ext`: no leading `/`, `~` or `..`, and a
 * first segment without a dot unless it leads with one (`.github/`), so `example.com/a.html`,
 * `and/or` and `1/2` stay text.
 */
private val relativeFilePath = Regex("""(?:\.?[\w@+-]+)(?:/[\w.@+-]+)*/[\w.@+-]*\.[A-Za-z]\w*""")

/** The one file [this] names: calls can name it absolute and relative, so a path ending another's is the same file. */
private fun List<String>.sameFile(): String? {
    val longest = maxByOrNull { it.length } ?: return null
    val normalized = longest.replace('\\', '/')
    return if (all { it == longest || normalized.endsWith("/" + it.replace('\\', '/')) }) longest else null
}

/**
 * Opens the file a chip names, or a picker when its name is shared ([chipCandidates]); null where
 * there is no workspace to read it from.
 */
internal val localFileOpener = compositionLocalOf<((List<String>) -> Unit)?> { null }

/** The workspace's files by file name (`ChatState.workspaceFiles`); null until the gateway lists them. */
internal val localWorkspaceFiles = compositionLocalOf<Map<String, List<String>>?> { null }

// Mirrors FileChip's chrome: 6dp padding each side, the 14dp icon and the 4dp gap; 2dp above and below.
private val chipChromeWidth = 30.dp
private val chipChromeHeight = 4.dp
private val chipIcon = 14.dp

/**
 * File mentions in [segments]' prose as inline [FileChip]s that open the whole file
 * ([localFileOpener]); none without an opener. None while the turn streams: a file edited late
 * would chip prose already on screen.
 */
@Composable
internal fun rememberFileChips(
    segments: List<AssistantSegment>,
    isStreaming: Boolean,
): InlineChips? {
    val opener = localFileOpener.current
    val workspace = localWorkspaceFiles.current
    val paths = remember(segments) { segments.fileReferences() }
    val open by rememberUpdatedState(opener)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val chips =
        remember(paths, workspace, measurer, style, density) {
            // Built on first use: a written path can name a file no call touched.
            val cache = HashMap<String, InlineTextContent>()
            // The id leads with the text as written, which the chip shows: a written path stays a path.
            InlineChips(
                find = { literal ->
                    findFileReferences(literal, paths, workspace).map { (range, file) ->
                        range to literal.substring(range) + CANDIDATE_SEPARATOR + file
                    }
                },
            ) { id ->
                cache.getOrPut(id) {
                    val label = id.substringBefore(CANDIDATE_SEPARATOR)
                    val candidates = chipCandidates(id.substringAfter(CANDIDATE_SEPARATOR))
                    chipContent(
                        candidates.first(),
                        label,
                        measurer.measure(label, style, maxLines = 1).size,
                        density,
                    ) { open?.invoke(candidates) }
                }
            }
        }
    return chips.takeIf { opener != null && !isStreaming }
}

private fun chipContent(
    path: String,
    label: String,
    text: IntSize,
    density: Density,
    onClick: () -> Unit,
): InlineTextContent {
    val placeholder =
        with(density) {
            Placeholder(
                // One pixel of slack, so rounding never ellipsizes the name.
                width = (text.width + chipChromeWidth.roundToPx() + 1).toSp(),
                height = (maxOf(text.height, chipIcon.roundToPx()) + chipChromeHeight.roundToPx()).toSp(),
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
        }
    return InlineTextContent(placeholder) {
        FileChip(path, label = label, modifier = Modifier.handCursor().clickable(onClick = onClick))
    }
}
