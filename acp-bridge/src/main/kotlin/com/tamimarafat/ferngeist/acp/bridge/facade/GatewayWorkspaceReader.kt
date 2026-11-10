package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import com.tamimarafat.ferngeist.gateway.GatewayRepository

/**
 * Reads a chat's gateway workspace (git, worktree, files) through the gateway, scoped to the
 * checkout for Git, or its live session for ordinary files. Reads are null without a gateway workspace.
 */
internal class GatewayWorkspaceReader(
    private val gatewayRepository: GatewayRepository,
    private val workspaceConnection: () -> GatewayWorkspaceConnection?,
    private val liveSessionId: () -> String?,
    private val cwd: String,
) {
    private fun gitReader(): GatewayProjectGitReader? =
        workspaceConnection()?.let { connection ->
            GatewayProjectGitReader(
                gatewayRepository,
                ProjectGitTarget(
                    connection.scheme,
                    connection.host,
                    connection.gatewayCredential,
                    cwd,
                ),
            )
        }

    suspend fun fetchGitStatus(compare: String?): ChatGitStatus? = gitReader()?.fetchGitStatus(compare)

    suspend fun fetchWorktree(): ChatWorktree? = gitReader()?.fetchWorktree()

    suspend fun fetchGitDiff(
        path: String,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ): List<ToolCallContent.Diff>? = gitReader()?.fetchGitDiff(path, commit, oldPath, compare)

    suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage? = gitReader()?.fetchGitLog(head, skip)

    suspend fun fetchGitRefs(): ChatGitRefs? = gitReader()?.fetchGitRefs()

    suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>? = gitReader()?.fetchGitCommitFiles(sha)

    suspend fun fetchFile(path: String): ChatFileContents? {
        val connection = workspaceConnection() ?: return null
        val file =
            gatewayRepository.fetchFile(connection = connection, path = path, acpSessionId = liveSessionId())
        return ChatFileContents(text = file.text, truncated = file.truncated, blob = file.blob)
    }

    suspend fun fetchFileList(): List<String>? {
        val connection = workspaceConnection() ?: return null
        return gatewayRepository
            .fetchFileList(connection = connection, acpSessionId = liveSessionId())
            .files
    }
}
