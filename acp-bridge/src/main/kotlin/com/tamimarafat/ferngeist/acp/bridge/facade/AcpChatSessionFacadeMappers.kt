package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.sessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.supportsSteering
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GitHistoryHeadGoneException
import com.tamimarafat.ferngeist.core.model.GitHistoryUnsupportedException
import com.tamimarafat.ferngeist.core.model.GitRefsUnsupportedException
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import com.tamimarafat.ferngeist.gateway.GatewayGitCommit
import com.tamimarafat.ferngeist.gateway.GatewayGitLog
import com.tamimarafat.ferngeist.gateway.GatewayGitRef
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import com.tamimarafat.ferngeist.gateway.gatewayErrorMessage
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketTimeoutException

/** Upper bound on a user-facing send-error string, so error payloads never flood the UI. */
private const val MAX_SEND_ERROR_CHARS = 240

// ---- Mapping helpers ----

internal fun mapConnectionState(acp: AcpConnectionState): ChatConnectionState =
    when (acp) {
        AcpConnectionState.Disconnected -> ChatConnectionState.Disconnected
        AcpConnectionState.Connecting -> ChatConnectionState.Connecting
        AcpConnectionState.Connected -> ChatConnectionState.Connected
        is AcpConnectionState.Failed ->
            ChatConnectionState.Failed(acp.error.message)
    }

internal fun mapDiagnostics(diag: ConnectionDiagnostics): ChatConnectionDiagnostics =
    ChatConnectionDiagnostics(
        serverUrl = diag.serverUrl,
        pendingRequestCount = diag.pendingRequestCount,
        reconnectAttempts = diag.reconnectAttempts,
        recentErrors = diag.recentErrors.map { it.message },
        lastUpdatedAtMs = diag.lastUpdatedAtMs,
    )

internal fun mapCapabilities(caps: AgentCapabilities): ChatAgentCapabilities =
    ChatAgentCapabilities(
        canSendImages = caps.promptCapabilities.image,
        supportsEmbeddedContext = caps.promptCapabilities.embeddedContext,
        // Reuse the attach decision rather than re-reading the capability flags:
        // resume-only agents (and those that can attach no session at all) never
        // replay history into this client.
        supportsHistoryReplay = caps.sessionAttachRpc() == SessionAttachRpc.Load,
        supportsSteering = caps.supportsSteering(),
    )

internal fun mapSnapshot(snapshot: SessionSnapshot): ChatSessionSnapshot =
    ChatSessionSnapshot(
        loadState = mapLoadState(snapshot.loadState),
        messages = snapshot.messages,
        isStreaming = snapshot.isStreaming,
        configOptions = snapshot.configOptions.map { mapConfigOption(it) },
        availableCommands = snapshot.availableCommands.map { ChatCommand(it.name, it.description) },
        commandsAdvertised = snapshot.commandsAdvertised,
        error = snapshot.error,
        title = snapshot.title,
        pendingElicitations = snapshot.pendingElicitations,
        usage =
            snapshot.usage?.let {
                UsageState(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                    cachedReadTokens = it.cachedReadTokens,
                    contextWindowTokens = it.contextWindowTokens,
                    costAmount = it.costAmount,
                    costCurrency = it.costCurrency,
                )
            },
    )

internal fun mapLoadState(acp: SessionLoadState): ChatLoadState =
    when (acp) {
        SessionLoadState.IDLE ->
            // Treat IDLE as hydrating so the UI shows a loading state until a snapshot arrives.
            ChatLoadState.HYDRATING
        SessionLoadState.HYDRATING ->
            ChatLoadState.HYDRATING
        SessionLoadState.READY ->
            ChatLoadState.READY
        SessionLoadState.FAILED ->
            ChatLoadState.FAILED
    }

internal fun mapConfigOption(option: SessionConfigOption): ChatConfigOption {
    val category = option.category?.let { mapConfigCategory(it) }
    return when (option) {
        is SessionConfigOption.Select ->
            ChatConfigOption.Select(
                id = option.id,
                name = option.name,
                description = option.description,
                category = category,
                currentValue = option.currentValue,
                choices = option.choices.map { mapChoice(it) },
                groups =
                    option.groups.map { group ->
                        ChatConfigChoiceGroup(
                            id = group.id,
                            label = group.label,
                            choices = group.choices.map { mapChoice(it) },
                        )
                    },
            )
        is SessionConfigOption.BooleanOption ->
            ChatConfigOption.BooleanOption(
                id = option.id,
                name = option.name,
                description = option.description,
                category = category,
                currentValue = option.currentValue,
            )
        is SessionConfigOption.Unknown ->
            ChatConfigOption.Unknown(
                id = option.id,
                name = option.name,
                description = option.description,
                category = category,
                kind = option.kind,
                currentValue = option.currentValue?.let { mapConfigValue(it) },
            )
    }
}

