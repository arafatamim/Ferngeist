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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.git.ui.FileChip
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.ArtifactKind
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.artifactKind
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.fileEvidence
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.fileLink
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.relativeFilePath
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
 * matches opens as written. An unlisted absolute path inside [cwd] opens relative to it. A name the
 * turn's files share stays text.
 */
internal fun findFileReferences(
    literal: String,
    paths: Set<String>,
    workspace: Map<String, List<String>>? = null,
    cwd: String = "",
): List<Pair<IntRange, String>> =
    pathToken
        .findAll(literal)
        .mapNotNull { match ->
            // A sentence-ending period is not part of the name.
            val end = match.range.first + match.value.trimEnd('.').length
            val start = match.range.first + if (match.value.startsWith("./")) 2 else 0
            val mention = literal.substring(start, end).replace('\\', '/')
            val evidence = fileEvidence(mention, paths, workspace)
            val file =
                when {
                    evidence == null -> null
                    evidence.isNotEmpty() -> evidence.joinToString(CANDIDATE_SEPARATOR)
                    else ->
                        mention.takeIf { relativeFilePath.matches(it) }
                            ?: mention.takeIf { it.startsWith('/') }?.let { fileLink(it, cwd)?.first }
                }
            file?.let { (start until end) to it }
        }.toList()

/** The files a chip id names: one to open, or the candidates a shared name offers. */
internal fun chipCandidates(id: String): List<String> = id.split(CANDIDATE_SEPARATOR)

private const val CANDIDATE_SEPARATOR = "\n"

/**
 * Opens the file a chip names, or a picker when its name is shared ([chipCandidates]); null where
 * there is no workspace to read it from.
 */
internal val localFileOpener = compositionLocalOf<((List<String>) -> Unit)?> { null }

/** The workspace's files by file name (`ChatState.workspaceFiles`); null until the gateway lists them. */
internal val localWorkspaceFiles = compositionLocalOf<Map<String, List<String>>?> { null }

/** The active project's directory on the gateway. */
internal val localWorkspaceRoot = compositionLocalOf { "" }

// Mirrors FileChip's chrome: 6dp padding each side, the 14dp icon and the 4dp gap; 2dp above and below.
private val chipChromeWidth = 30.dp
private val chipChromeHeight = 4.dp
private val chipIcon = 14.dp

/**
 * Source file mentions in a turn's prose as inline [FileChip]s that open the whole file
 * ([localFileOpener]), [paths] being the turn's files; none without an opener. None while the
 * turn streams: a file edited late would chip prose already on screen. Other files are
 * attachment cards only; HTML is both.
 */
@Composable
internal fun rememberFileChips(
    paths: Set<String>,
    isStreaming: Boolean,
): InlineChips? {
    val opener = localFileOpener.current
    val workspace = localWorkspaceFiles.current
    val cwd = localWorkspaceRoot.current
    val open by rememberUpdatedState(opener)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val chips =
        remember(paths, workspace, cwd, measurer, style, density) {
            // Built on first use: a written path can name a file no call touched.
            val cache = HashMap<Pair<String, Int>, InlineTextContent>()
            // The id leads with the text as written, which the chip shows: a written path stays a path.
            InlineChips(
                find = { literal ->
                    val sources = findFileReferences(literal, paths, workspace, cwd).filter { isSource(it.second) }
                    sources.map { (range, file) ->
                        val mention = literal.substring(range)
                        val label = mention.takeIf { it.startsWith('/') }?.let { fileLink(it, cwd)?.first } ?: mention
                        range to label + CANDIDATE_SEPARATOR + file
                    }
                },
                link = { destination ->
                    fileLink(destination, cwd)?.takeIf { isSource(it.first) }?.let { (path, suffix) ->
                        (path + CANDIDATE_SEPARATOR + path) to suffix
                    }
                },
                content = { id, maxWidth ->
                    cache.getOrPut(id to maxWidth) {
                        val label = id.substringBefore(CANDIDATE_SEPARATOR)
                        val candidates = chipCandidates(id.substringAfter(CANDIDATE_SEPARATOR))
                        val textWidth = (maxWidth - with(density) { chipChromeWidth.roundToPx() } - 1).coerceAtLeast(1)
                        chipContent(
                            candidates.first(),
                            label,
                            measurer.measure(label, style, constraints = Constraints(maxWidth = textWidth)).size,
                            density,
                        ) { open?.invoke(candidates) }
                    }
                },
            )
        }
    return chips.takeIf { opener != null && !isStreaming }
}

/**
 * A chip id's file opens as source: source text, and HTML, whose card previews the page while the
 * chip shows its markup. Its candidates share a name, so the first stands for all.
 */
private fun isSource(id: String): Boolean =
    artifactKind(chipCandidates(id).first()).let { it == ArtifactKind.SOURCE || it == ArtifactKind.HTML }

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
        FileChip(
            path,
            label = label,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.handCursor().clickable(onClick = onClick),
        )
    }
}
