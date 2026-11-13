package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GitWorkspaceReader
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import com.tamimarafat.ferngeist.core.model.hostPath
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.workspace.GatewayDiffQuery
import com.tamimarafat.ferngeist.gateway.workspace.GatewayLogQuery

/** Reads one checkout through the session-free project API. */
internal class GatewayProjectGitReader(
    private val gatewayRepository: GatewayRepository,
    private val target: ProjectGitTarget,
) : GitWorkspaceReader {
    /** Reads the selected checkout directly; no runtime or ACP session is needed. */
    override suspend fun fetchGitStatus(compare: String?): ChatGitStatus? =
        gatewayRepository
            .fetchGitStatus(connection = target, compare = compare)
            .toChatGitStatus()

    override suspend fun fetchWorktree(): ChatWorktree? {
        if (target.cwd.isBlank()) return null
        return try {
            gatewayRepository
                .listWorktrees(target.scheme, target.host, target.gatewayCredential)
                .firstOrNull { isSameCwd(it.path, target.cwd) }
                ?.toChatWorktree()
        } catch (error: GatewayRequestException) {
            if (error.isMissingWorktreeApi()) null else throw error
        }
    }

    /**
     * Unified diff for one file of this chat's gateway working tree, or null when the chat
     * has no gateway workspace. Transport failures propagate to the caller.
     */
    override suspend fun fetchGitDiff(
        path: String,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ): List<ToolCallContent.Diff>? =
        gatewayRepository.fetchGitDiff(
            connection = target,
            query =
                GatewayDiffQuery(
                    path = path,
                    commit = commit,
                    oldPath = oldPath,
                    compare = compare,
                ),
        )

    override suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage? =
        try {
            gatewayRepository
                .fetchGitLog(
                    connection = target,
                    query =
                        GatewayLogQuery(
                            head = head,
                            skip = skip,
                            limit = GIT_LOG_PAGE_SIZE,
                        ),
                ).toChatGitLogPage()
        } catch (error: GatewayRequestException) {
            throw gitLogFailure(error, pinnedHead = head != null)
        }

    override suspend fun fetchGitRefs(): ChatGitRefs? =
        try {
            gatewayRepository
                .fetchGitRefs(connection = target)
                .toChatGitRefs()
        } catch (error: GatewayRequestException) {
            throw gitRefsFailure(error)
        }

    override suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>? =
        gatewayRepository
            .fetchGitCommit(connection = target, sha = sha)
            .changed
            .map { it.toChatChangedFile() }

    override suspend fun fetchFile(path: String): ChatFileContents {
        // Joined onto a blank cwd, the relative path would read from the host's root.
        check(target.cwd.isNotBlank()) { "No project directory to read $path from" }
        return gatewayRepository.fetchFile(
            target.scheme,
            target.host,
            target.gatewayCredential,
            hostPath(target.cwd, path),
        )
    }
}

private const val GIT_LOG_PAGE_SIZE = 50
