package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.git.files.FileView
import com.tamimarafat.ferngeist.core.git.state.GitState
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationRequest

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
    val authentication: AuthenticationRequest? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    /** See [com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot.dropsRepeatPrompts]. */
    val dropsRepeatPrompts: Boolean? = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: ChatConnectionState = ChatConnectionState.Disconnected,
    val connectionDiagnostics: ChatConnectionDiagnostics = ChatConnectionDiagnostics(),
    val configOptions: List<ChatConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<ChatCommand> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val pendingElicitations: List<ChatElicitationRequest> = emptyList(),
    val canSendImages: Boolean = false,
    val canSendAudio: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val supportsSteering: Boolean = false,
    /**
     * True when this screen attached to an existing session whose agent cannot
     * replay history, so earlier turns exist only on the agent's side and the
     * transcript starts empty. Drives the top-of-transcript resumed-session notice.
     */
    val resumedSession: Boolean = false,
    /**
     * True for a create-on-arrival chat. Only these show the empty-session hero: an existing
     * session can paint empty for a frame on attach, and the hero would flash over its transcript.
     */
    val isNewChat: Boolean = false,
    val gatewayWorkspaceConnection: GatewayWorkspaceConnection? = null,
    /** The file a transcript chip or a diff's open-full-file button opened; null while the sheet is closed. */
    val openFile: FileView? = null,
    /** The workspace's files by file name, for chips on names no tool call touched; null until listed. */
    val workspaceFiles: Map<String, List<String>>? = null,
    val git: GitState = GitState(),
    val error: String? = null,
    /**
     * Registry logo URL for this chat's agent, for identity marks like the
     * empty-session hero. Null for manual agents or when unresolved.
     */
    val iconUrl: String? = null,
)
