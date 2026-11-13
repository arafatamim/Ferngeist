package com.tamimarafat.ferngeist.feature.chat.artifact.reference

import com.adamglin.compose.markdown.core.model.InlineNode
import com.tamimarafat.ferngeist.core.model.hostPath
import com.tamimarafat.ferngeist.core.model.isAbsoluteHostPath
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/** How a paragraph wrote a file reference. */
internal enum class ReferenceSource { LINK, IMAGE, PLAIN }

/**
 * One file a paragraph references, [mention] as written. [candidates] are host paths, relative to
 * the chat's cwd when [inWorkspace], else absolute; several when a bare name is ambiguous.
 * Membership is lexical: a workspace symlink pointing elsewhere still counts as inside.
 */
internal data class ParagraphArtifact(
    val mention: String,
    val candidates: List<String>,
    val inWorkspace: Boolean,
    val source: ReferenceSource,
)

internal val ParagraphArtifact.label: String get() = candidates.first().substringAfterLast('/').substringAfterLast('\\')

internal val ParagraphArtifact.kind: ArtifactKind get() = artifactKind(candidates.first())

internal val ParagraphArtifact.presentation: ArtifactPresentation get() = presentationOf(kind, inWorkspace)

/**
 * The files [inlines] reference, once each in first-reference order: Markdown link and image
 * destinations, and plain paths in text and code spans. Bare names resolve through the
 * [workspaceFiles] listing and the turn's [turnPaths] before falling back to [cwd]; without a
 * [cwd] only absolute paths resolve.
 */
internal fun paragraphArtifacts(
    inlines: List<InlineNode>,
    cwd: String,
    turnPaths: Set<String>,
    workspaceFiles: Map<String, List<String>>?,
): List<ParagraphArtifact> {
    val resolver = MentionResolver(cwd, turnPaths, workspaceFiles)
    return inlines.mentions().mapNotNull(resolver::resolve).distinctBy { it.candidates }
}

private data class Mention(
    val text: String,
    val source: ReferenceSource,
)

/** Adjacent text nodes scan as one run: the parser splits a path at unmatched `_` or `*`. */
private fun List<InlineNode>.mentions(): List<Mention> {
    val found = mutableListOf<Mention>()
    val text = StringBuilder()
    forEach { node ->
        if (node is InlineNode.Text) {
            text.append(node.literal)
        } else {
            found += plainMentions(text.toString())
            text.clear()
            found += node.nodeMentions()
        }
    }
    return found + plainMentions(text.toString())
}

/** A link's label is its own prose, never another reference. */
private fun InlineNode.nodeMentions(): List<Mention> =
    when (this) {
        is InlineNode.Link -> listOf(Mention(destination, ReferenceSource.LINK))
        is InlineNode.Image -> listOf(Mention(destination, ReferenceSource.IMAGE))
        is InlineNode.Emphasis -> children.mentions()
        is InlineNode.Strong -> children.mentions()
        is InlineNode.Strikethrough -> children.mentions()
        is InlineNode.CodeSpan ->
            literal.trim().takeIf(::isWholePath)?.let { listOf(Mention(it, ReferenceSource.PLAIN)) }
                ?: plainMentions(literal)
        else -> emptyList()
    }

// A web address's path never reads as a host file.
private val webUrl = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://\S+|\bwww\.\S+""")

// A quoted run with a space, which the token scan would split.
private val quotedRun = Regex(""""([^"\n]*\s[^"\n]*)"|'([^'\n]*\s[^'\n]*)'""")

// Path runs, optionally after a Windows drive; ':' and '#' end one, so "A.kt:42" keeps its line apart.
private val plainToken = Regex("""(?<![\w:])(?:[A-Za-z]:[\\/])?[\w.~@+\-/\\]+""")

