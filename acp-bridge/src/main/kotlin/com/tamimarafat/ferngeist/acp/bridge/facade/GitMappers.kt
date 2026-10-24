package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GitHistoryHeadGoneException
import com.tamimarafat.ferngeist.core.model.GitHistoryUnsupportedException
import com.tamimarafat.ferngeist.core.model.GitRefsUnsupportedException
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import com.tamimarafat.ferngeist.gateway.GatewayGitCommit
import com.tamimarafat.ferngeist.gateway.GatewayGitLog
import com.tamimarafat.ferngeist.gateway.GatewayGitRef
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import com.tamimarafat.ferngeist.gateway.gatewayErrorMessage

// ---- Gateway working tree ----

/** Gateway working-tree status in the chat-domain shape the feature layer holds. */
internal fun GatewayGitStatus.toChatGitStatus(): ChatGitStatus =
    ChatGitStatus(
        branch = branch,
        ahead = ahead,
        behind = behind,
        changed = changed.orEmpty().map { it.toChatChangedFile() },
        compare = compare,
    )

internal fun GatewayChangedFile.toChatChangedFile(): ChatChangedFile =
    ChatChangedFile(
        path = path,
        status = status,
        added = added,
        removed = removed,
        binary = binary,
        isDir = isDir,
        oldPath = oldPath,
        staged = staged,
    )

/** A history page; the gateway's `""` for an empty repository, no `origin` or no base becomes null. */
internal fun GatewayGitLog.toChatGitLogPage(): ChatGitLogPage =
    ChatGitLogPage(
        head = head.ifBlank { null },
        commits = commits.map { it.toChatGitCommit() },
        hasMore = hasMore,
        remoteUrl = remoteUrl.ifBlank { null },
        total = total,
        base = baseCommit.ifBlank { null },
    )

internal fun GatewayGitCommit.toChatGitCommit(): ChatGitCommit =
    ChatGitCommit(
        sha = sha,
        parents = parents,
        subject = subject,
        body = body,
        authorName = authorName,
        authorEmail = authorEmail,
        authorTimeMillis = authorTime * MILLIS_PER_SECOND,
        refs = refs.map { it.toChatGitRef() },
        isPushed = pushed,
        commitTimeMillis = (commitTime.takeIf { it > 0 } ?: authorTime) * MILLIS_PER_SECOND,
    )

internal fun GatewayWorktree.toChatWorktree(): ChatWorktree =
    ChatWorktree(branch = branch, ahead = ahead, behind = behind, dirty = dirty)

/** A gateway without the worktree API answers `/v1/worktrees` with a 404, which means "none" here. */
internal fun GatewayRequestException.isMissingWorktreeApi(): Boolean = statusCode == HTTP_NOT_FOUND

internal fun GatewayGitRefs.toChatGitRefs(): ChatGitRefs =
    ChatGitRefs(default = default, refs = refs.map { it.toChatGitRef() })

/** An unknown kind from a newer gateway reads as a branch rather than failing the page. */
internal fun GatewayGitRef.toChatGitRef(): ChatGitRef =
    ChatGitRef(
        name = name,
        kind =
            when (kind) {
                "head" -> ChatGitRef.Kind.HEAD
                "remote" -> ChatGitRef.Kind.REMOTE
                "tag" -> ChatGitRef.Kind.TAG
                else -> ChatGitRef.Kind.BRANCH
            },
        isHead = isHead,
    )

/**
 * What a failed `/git/log` means to the history view. An unknown route is Go's plain-text 404,
 * while the gateway's own 404s (no session yet) carry an `{"error"}` envelope; a 400 for a
 * pinned head means a rewrite collected it.
 */
internal fun gitLogFailure(
    error: GatewayRequestException,
    pinnedHead: Boolean,
): Throwable =
    when {
        error.statusCode == HTTP_NOT_FOUND && gatewayErrorMessage(error.responseBody) == null ->
            GitHistoryUnsupportedException()
        error.statusCode == HTTP_BAD_REQUEST && pinnedHead -> GitHistoryHeadGoneException(error)
        else -> error
    }

/** Same unknown-route test as [gitLogFailure]: a runtime-gone 404 carries an `{"error"}` envelope and stays ordinary. */
internal fun gitRefsFailure(error: GatewayRequestException): Throwable =
    if (error.statusCode == HTTP_NOT_FOUND && gatewayErrorMessage(error.responseBody) == null) {
        GitRefsUnsupportedException()
    } else {
        error
    }

private const val MILLIS_PER_SECOND = 1000L
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
