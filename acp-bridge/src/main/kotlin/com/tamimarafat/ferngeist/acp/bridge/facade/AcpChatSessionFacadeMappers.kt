package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.UsageState

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

internal fun mapDiagnostics(
    diag: ConnectionDiagnostics,
): ChatConnectionDiagnostics =
    ChatConnectionDiagnostics(
        serverUrl = diag.serverUrl,
        pendingRequestCount = diag.pendingRequestCount,
        recentErrors = diag.recentErrors.map { it.message },
        lastUpdatedAtMs = diag.lastUpdatedAtMs,
    )

internal fun mapCapabilities(caps: AgentCapabilities): ChatAgentCapabilities =
    ChatAgentCapabilities(
        canSendImages = caps.promptCapabilities.image,
        supportsEmbeddedContext = caps.promptCapabilities.embeddedContext,
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

internal fun mapConfigOption(
    option: SessionConfigOption,
): ChatConfigOption {
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

/** Maps a send error to a concise, bounded user-facing message. */
internal fun userFacingSendError(error: Throwable): String {
    val detailedMessage = formatAcpErrorMessage(error, "Send failed")
    val raw = error.message.orEmpty()
    val message =
        when {
            raw.contains("Request timeout", true) -> "Request timed out. Please try again."
            raw.contains("Invalid params", true) -> "Send failed due to an invalid request format."
            detailedMessage != "Send failed" -> detailedMessage
            else -> "Send failed due to an unknown error."
        }
    return if (message.length > MAX_SEND_ERROR_CHARS) {
        message.take(MAX_SEND_ERROR_CHARS).trimEnd() + "…"
    } else {
        message
    }
}

/** Returns true when the error indicates the server does not support session/cancel. */
internal fun isSessionCancelUnsupported(error: Throwable): Boolean {
    val raw = error.message.orEmpty()
    return raw.contains("Method not found", true) &&
        raw.contains("session/cancel", true)
}

/** Returns true when the error indicates the stream was destroyed (stale bridge). */
internal fun isDestroyedBridgeStreamError(error: Throwable): Boolean {
    val message = formatAcpErrorMessage(error, "").lowercase()
    return message.contains("write after a stream was destroyed") ||
        message.contains("stream was destroyed")
}