internal fun mapConfigCategory(acp: SessionConfigCategory): ChatConfigCategory =
    when (acp) {
        SessionConfigCategory.Mode -> ChatConfigCategory.Mode
        SessionConfigCategory.Model -> ChatConfigCategory.Model
        is SessionConfigCategory.Custom -> ChatConfigCategory.Custom(acp.rawValue)
    }

internal fun mapChoice(acp: SessionConfigChoice): ChatConfigChoice =
    ChatConfigChoice(
        id = acp.id,
        label = acp.label,
        value = acp.value,
        description = acp.description,
    )

internal fun mapConfigValue(acp: SessionConfigValue): ChatConfigValue =
    when (acp) {
        is SessionConfigValue.StringValue -> ChatConfigValue.StringValue(acp.value)
        is SessionConfigValue.BoolValue -> ChatConfigValue.BoolValue(acp.value)
        is SessionConfigValue.UnknownValue -> ChatConfigValue.UnknownValue(acp.debugValue)
    }

internal fun toAcpConfigValue(chat: ChatConfigValue): SessionConfigValue =
    when (chat) {
        is ChatConfigValue.StringValue -> SessionConfigValue.StringValue(chat.value)
        is ChatConfigValue.BoolValue -> SessionConfigValue.BoolValue(chat.value)
        is ChatConfigValue.UnknownValue -> SessionConfigValue.UnknownValue(chat.debugValue)
    }

// ---- Error classification helpers ----

/**
 * Maps a send error to a concise, bounded user-facing message.
 *
 * Classification reads the JSON-RPC code, never the wording. "Invalid params" is the
 * protocol's generic label and the agent's own detail rides in `message`/`data`
 * ("cwd does not exist"), so matching the text also replaced that detail with a locally
 * authored sentence that told the user nothing.
 */
internal fun userFacingSendError(error: Throwable): String {
    val message =
        when {
            error.isRequestTimeout() -> "Request timed out. Please try again."
            error.isInvalidParams() -> "Send failed due to an invalid request format."
            else -> formatAcpErrorMessage(error, "Send failed due to an unknown error.")
        }
    return if (message.length > MAX_SEND_ERROR_CHARS) {
        message.take(MAX_SEND_ERROR_CHARS).trimEnd() + "…"
    } else {
        message
    }
}

/**
 * True for a client-side request deadline. Our own bounds raise a
 * [TimeoutCancellationException]; the SDK's request deadline surfaces as a plain error,
 * so its own prefix is the only signal it offers. This matches the client library's
 * wording, never the agent's payload.
 */
private fun Throwable.isRequestTimeout(): Boolean =
    generateSequence(this as Throwable?) { it.cause }.any { cause ->
        cause is TimeoutCancellationException ||
            cause is SocketTimeoutException ||
            cause.message.orEmpty().startsWith("Request timeout", ignoreCase = true)
    }

/** True when the agent rejected the request's parameters, per the JSON-RPC code. */
private fun Throwable.isInvalidParams(): Boolean =
    (this as? JsonRpcException)?.code == JsonRpcErrorCode.INVALID_PARAMS.code

/**
 * Returns true when the server does not implement `session/cancel`.
 *
 * Only the code is read. The method identity is not in dispute — the call site that
 * produced this error is the one cancelling — so requiring "Method not found" *and*
 * "session/cancel" in the message added no evidence and one false negative: an agent
 * answering a bare `-32601 Method not found`, which is legal JSON-RPC, left the flag
 * unset and the user with a Cancel button that could never work.
 */
internal fun isSessionCancelUnsupported(error: Throwable): Boolean =
    (error as? JsonRpcException)?.code == JsonRpcErrorCode.METHOD_NOT_FOUND.code

// ---- Gateway working tree ----

/** Gateway working-tree status in the chat-domain shape the feature layer holds. */
internal fun GatewayGitStatus.toChatGitStatus(): ChatGitStatus =
    ChatGitStatus(
        branch = branch,
        ahead = ahead,
        behind = behind,
        changed = changed.map { it.toChatChangedFile() },
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
    ChatWorktree(branch = branch, ahead = ahead, dirty = dirty)

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
