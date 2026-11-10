package com.tamimarafat.ferngeist.core.model.git

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.GitWorkspaceReader

/** One checkout on one paired gateway, independent of any running ACP session. */
data class ProjectGitTarget(
    val scheme: String,
    val host: String,
    val gatewayCredential: String,
    val cwd: String,
)

fun GatewaySource.gitTarget(cwd: String): ProjectGitTarget = ProjectGitTarget(scheme, host, gatewayCredential, cwd)

/** Creates a reader bound to a checkout, without launching or attaching an agent. */
interface ProjectGitReaderFactory {
    fun create(target: ProjectGitTarget): GitWorkspaceReader
}
