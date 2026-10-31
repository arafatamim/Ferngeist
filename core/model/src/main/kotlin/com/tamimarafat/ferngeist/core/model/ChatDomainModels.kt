package com.tamimarafat.ferngeist.core.model

import com.agentclientprotocol.model.ToolCallContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nav-arg sentinel for the chat route: the chat screen must CREATE a fresh
 * ACP session on its own connection instead of loading an existing one.
 */
const val NEW_SESSION_ARG = "__new__"

/**
 * Chat-domain equivalent of ACP connection state.
 */
sealed interface ChatConnectionState {
    data object Disconnected : ChatConnectionState

    data object Connecting : ChatConnectionState

    data object Connected : ChatConnectionState

    data class Failed(
        val errorMessage: String?,
    ) : ChatConnectionState
}

enum class ChatLoadState {
    HYDRATING,
    READY,
    FAILED,
}

sealed interface ChatConfigCategory {
    val rawValue: String

    data object Mode : ChatConfigCategory {
        override val rawValue: String = "mode"
    }

    data object Model : ChatConfigCategory {
        override val rawValue: String = "model"
    }

    data class Custom(
        override val rawValue: String,
    ) : ChatConfigCategory
}

sealed interface ChatConfigValue {
    data class StringValue(
        val value: String,
    ) : ChatConfigValue

    data class BoolValue(
        val value: Boolean,
    ) : ChatConfigValue

    data class UnknownValue(
        val debugValue: String? = null,
    ) : ChatConfigValue
}

data class ChatConfigChoice(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
)

data class ChatConfigChoiceGroup(
    val id: String,
    val label: String? = null,
    val choices: List<ChatConfigChoice>,
)

sealed interface ChatConfigOption {
    val id: String
    val name: String
    val description: String?
    val category: ChatConfigCategory?

    data class Select(
        override val id: String,
        override val name: String,
        override val description: String? = null,
        override val category: ChatConfigCategory? = null,
        val currentValue: String? = null,
        val choices: List<ChatConfigChoice> = emptyList(),
        val groups: List<ChatConfigChoiceGroup> = emptyList(),
    ) : ChatConfigOption

    data class BooleanOption(
        override val id: String,
        override val name: String,
        override val description: String? = null,
        override val category: ChatConfigCategory? = null,
        val currentValue: Boolean = false,
    ) : ChatConfigOption

    data class Unknown(
        override val id: String,
        override val name: String,
        override val description: String? = null,
        override val category: ChatConfigCategory? = null,
        val kind: String? = null,
        val currentValue: ChatConfigValue? = null,
    ) : ChatConfigOption
}

fun ChatConfigOption.Select.allChoices(): List<ChatConfigChoice> =
    if (groups.isEmpty()) {
        choices
    } else {
        groups.flatMap { it.choices }
    }

fun ChatConfigOption.Select.selectedChoice(): ChatConfigChoice? {
    val value = currentValue ?: return null
    return allChoices().firstOrNull { it.value == value }
}

fun ChatConfigOption.displayValueLabel(): String? =
    when (this) {
        is ChatConfigOption.Select -> selectedChoice()?.label ?: currentValue
        is ChatConfigOption.BooleanOption -> currentValue.toString()
        is ChatConfigOption.Unknown ->
            when (val value = currentValue) {
                is ChatConfigValue.StringValue -> value.value
                is ChatConfigValue.BoolValue -> value.value.toString()
                is ChatConfigValue.UnknownValue -> value.debugValue
                null -> null
            }
    }

data class ChatCommand(
    val name: String,
    val description: String? = null,
)

data class ChatConnectionDiagnostics(
    val serverUrl: String? = null,
    val pendingRequestCount: Int = 0,
    val reconnectAttempts: Int = 0,
    val recentErrors: List<String> = emptyList(),
    val lastUpdatedAtMs: Long = 0L,
)

data class ChatAgentCapabilities(
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    /**
     * True when reopening an existing session replays its earlier messages to this
     * client (`session/load`). False for a resume-only agent, whose reattached
     * sessions start with an empty transcript here.
     *
     * Unobserved capabilities count as replay-capable, matching the attach-RPC
     * decision that assumes `session/load` until the agent says otherwise.
     */
    val supportsHistoryReplay: Boolean = true,
    /** True when a prompt can be steered into a running turn instead of waiting for it. */
    val supportsSteering: Boolean = false,
)

