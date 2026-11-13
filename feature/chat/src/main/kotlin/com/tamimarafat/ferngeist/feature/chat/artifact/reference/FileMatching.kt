package com.tamimarafat.ferngeist.feature.chat.artifact.reference

import com.tamimarafat.ferngeist.core.git.files.relativeTo
import com.tamimarafat.ferngeist.core.git.ui.fileName
import java.nio.file.Paths

/**
 * The files [mention] names by evidence: the [workspace]'s listed matches first, else the one turn
 * file of [paths] it names. Empty when neither knows it; null when the turn's files share the name.
 */
internal fun fileEvidence(
    mention: String,
    paths: Set<String>,
    workspace: Map<String, List<String>>?,
): List<String>? {
    workspace?.listed(mention)?.takeIf { it.isNotEmpty() }?.let { return it }
    val hits =
        paths.filter {
            val path = it.replace('\\', '/')
            if ('/' in mention) {
                path == mention || path.endsWith("/$mention") || mention.endsWith("/$path")
            } else {
                path.fileName() == mention
            }
        }
    return if (hits.isEmpty()) emptyList() else hits.sameFile()?.let(::listOf)
}

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
internal val relativeFilePath = Regex("""(?:\.?[\w@+-]+)(?:/[\w.@+-]+)*/[\w.@+-]*\.[A-Za-z]\w*""")

/** The one file [this] names: calls can name it absolute and relative, so a path ending another's is the same file. */
private fun List<String>.sameFile(): String? {
    val longest = maxByOrNull { it.length } ?: return null
    val normalized = longest.replace('\\', '/')
    return if (all { it == longest || normalized.endsWith("/" + it.replace('\\', '/')) }) longest else null
}

/**
 * [destination] as a path inside [cwd] and the `:line` suffix it carried, or null when it is a
 * URL, carries a query or fragment, or lies outside [cwd].
 */
internal fun fileLink(
    destination: String,
    cwd: String,
): Pair<String, String>? {
    if (cwd.isBlank() || destination.contains('?') || destination.contains('#')) return null
    val suffix = Regex(":\\d+$").find(destination)?.value.orEmpty()
    val path = destination.removeSuffix(suffix)
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]+:").containsMatchIn(path)) return null
    return path
        .relativeTo(cwd)
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching {
                Paths
                    .get(it)
                    .normalize()
                    .toString()
                    .replace('\\', '/')
            }.getOrNull()
        }?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?.takeUnless { it.startsWith("../") }
        ?.let { it to suffix }
}
