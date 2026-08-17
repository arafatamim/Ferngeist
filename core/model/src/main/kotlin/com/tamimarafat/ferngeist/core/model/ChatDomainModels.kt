package com.tamimarafat.ferngeist.core.model

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
)

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
interface ChatSessionFacade {
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

    // One-shot event streams (not state snapshots):
    val loadFailed: SharedFlow<String>
    val operationError: SharedFlow<ChatOperationError>
    val streamingCancelled: SharedFlow<Unit>
    val cancelUnsupported: SharedFlow<Unit>
    val sessionReady: SharedFlow<Unit>
    val modelUpdated: SharedFlow<Unit>

    /** Loads an existing session or creates a new one. Emits [loadFailed] on error. */
    suspend fun loadSession()

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