/** What the agent did with a steered prompt. */
enum class SteerOutcome {
    /** Folded into the running turn. */
    INJECTED,

    /** No turn was running, so the agent started one of its own with the prompt. */
    STARTED_NEW_TURN,

    /** Not taken (idle, rejected, unsupported or failed); the prompt must be sent normally. */
    NOT_CONSUMED,
}

/**
 * The connection details a chat needs to reach its agent's workspace through the
 * gateway REST API (file reads, git status/diff). Null for non-gateway (manual)
 * chats, which have no gateway workspace.
 */
data class GatewayWorkspaceConnection(
    val runtimeId: String,
    val scheme: String,
    val host: String,
    val gatewayCredential: String,
)

/**
 * One changed entry of a gateway working tree, in git's own terms.
 *
 * [status] is the porcelain status code (`M`, `??`, ...); [added]/[removed] are the
 * line counts the gateway read from `git diff --numstat`.
 */
data class ChatChangedFile(
    val path: String,
    val status: String,
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false,
    /**
     * True when the entry is a directory rather than a file: a submodule (gitlink) or a
     * collapsed untracked directory. Directory entries are not diffable — tapping one
     * must not ask for a diff. The gateway computes this from `git status
     * --porcelain=v2`, since the client cannot tell a submodule from a file by path.
     */
    val isDir: Boolean = false,
    /** The pre-rename path, for renames and copies in a commit's file list. */
    val oldPath: String? = null,
    /** True when the change is staged in the index. */
    val staged: Boolean = false,
)

/** Branch, ahead/behind counts, and changed entries of a gateway working tree. */
data class ChatGitStatus(
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val changed: List<ChatChangedFile> = emptyList(),
    /** The ref the gateway compared against, echoed verbatim; null unless an explicit compare was honoured. */
    val compare: String? = null,
)

/**
 * The gateway's record of the worktree a chat runs in. [ahead], [behind] and [dirty] are null when
 * git could not say.
 */
data class ChatWorktree(
    val branch: String,
    val ahead: Int? = null,
    val behind: Int? = null,
    val dirty: Boolean? = null,
)

/**
 * One gateway worktree as a listing names it: the repo it belongs to, its checkout, and what git
 * knows about it. The repo is authoritative — a worktree folder is named for its branch, not its
 * project.
 */
data class WorktreeRef(
    val repo: String,
    val path: String,
    val branch: String,
    val ahead: Int? = null,
    val behind: Int? = null,
    val dirty: Boolean? = null,
)

/** A branch, remote branch, tag, or detached HEAD pointing at a commit. */
data class ChatGitRef(
    val name: String,
    val kind: Kind,
    /** True for the branch HEAD points at. */
    val isHead: Boolean = false,
) {
    enum class Kind { HEAD, BRANCH, REMOTE, TAG }
}

/**
 * The fully-qualified ref sent as `compare=`: a tag and a branch can share a short name, and
 * git resolves the short one tag-first. Null for a detached HEAD, which is not a target.
 */
val ChatGitRef.qualified: String?
    get() =
        when (kind) {
            ChatGitRef.Kind.BRANCH -> "refs/heads/$name"
            ChatGitRef.Kind.REMOTE -> "refs/remotes/$name"
            ChatGitRef.Kind.TAG -> "refs/tags/$name"
            ChatGitRef.Kind.HEAD -> null
        }

/** The compare picker's data; [default] is the repo's main branch name, or "" when unknown. */
data class ChatGitRefs(
    val default: String,
    val refs: List<ChatGitRef>,
)

/** One commit of a gateway working tree's history, as `git log` reports it. */
data class ChatGitCommit(
    val sha: String,
    val parents: List<String>,
    val subject: String,
    val body: String = "",
    val authorName: String,
    val authorEmail: String,
    val authorTimeMillis: Long,
    val refs: List<ChatGitRef> = emptyList(),
    /** Reachable from a branch on `origin`, so a link to it on origin's forge resolves. */
    val isPushed: Boolean = false,
    /** When it landed on the branch; later than [authorTimeMillis] after a rebase, amend or cherry-pick. */
    val commitTimeMillis: Long = authorTimeMillis,
)

