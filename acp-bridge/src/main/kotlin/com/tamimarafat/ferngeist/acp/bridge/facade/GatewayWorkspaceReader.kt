package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.workspace.GatewayDiffQuery
import com.tamimarafat.ferngeist.gateway.workspace.GatewayLogQuery

/** Rows per history page; the gateway clamps to 1..200. */
private const val GIT_LOG_PAGE_SIZE = 50

/**
 * Reads a chat's gateway workspace (git, worktree, files) through the gateway, scoped to the
 * chat's live session. Every read is null when the chat has no gateway workspace to read.
 */
internal class GatewayWorkspaceReader(
    private val gatewayRepository: GatewayRepository,
    private val workspaceConnection: () -> GatewayWorkspaceConnection?,
    private val liveSessionId: () -> String?,
    private val cwd: String,
) {
    /**
     * Git status of this chat's gateway working tree, or null when it has none: a manual
     * agent, a gateway chat whose runtime is not up yet, or a create-on-arrival chat that
     * has not minted its real session id (asking then would scope the read to whichever
     * session the gateway saw last).
     */
    suspend fun fetchGitStatus(compare: String?): ChatGitStatus? {
        val connection = workspaceConnection() ?: return null
        val acpSessionId = liveSessionId() ?: return null
        return gatewayRepository
            .fetchGitStatus(connection = connection, acpSessionId = acpSessionId, compare = compare)
            .toChatGitStatus()
    }

    suspend fun fetchWorktree(): ChatWorktree? {
        val connection = workspaceConnection() ?: return null
        if (cwd.isBlank()) return null
        return try {
            gatewayRepository
                .listWorktrees(connection.scheme, connection.host, connection.gatewayCredential)
                .firstOrNull { isSameCwd(it.path, cwd) }
                ?.toChatWorktree()
        } catch (error: GatewayRequestException) {
            if (error.isMissingWorktreeApi()) null else throw error
        }
    }

    /**
     * Unified diff for one file of this chat's gateway working tree, or null when the chat
     * has no gateway workspace. Transport failures propagate to the caller.
     */
    suspend fun fetchGitDiff(
        path: String,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ): List<ToolCallContent.Diff>? {
        val connection = workspaceConnection() ?: return null
        return gatewayRepository.fetchGitDiff(
            connection = connection,
            query =
                GatewayDiffQuery(
                    path = path,
                    acpSessionId = liveSessionId(),
                    commit = commit,
                    oldPath = oldPath,
                    compare = compare,
                ),
        )
    }

    suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage? {
        val connection = workspaceConnection() ?: return null
        val acpSessionId = liveSessionId() ?: return null
        return try {
            gatewayRepository
                .fetchGitLog(
                    connection = connection,
                    query =
                        GatewayLogQuery(
                            head = head,
                            skip = skip,
                            limit = GIT_LOG_PAGE_SIZE,
                            acpSessionId = acpSessionId,
                        ),
                ).toChatGitLogPage()
        } catch (error: GatewayRequestException) {
            throw gitLogFailure(error, pinnedHead = head != null)
        }
    }

    suspend fun fetchGitRefs(): ChatGitRefs? {
        val connection = workspaceConnection() ?: return null
        val acpSessionId = liveSessionId() ?: return null
        return try {
            gatewayRepository
                .fetchGitRefs(connection = connection, acpSessionId = acpSessionId)
                .toChatGitRefs()
        } catch (error: GatewayRequestException) {
            throw gitRefsFailure(error)
        }
    }

    suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>? {
        val connection = workspaceConnection() ?: return null
        return gatewayRepository
            .fetchGitCommit(connection = connection, sha = sha, acpSessionId = liveSessionId())
            .changed
            .map { it.toChatChangedFile() }
    }

    suspend fun fetchFile(path: String): ChatFileContents? {
        val connection = workspaceConnection() ?: return null
        val file =
            gatewayRepository.fetchFile(connection = connection, path = path, acpSessionId = liveSessionId())
        return ChatFileContents(text = file.text, truncated = file.truncated)
    }

    suspend fun fetchFileList(): List<String>? {
        val connection = workspaceConnection() ?: return null
        return gatewayRepository
            .fetchFileList(connection = connection, acpSessionId = liveSessionId())
            .files
    }
}
