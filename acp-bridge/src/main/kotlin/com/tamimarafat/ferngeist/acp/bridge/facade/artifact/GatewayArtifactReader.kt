package com.tamimarafat.ferngeist.acp.bridge.facade.artifact

import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactMetadata
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactReader
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import com.tamimarafat.ferngeist.core.model.hostPath
import com.tamimarafat.ferngeist.core.model.isAbsoluteHostPath
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import java.io.OutputStream

/** Reads host files through `/v1/files` against one fixed [target], whatever the chat connects to later. */
internal class GatewayArtifactReader(
    private val gatewayRepository: GatewayRepository,
    private val target: ProjectGitTarget,
) : ChatArtifactReader {
    override val identity: String = "${target.scheme}://${target.host}\n${target.cwd}"

    override suspend fun write(
        path: String,
        destination: OutputStream,
        ifNoneMatch: String?,
    ): ChatArtifactMetadata? = gatewayRepository.downloadFile(target, absolute(path), destination, ifNoneMatch)

    override suspend fun stat(paths: List<String>): List<ChatArtifactStat> =
        gatewayRepository
            .statFiles(target, paths.map(::absolute))
            .map { ChatArtifactStat(size = it.size, error = it.error) }

    private fun absolute(path: String): String {
        if (isAbsoluteHostPath(path)) return path
        // Joined onto a blank cwd, the relative path would read from the host's root.
        check(target.cwd.isNotBlank()) { "No project directory to resolve $path against" }
        return hostPath(target.cwd, path)
    }
}