/**
 * One page of history. [head] is the sha the page was read from, sent back for later pages;
 * null for an empty repository. [remoteUrl] is `origin`'s URL with credentials removed.
 */
data class ChatGitLogPage(
    val head: String?,
    val commits: List<ChatGitCommit>,
    val hasMore: Boolean,
    val remoteUrl: String?,
    /**
     * The size of the whole walk this page comes from, so the list can be sized before it is paged
     * to the end. Null when the gateway predates the field.
     */
    val total: Int? = null,
    /**
     * The commit the gateway reads this workspace against: a managed worktree's base, so the
     * history above it is this branch's own work even after it is merged and `HEAD` stops naming
     * it. Null when the workspace has no base to name, and the list then draws unmarked.
     */
    val base: String? = null,
)

/** The gateway predates commit history, so the history view has nothing to read. */
class GitHistoryUnsupportedException : IllegalStateException("This gateway does not support commit history")

/** The gateway predates branch comparison, so the sheet hides the compare affordance. */
class GitRefsUnsupportedException : IllegalStateException("This gateway does not support branch comparison")

/** The pinned history head no longer names a commit (rewritten and collected); reload from page 1. */
class GitHistoryHeadGoneException(
    cause: Throwable,
) : IllegalStateException("History changed on the gateway", cause)

data class ChatSessionSnapshot(
    val loadState: ChatLoadState,
    val messages: List<ChatMessage>,
    val isStreaming: Boolean,
    val configOptions: List<ChatConfigOption>,
    val availableCommands: List<ChatCommand>,
    val commandsAdvertised: Boolean,
    val error: String?,
    val usage: UsageState?,
    val title: String? = null,
    val pendingElicitations: List<ChatElicitationRequest> = emptyList(),
)

data class UsageState(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedReadTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
)

data class ChatOperationError(
    val message: String,
    val stopStreaming: Boolean,
)

/**
 * Facade that exposes chat session operations using only chat-domain types.
 *
 * Implementations bridge transport-specific logic (ACP, etc.) behind this interface
 * so the feature layer never imports protocol-specific types.
 */
interface ChatSessionFacade :
    ChatSessionState,
    ChatSessionEvents,
    ChatSessionLifecycle,
    ChatSessionInteraction,
    ChatWorkspaceReader

/** Observable session snapshots, independent of one-shot events. */
interface ChatSessionState {
    /** Current transport-level connection state. */
    val connectionState: StateFlow<ChatConnectionState>

    /** Diagnostic telemetry from the transport layer (server URL, error history, etc.). */
    val diagnostics: StateFlow<ChatConnectionDiagnostics>

    /** Latest session snapshot — null while no bridge is attached. */
    val sessionSnapshot: StateFlow<ChatSessionSnapshot?>

    /** Advertised agent capabilities (image prompt, embedded context, etc.). */
    val agentCapabilities: StateFlow<ChatAgentCapabilities>

    /** Gateway workspace connection details (runtime id, host, credential), or null
     * when the chat targets a direct (non-gateway) server. */
    val gatewayWorkspaceConnection: StateFlow<GatewayWorkspaceConnection?>

    /** Hub chat id (`"$serverId/$sessionId"`) once a live session is attached; null before. */
    val liveChatId: StateFlow<String?>

    /**
     * Last rendered snapshot, retained after [clear] so a reopening screen can
     * paint instantly without waiting for reconnect + session/load replay.
     * Null when nothing has rendered yet. Never blocks on transport.
     */
    val cachedSnapshot: StateFlow<ChatSessionSnapshot?>
}

/** One-shot events observed by the chat screen. */
interface ChatSessionEvents {
    // One-shot event streams (not state snapshots):
    val loadFailed: SharedFlow<String>
    val operationError: SharedFlow<ChatOperationError>
    val streamingCancelled: SharedFlow<Unit>
    val cancelUnsupported: SharedFlow<Unit>
    val sessionReady: SharedFlow<Unit>
    val modelUpdated: SharedFlow<Unit>
}

