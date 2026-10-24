package com.tamimarafat.ferngeist.feature.chat.ui.history

import java.net.URI

private val scpRemote = Regex("^[\\w.-]+@([\\w.-]+):(?!/)(.+)$")
private val shaPattern = Regex("^[0-9a-f]{4,64}$")

/**
 * The forge's web page for [sha], from a remote URL in any form git accepts: `https://`,
 * `ssh://`, or scp-like `git@host:owner/repo.git`. Credentials and ports are dropped, so a
 * token embedded in the remote never ends up in a browser. Null for local or unknown remotes.
 * The GitHub app claims github.com links, so Android opens it there when installed.
 */
internal fun commitWebUrl(
    remoteUrl: String,
    sha: String,
): String? {
    val (scheme, host, path) = parseRemote(remoteUrl.trim()) ?: return null
    val repo = path.trim('/').removeSuffix(".git").trim('/')
    // owner/repo at minimum; GitLab subgroups add more segments and keep the same /commit/ path.
    val valid = shaPattern.matches(sha) && '/' in repo && repo.split('/').none { it.isBlank() || it == ".." }
    return if (valid) "$scheme://${host.lowercase()}/$repo/commit/$sha" else null
}

/** Web scheme, host and repo path of a remote; userinfo and port are left behind. */
private fun parseRemote(remote: String): Triple<String, String, String>? {
    scpRemote.find(remote)?.destructured?.let { (host, path) -> return Triple("https", host, path) }
    val uri = runCatching { URI(remote) }.getOrNull()
    val scheme =
        when (uri?.scheme?.lowercase()) {
            "https", "ssh", "git" -> "https"
            "http" -> "http"
            else -> null
        }
    val host = uri?.host
    return if (scheme != null && host != null) Triple(scheme, host, uri.path.orEmpty()) else null
}