private fun plainMentions(literal: String): List<Mention> {
    if (literal.isBlank()) return emptyList()
    val taken = webUrl.findAll(literal).map { it.range }.toMutableList()
    val quoted =
        quotedRun
            .findAll(literal)
            .filter { match -> taken.none { match.range.first in it } }
            .mapNotNull { match ->
                match.groupValues
                    .drop(1)
                    .first { it.isNotEmpty() }
                    .takeIf(::isWholePath)
                    ?.let { match.range to it }
            }.toList()
    taken += quoted.map { it.first }
    val tokens =
        plainToken
            .findAll(literal)
            .filter { match -> taken.none { match.range.first in it } }
            .map { it.range to it.value }
    return (quoted + tokens).sortedBy { it.first.first }.map { Mention(it.second, ReferenceSource.PLAIN) }
}

/** A whole quoted or code-span literal that is one explicit path, spaces and all. */
private fun isWholePath(text: String): Boolean =
    (isAbsoluteHostPath(text) || text.startsWith("./") || text.startsWith("../")) &&
        text == text.trim() &&
        extensionOf(text).isNotEmpty()

private val urlScheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]+:""")
private val lineSuffix = Regex(""":\d+(?::\d+)?$""")
private val percentRun = Regex("(?:%[0-9A-Fa-f]{2})+")
private const val PERCENT_ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16

private class MentionResolver(
    private val cwd: String,
    private val turnPaths: Set<String>,
    private val workspace: Map<String, List<String>>?,
) {
    fun resolve(mention: Mention): ParagraphArtifact? {
        val paths =
            when (mention.source) {
                ReferenceSource.PLAIN -> plainPaths(mention.text)
                else -> listOfNotNull(destinationPath(mention.text))
            }
        // A relative path, or one escaping the cwd, has nowhere to resolve without the cwd.
        val located = paths.mapNotNull(::locate)
        if (located.isEmpty()) return null
        return ParagraphArtifact(mention.text, located, !isAbsoluteHostPath(located.first()), mention.source)
    }

    private fun locate(path: String): String? {
        fileLink(path, cwd)?.first?.let { return it }
        if (isAbsoluteHostPath(path)) return path
        if (!isAbsoluteHostPath(cwd)) return null
        return try {
            val absolute = Paths.get(hostPath(cwd, path).replace('\\', '/')).normalize().toString()
            if ('\\' in cwd && '/' !in cwd) absolute.replace('/', '\\') else absolute
        } catch (_: InvalidPathException) {
            // Agent-supplied destinations can contain decoded control bytes, which are not host paths.
            null
        }
    }

    /** A link or image destination names a file as written, extensionless or not; web schemes never do. */
    private fun destinationPath(destination: String): String? {
        val raw = destination.trim()
        val scheme =
            urlScheme
                .find(raw)
                ?.value
                ?.dropLast(1)
                ?.lowercase()
        val path =
            when (scheme) {
                null -> percentDecoded(raw.substringBefore('#')).replace(lineSuffix, "")
                "file" -> runCatching { URI(raw).path }.getOrNull()
                else -> null
            }
        return path?.takeIf { it.isNotBlank() && !it.endsWith('/') && !it.endsWith('\\') }
    }

    /**
     * A plain mention counts on workspace or turn evidence, or by its shape: an absolute path or
     * `dir/name.ext` with an extension, or a bare name with a known file extension.
     */
    private fun plainPaths(token: String): List<String> {
        val mention = token.trimEnd('.').removePrefix("./")
        if (mention.isEmpty() || mention.last() in "/\\" || mention.startsWith('~')) return emptyList()
        val slashed = mention.replace('\\', '/')
        val evidence = fileEvidence(slashed, turnPaths, workspace) ?: return emptyList()
        if (evidence.isNotEmpty()) return evidence
        val counts =
            when {
                isAbsoluteHostPath(mention) -> extensionOf(mention).firstOrNull()?.isLetter() == true
                slashed.startsWith("../") -> extensionOf(mention).firstOrNull()?.isLetter() == true
                '/' in slashed -> relativeFilePath.matches(slashed)
                else -> extensionOf(mention) in bareArtifactExtensions
            }
        return if (counts) listOf(mention) else emptyList()
    }
}

private fun percentDecoded(text: String): String =
    percentRun.replace(text) { run ->
        run.value
            .chunked(PERCENT_ESCAPE_LENGTH)
            .map { it.drop(1).toInt(HEX_RADIX).toByte() }
            .toByteArray()
            .decodeToString()
    }