/** Loading and connection ownership for one chat session. */
interface ChatSessionLifecycle {
    /**
     * Attempts to reattach a warm in-memory session without wiping UI state.
     *
     * Returns true when an already-connected manager holds a hydrated
     * bridge for this session; the facade attaches its observers and the
     * caller should skip `onLoadStarted` (which clears transcript/markdown).
     * Returns false when no warm cache exists — caller must do a full
     * `loadSession` path.
     */
    suspend fun tryRestoreWarmSession(): Boolean = false

    /**
     * Asks the agent for this session's canonical title via `session/list`, or
     * null when there is nothing to apply.
     *
     * The fallback for agents that generate a session title server-side but
     * never push it over `session_info_update`: the chat asks once the first
     * response finishes and it still has no title. Read-only — it never writes
     * to a local chat record; other screens use the next authoritative listing.
     */
    suspend fun fetchSessionTitle(): String? = null

    /** Loads an existing session or creates a new one. Emits [loadFailed] on error. */
    suspend fun loadSession()

    /**
     * Same as [loadSession] but skips the transcript wipe: the caller already
     * painted [cachedSnapshot], so the reload only revalidates in the
     * background. UI state must be preserved.
     */
    suspend fun loadSessionQuietly() {
        loadSession()
    }

    /** Tears down the active bridge and clears observers. */
    fun clear()

    /** Informs the facade of connection state transitions so it can schedule bridge recovery. */
    fun onConnectionStateChanged(connectionState: ChatConnectionState)

    /**
     * Ensures the transport is (re)connecting. Called when a prompt is queued while
     * offline so the queued message has a path forward instead of waiting for an
     * externally-driven reconnect. Idempotent and safe to call repeatedly.
     */
    suspend fun reconnect()
}

/** User actions sent to an attached session. */
interface ChatSessionInteraction {
    /** Sends a user message with optional inline images. Returns true if the message
     * was dispatched to a live session; false when no bridge is available or the
     * payload is unsupported by the current transport. */
    suspend fun sendMessage(
        text: String,
        images: List<ChatImageData> = emptyList(),
        files: List<ChatFileData> = emptyList(),
    ): Boolean

    /** Requests a streaming cancel from the transport. */
    suspend fun cancelStreaming()

    /** Steers a prompt into the running turn; see [ChatAgentCapabilities.supportsSteering]. */
    suspend fun steerMessage(
        text: String,
        images: List<ChatImageData> = emptyList(),
        files: List<ChatFileData> = emptyList(),
    ): SteerOutcome = SteerOutcome.NOT_CONSUMED

    /** Updates a session configuration option (mode, model, native config, etc.). */
    suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    )

    /** Grants a permission prompt identified by [toolCallId] with the selected [optionId]. */
    suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    )

    /** Denies a pending permission prompt. */
    suspend fun denyPermission(toolCallId: String)

    /** Submits a pending elicitation form with the user's answers. */
    suspend fun submitElicitation(
        key: String,
        values: Map<String, ChatElicitationValue>,
    )

    /** Declines a pending elicitation: the user explicitly refused the request. */
    suspend fun declineElicitation(key: String)

    /** Cancels a pending elicitation: dismissed without choosing. */
    suspend fun cancelElicitation(key: String)
}

/** Read-only access to a session's gateway workspace. */
interface ChatWorkspaceReader {
    /**
     * Git status of this chat's gateway working tree, or null when it has none: a manual
     * agent, a gateway chat whose runtime is not up yet, or a create-on-arrival chat that
     * has not minted its real session id (the gateway would scope the read to whichever
     * session it saw last).
     */
    suspend fun fetchGitStatus(compare: String? = null): ChatGitStatus?

    /**
     * The gateway worktree this chat's directory is, or null when it is not one: a manual agent, a
     * chat outside any worktree, or a gateway that predates worktrees. Other failures propagate.
     */
    suspend fun fetchWorktree(): ChatWorktree?

