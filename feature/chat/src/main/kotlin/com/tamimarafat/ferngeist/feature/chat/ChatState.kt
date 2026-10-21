package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.UsageState

/** UI state for the chat screen. */
data class ChatState(
    val serverId: String = "",
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingMessages: List<ChatMessage> = emptyList(),
    /**
     * Transcript list key per message id, where it differs from the id: the reducer's echo of a
     * sent prompt has a new id, and keeps the pending bubble's key so the list moves the same
     * item instead of swapping one for another.
     */
    val listKeys: Map<String, String> = emptyMap(),
    val markdownDocuments: Map<String, MarkdownRenderedDocument> = emptyMap(),
    val restoredScrollSnapshot: ChatScrollSnapshot? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: ChatConnectionState = ChatConnectionState.Disconnected,
    val connectionDiagnostics: ChatConnectionDiagnostics = ChatConnectionDiagnostics(),
    val configOptions: List<ChatConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<ChatCommand> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val pendingElicitations: List<ChatElicitationRequest> = emptyList(),
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val supportsSteering: Boolean = false,
    /**
     * True when this screen attached to an existing session whose agent cannot
     * replay history, so earlier turns exist only on the agent's side and the
     * transcript starts empty. Drives the top-of-transcript resumed-session notice.
     */
    val resumedSession: Boolean = false,
    val gatewayWorkspaceConnection: GatewayWorkspaceConnection? = null,
    /** The file a transcript chip or a diff's open-full-file button opened; null while the sheet is closed. */
    val openFile: FileView? = null,
    /** The workspace's files by file name, for chips on names no tool call touched; null until listed. */
    val workspaceFiles: Map<String, List<String>>? = null,
    val gitStatus: ChatGitStatus? = null,
    /** The gateway worktree this chat runs in; null when it is not one or the gateway has not said yet. */
    val worktree: ChatWorktree? = null,
    val gitDiffStats: GitDiffStats? = null,
    val gitFileDiff: List<ToolCallContent.Diff>? = null,
    val gitFileDiffPath: String? = null,
    val isGitFileDiffLoading: Boolean = false,
    val gitFileDiffError: String? = null,
    /** The commit [gitFileDiff] was read from; null for the working tree. */
    val gitFileDiffCommit: String? = null,
    /** The qualified compare ref [gitFileDiff] was read against; null for the working tree or a commit. */
    val gitFileDiffCompare: String? = null,
    /**
     * Working-tree diffs backing the latest turn's files whose tool calls reported
     * none, keyed by git-relative path. Read only for the newest turn: git
     * describes the tree as it is now, so an older turn must never borrow them.
     */
    val turnGitDiffs: Map<String, List<ToolCallContent.Diff>> = emptyMap(),
    val turnGitDiffsLoading: Set<String> = emptySet(),
    val turnGitDiffsErrors: Map<String, String> = emptyMap(),
    /** Git paths already changed when the newest turn started; null when git was not known then. */
    val turnDirtyAtStart: Set<String>? = null,
    val gitHistory: GitHistoryState = GitHistoryState(),
    /** The branch the git sheet compares against; null is `Working tree`. Lives for this view model only. */
    val compareRef: ChatGitRef? = null,
    val gitRefs: GitRefsState = GitRefsState(),
    /**
     * The working tree against [compareRef], kept apart from [gitStatus] and [gitDiffStats]: those
     * feed the top-bar pill and the transcript's per-turn rows, which stay working-tree-relative.
     */
    val compareStatus: ChatGitStatus? = null,
    val isCompareStatusLoading: Boolean = false,
    val compareStatusError: String? = null,
    val error: String? = null,
    /**
     * Registry logo URL for this chat's agent, for identity marks like the
     * empty-session hero. Null for manual agents or when unresolved.
     */
    val iconUrl: String? = null,
)

/** What the compare picker lists. A failed reload keeps [refs]; [unsupported] hides the whole affordance. */
data class GitRefsState(
    /** The repo's main branch name, sorting the picker and preselected from any other branch; "" when unknown. */
    val default: String = "",
    val refs: List<ChatGitRef> = emptyList(),
    /** `/git/refs` has answered at least once. */
    val isLoaded: Boolean = false,
    val unsupported: Boolean = false,
)

/** The git sheet's History tab and the commit it has open. */
data class GitHistoryState(
    /** The sha page 1 was read from, pinned for later pages; null before then or for an empty repository. */
    val head: String? = null,
    val commits: List<ChatGitCommit> = emptyList(),
    val hasMore: Boolean = false,
    val remoteUrl: String? = null,
    /** The whole walk's size, shown as the History tab's badge; null when the gateway predates it. */
    val total: Int? = null,
    /** The commit the gateway reads this workspace against, marking the branch's own commits; null when there is none. */
    val base: String? = null,
    /** Page 1 has answered at least once, so an empty [commits] means an empty repository. */
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Page 1 failed. */
    val error: String? = null,
    /** A later page failed; the list stays and offers a retry at its end. */
    val moreError: String? = null,
    /** The gateway predates history, so the sheet hides the tab. */
    val unsupported: Boolean = false,
    val commitSha: String? = null,
    val commitFiles: List<ChatChangedFile>? = null,
    val isCommitLoading: Boolean = false,
    val commitError: String? = null,
) {
    /** A next page exists from a pinned head and no page is in flight. */
    val canLoadMore: Boolean get() = hasMore && head != null && !isLoading && !isLoadingMore
}