    /**
     * Diff of one file in the chat's gateway working tree, or in [commit] against its first
     * parent, or null when it has no gateway workspace. Transport failures propagate.
     */
    suspend fun fetchGitDiff(
        path: String,
        commit: String? = null,
        oldPath: String? = null,
        compare: String? = null,
    ): List<ToolCallContent.Diff>?

    /**
     * One page of history from [head] (`HEAD` when null), or null when the chat has no gateway
     * workspace. Throws [GitHistoryUnsupportedException] on a gateway without history and
     * [GitHistoryHeadGoneException] when [head] no longer resolves. Always the whole walk from
     * `HEAD`, which [ChatGitLogPage.base] marks this branch's own work within.
     */
    suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage?

    /**
     * Branches, remote branches and tags to compare against, or null when the chat has no gateway
     * workspace. Throws [GitRefsUnsupportedException] on a gateway without branch comparison.
     */
    suspend fun fetchGitRefs(): ChatGitRefs?

    /** Files [sha] changed against its first parent, or null when the chat has no gateway workspace. */
    suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>?

    /**
     * One file of the chat's gateway workspace, [path] relative to its project directory, or null
     * when it has none. Transport failures propagate.
     */
    suspend fun fetchFile(path: String): ChatFileContents?

    /**
     * The workspace's files, relative to its project directory, or null when it has none. Possibly
     * cut short on a huge repo. Throws on a gateway without the list, and on transport failures.
     */
    suspend fun fetchFileList(): List<String>?
}

/** A workspace file as the gateway read it: [text] is null for a binary file, cut at 1 MiB when [truncated]. */
data class ChatFileContents(
    val text: String?,
    val truncated: Boolean,
)

/**
 * Session-presence and session-lifecycle operations a chat screen needs from the
 * process-wide connection hub: which servers/sessions are live (the chat switcher and
 * notification deep links), this screen's own presence, and closing another session's
 * gateway lease.
 *
 * A separate port from [ChatSessionFacade] because it is hub-wide rather than scoped to
 * one session. Implemented by a hub-backed adapter in the ACP bridge, so the feature
 * layer never sees the hub or gateway types.
 */
interface ChatSessionPresence {
    /** Servers that hold at least one live chat connection. */
    val warmServers: StateFlow<Set<String>>

    /** Live local or last fetched gateway turn status, keyed by target/session identity. */
    val turnStatuses: StateFlow<Map<String, TurnActivity>>

    /** Connected session ids tracked on [serverId], re-emitting as connections come and go. */
    fun connectedSessionIds(serverId: String): Flow<Set<String>>

    /**
     * The gateway's worktrees for [serverId], or null when it cannot be asked: not gateway-backed,
     * or the listing failed. Empty means the gateway answered with none. Their repos fold a chat's
     * worktree directory into its project.
     */
    suspend fun worktrees(serverId: String): List<WorktreeRef>?

    /** True when the tracked entry for [serverId]/[sessionId] is streaming. */
    fun isStreaming(
        serverId: String,
        sessionId: String,
    ): Boolean

    /** Records that [sessionId]'s chat screen opened, making it the most recently focused. */
    fun screenOpened(
        serverId: String,
        sessionId: String,
        cwd: String,
    )

    /** Records a chat this phone just created, so lists show it before the agent next reports it. */
    fun chatCreated(
        serverId: String,
        summary: SessionSummary,
    )

    /** Records that [sessionId]'s chat screen closed. */
    fun screenClosed(
        serverId: String,
        sessionId: String,
    )

    /**
     * True when [serverId] targets a paired gateway, whose sessions hold a gateway lease
     * a close has to release. False for direct (manual) agents.
     */
    suspend fun isGatewayBacked(serverId: String): Boolean

    /**
     * Closes [sessionId] on [serverId]: tracked chats go through the tracked close, cold
     * ones close the gateway session directly. Returns false when nothing was left to
     * close.
     */
    suspend fun closeSession(
        serverId: String,
        sessionId: String,
    ): Boolean
}

/**
 * Factory for creating per-session [ChatSessionFacade] instances.
 *
 * Implementations inject infrastructure dependencies and return a facade
 * tied to the provided [CoroutineScope] and session identifiers.
 */
interface ChatSessionFacadeFactory {
    fun create(
        scope: kotlinx.coroutines.CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade
}
